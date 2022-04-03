package bleep

import bleep.BuildPaths.Mode.Normal
import bleep.CoursierResolver.Authentications
import bleep.commands.Import
import bleep.commands.Import.Options
import bleep.internal.{importBloopFilesFromSbt, FileUtils, ReadSbtExportFile, Replacements}
import bloop.config.Config
import coursier.Repositories
import coursier.paths.CoursierPaths
import org.scalatest.Assertion

import java.net.URI
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.IteratorHasAsScala

class IntegrationSnapshotTests extends SnapshotTest {
  val logger = logging.stdout(LogPatterns.logFile).untyped
  val inFolder = Paths.get("snapshot-tests-in").toAbsolutePath
  val outFolder = Paths.get("snapshot-tests").toAbsolutePath
  val resolver: CoursierResolver = {
    val sbtReleases = model.Repository.Ivy(URI.create(Repositories.sbtPlugin("releases").pattern.chunks.head.string))
    val cachePath = if (isCi) CoursierPaths.cacheDirectory().toPath / "sneaky-bleep-cache" else UserPaths.fromAppDirs.cacheDir
    CoursierResolver(List(sbtReleases), logger, downloadSources = false, cacheIn = Some(cachePath), Authentications.empty)
  }

  test("tapir") {
    testIn("tapir")
  }

  test("doobie") {
    testIn("doobie")
  }

  test("http4s") {
    testIn("http4s")
  }

  test("converter") {
    testIn("converter")
  }

  def testIn(project: String): Assertion = {
    val sbtBuildDir = inFolder / project
    val destinationPaths = BuildPaths.fromBuildDir(_cwd = Path.of("/tmp"), outFolder / project, Normal)
    val importer = commands.Import(sbtBuildDir, destinationPaths, logger, Options(ignoreWhenInferringTemplates = Set.empty, skipSbt = false))

    // if this directory exists, assume it has all files in good condition, but with paths not filled in
    if (!Files.exists(destinationPaths.bleepImportDir)) {
      // if not, generate all bloop and dependency files
      importer.generateBloopAndDependencyFiles()

      // remove machine-specific paths inside bloop files files
      Import.findGeneratedJsonFiles(destinationPaths.bleepImportBloopDir).foreach { bloopFilePath =>
        val contents = Files.readString(bloopFilePath)
        val templatedContents = absolutePaths.templatize.string(contents)
        FileUtils.writeString(bloopFilePath, templatedContents)
      }
    }

    val sbtExportFiles = Import.findGeneratedJsonFiles(destinationPaths.bleepImportSbtExportDir).map { path =>
      val contents = Files.readString(path)
      (path, contents, ReadSbtExportFile.parse(path, contents))
    }

    val importedBloopFiles: Iterable[(Path, String, Config.File)] =
      Import
        .findGeneratedJsonFiles(destinationPaths.bleepImportBloopDir)
        .map { bloopFilePath =>
          val originalContents = Files.readString(bloopFilePath)
          val importedBloopFile = {
            val templatedContents = absolutePaths.fill.string(originalContents)
            GenBloopFiles.parseBloopFile(templatedContents)
          }
          (bloopFilePath, originalContents, importedBloopFile)
        }

    // generate a build file and store it
    val buildFiles: Map[Path, String] =
      importer.generateBuild(
        importedBloopFiles.map { case (_, _, file) => file },
        sbtExportFiles.map { case (_, _, sbtExportFile) => sbtExportFile },
        hackDropBleepDependency = true
      )

    // writ read that build file, and produce an (in-memory) exploded build plus new bloop files
    FileUtils.syncPaths(destinationPaths.buildDir, buildFiles, deleteUnknowns = FileUtils.DeleteUnknowns.No, soft = true)
    val started = bootstrap.from(Prebootstrapped(destinationPaths, logger), GenBloopFiles.InMemory, rewrites = Nil) match {
      case Left(th)       => throw th
      case Right(started) => started
    }

    // will produce templated bloop files we use to overwrite the bloop files already written by bootstrap
    val generatedBloopFiles: Map[Path, String] =
      GenBloopFiles.encodedFiles(destinationPaths, started.bloopFiles).map { case (p, s) => (p, absolutePaths.templatize.string(s)) }

    val allFiles: Map[Path, String] =
      buildFiles ++
        generatedBloopFiles ++
        importedBloopFiles.map { case (p, s, _) => (p, s) } ++
        sbtExportFiles.map { case (p, s, _) => (p, s) }

    // further property checks to see that we haven't made any illegal rewrites
    assertSameIshBloopFiles(importedBloopFiles.map { case (_, _, f) => f }, started)

    // flush templated bloop files to disk if local, compare to checked in if test is running in CI
    // note, keep last. locally it "succeeds" with a `pending`
    writeAndCompare(destinationPaths.buildDir, allFiles)
  }

  def assertSameIshBloopFiles(importedBloopFiles: Iterable[Config.File], started: Started): Assertion = {
    // compare some key properties before and after import
    val inputProjects: Map[(model.ProjectName, Option[String], Option[String]), Config.Project] =
      importedBloopFiles.map { case Config.File(_, p) =>
        ((importBloopFilesFromSbt.projectName(p.name), p.platform.map(_.name), p.scala.map(_.version)), p)
      }.toMap

    started.bloopProjects.foreach {
      case (crossProjectName, _) if crossProjectName.value == "scripts" => ()
      case (crossProjectName, output) =>
        val input = inputProjects((crossProjectName.name, output.platform.map(_.name), output.scala.map(_.version)))

        // todo: this needs further work,
        //      assert(
        //        output.platform == input.platform,
        //        crossProjectName.value
        //      )

        // scalacOptions are the same, modulo ordering, duplicates and target directory
        def patchedOptions(project: Config.Project, targetDir: Path): List[String] = {
          val replacements = Replacements.targetDir(targetDir) ++
            Replacements.ofReplacements(List(("snapshot-tests-in", "snapshot-tests")))
          val original = project.scala.map(_.options).getOrElse(Nil)
          original.map(replacements.templatize.string).sorted.distinct
        }

        val originalTargetDir = internal.findOriginalTargetDir.force(crossProjectName, input)
        assert(
          patchedOptions(output, output.out) == patchedOptions(input, originalTargetDir),
          crossProjectName.value
        )

        // assert that all source folders are conserved. currently bleep may add some. also we drop folders for generated stuff
        val target = Path.of("target")
        assert(
          input.sources.filterNot(_.startsWith(originalTargetDir)).sorted.forall(output.sources.contains),
          crossProjectName.value
        )
        // assert that all resource folders are conserved. currently bleep may add some
        assert(
          input.resources.getOrElse(Nil).filterNot(_.iterator().asScala.contains(target)).sorted.forall(output.resources.getOrElse(Nil).contains),
          crossProjectName.value
        )

        /** @param classesDirs
          *   classes directories are completely different in bleep. we may also drop projects, so no comparisons are done for these, unfortunately.
          *
          * For instance:
          *   - ~/bleep/snapshot-tests/converter/.bleep/.bloop/phases/classes
          *   - ~/bleep/snapshot-tests/converter/.bleep/import/bloop/2.12/phases/scala-2.12/classes,
          *
          * @param scalaJars
          *   paths expected to be completely different because in sbt they may be resolved by launcher/ivy
          *
          * For instance:
          *   - ~/.sbt/boot/scala-2.12.14/lib/scala-reflect.jar
          *   - ~/.cache/coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-reflect/2.12.2/scala-reflect-2.12.2.jar,
          *
          * @param restJars
          *   the remaining set of differing jars should be empty
          */
        case class AnalyzedClassPathDiff(classesDirs: Set[Path], scalaJars: Set[Path], restJars: Set[Path])
        object AnalyzedClassPathDiff {
          def from(paths: Set[Path]): AnalyzedClassPathDiff = {
            val (classes, jars) = paths.partition(p => p.endsWith("classes") || p.endsWith("test-classes"))
            // note that paths are difficult here, we may receive files from sbt launcher in boot folder
            val (scalaJars, restJars) = jars.partition(p => p.getFileName.toString.startsWith("scala"))
            AnalyzedClassPathDiff(classes, scalaJars, restJars)
          }
        }

        val added = AnalyzedClassPathDiff.from(output.classpath.toSet -- input.classpath)
        val removed = AnalyzedClassPathDiff.from(input.classpath.toSet -- output.classpath)

        def render(paths: Iterable[Path]): String =
          if (paths.isEmpty) "nothing"
          else paths.mkString("\n", ",\n", "\n")

        if (added.scalaJars.size != removed.scalaJars.size) {
          System.err.println {
            List(
              crossProjectName.value,
              ": Expected there to be equal number of scala jars. added :",
              render(added.scalaJars),
              ", removed: ",
              render(removed.scalaJars)
            ).mkString("")
          }
        }
        if (added.restJars.nonEmpty || removed.restJars.nonEmpty) {
          System.err.println(s"${crossProjectName.value}: Added ${render(added.restJars)} to classPath, Removed ${render(removed.restJars)} from classPath")
        }
    }
    succeed
  }
}

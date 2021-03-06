package pl.project13.scala.sbt

import sbt._
import sbt.Keys._
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.generators.bytecode.JmhBytecodeGenerator
import scala.tools.nsc.util.ScalaClassLoader.URLClassLoader

object JmhPlugin extends AutoPlugin {

  object JmhKeys {
    val Jmh = config("jmh") extend Test
    val generatorType = settingKey[String]("Benchmark code generator type. Available: `default`, `reflection` or `asm`.")
  }

  import JmhKeys._

  val autoImport = JmhKeys

  val generateJmhSourcesAndResources = taskKey[(Seq[File], Seq[File])]("Generate benchmark JMH Java code and resources")

  /** All we need is Java. */
  override def requires = plugins.JvmPlugin

  /** Plugin must be enabled on the benchmarks project. See http://www.scala-sbt.org/0.13/tutorial/Using-Plugins.html */
  override def trigger = noTrigger

  override def projectConfigurations = Seq(Jmh)

  override def projectSettings = inConfig(Jmh)(Defaults.testSettings ++ Seq(
    // settings in Jmh
    version := "1.11",
    generatorType := "default",

    mainClass in run := Some("org.openjdk.jmh.Main"),
    fork in run := true, // makes sure that sbt manages classpath for JMH when forking

    sourceGenerators := Seq(Def.task { generateJmhSourcesAndResources.value._1 }.taskValue),
    resourceGenerators := Seq(Def.task { generateJmhSourcesAndResources.value._2 }.taskValue),
    generateJmhSourcesAndResources := generateBenchmarkSourcesAndResources(streams.value, crossTarget.value / "jmh-cache", (classDirectory in Compile).value, sourceManaged.value, resourceManaged.value, generatorType.value, (dependencyClasspath in Compile).value),
    generateJmhSourcesAndResources <<= generateJmhSourcesAndResources dependsOn(compile in Compile)
  )) ++ Seq(
    // settings in default

    // includes the asm jar only if needed
    libraryDependencies ++= {
      val jmhVersion = (version in Jmh).value
      Seq(
        "org.openjdk.jmh" % "jmh-core"                 % jmhVersion,   // GPLv2
        "org.openjdk.jmh" % "jmh-generator-bytecode"   % jmhVersion,   // GPLv2
        "org.openjdk.jmh" % "jmh-generator-reflection" % jmhVersion    // GPLv2
      ) ++ ((generatorType in Jmh).value match {
        case "default" | "reflection" => Nil // default == reflection (0.9)
        case "asm"                    => Seq("org.openjdk.jmh" % "jmh-generator-asm" % jmhVersion)    // GPLv2
        case unknown                  => throw new IllegalArgumentException(s"Unknown benchmark generator type: $unknown, please use one of the supported generators!")
      })
    }
  )

  private def generateBenchmarkSourcesAndResources(s: TaskStreams, cacheDir: File, bytecodeDir: File, sourceDir: File, resourceDir: File, generatorType: String, classpath: Seq[Attributed[File]]): (Seq[File], Seq[File]) = {
    val inputs: Set[File] = (bytecodeDir ** "*").filter(_.isFile).get.toSet
    val cachedGeneration = FileFunction.cached(cacheDir, FilesInfo.hash) { _ =>
      // ignore change report and rebuild it completely
      internalGenerateBenchmarkSourcesAndResources(s, bytecodeDir, sourceDir, resourceDir, generatorType, classpath)
    }
    cachedGeneration(inputs).toSeq.partition(f => IO.relativizeFile(sourceDir, f).nonEmpty)
  }

  private def internalGenerateBenchmarkSourcesAndResources(s: TaskStreams, bytecodeDir: File, sourceDir: File, resourceDir: File, generatorType: String, classpath: Seq[Attributed[File]]): Set[File] = {
    // rebuild everything
    IO.delete(sourceDir)
    IO.createDirectory(sourceDir)
    IO.delete(resourceDir)
    IO.createDirectory(resourceDir)

    // since we might end up using reflection (even in ASM generated benchmarks), we have to set up the classpath to include classes our code depends on
    val bench = classOf[Benchmark]
    val loader = new URLClassLoader(classpath.map(_.data.toURI.toURL), bench.getClassLoader)
    val old = Thread.currentThread.getContextClassLoader
    Thread.currentThread.setContextClassLoader(loader)
    JmhBytecodeGenerator.main(Array(bytecodeDir, sourceDir, resourceDir, generatorType).map(_.toString))
    Thread.currentThread.setContextClassLoader(old)
    ((sourceDir ** "*").filter(_.isFile) +++ (resourceDir ** "*").filter(_.isFile)).get.toSet
  }
}


import Settings._
import com.typesafe.sbt.packager.archetypes.JavaAppPackaging

inThisBuild(List(
  organization := "com.apple.pie.almond",
  homepage := Some(url("https://github.com/almond-sh/almond")),
  licenses := List("BSD-3-Clause" -> url("https://opensource.org/licenses/BSD-3-Clause")),
  developers := List(
    Developer(
      "alexarchambault",
      "Alexandre Archambault",
      "alexandre.archambault@gmail.com",
      url("https://github.com/alexarchambault")
    )
  ),
  version := {
    // Simple X.Y.Z-SNAPSHOT versions are easier to find once published locally
    val forceSimpleVersion = sys.env
      .get("FORCE_SIMPLE_VERSION")
      .contains("1")
    val onTravisCi = sys.env.exists(_._1.startsWith("TRAVIS_"))
    val v = version.value
    if ((forceSimpleVersion || !onTravisCi) && v.contains("+") && v.endsWith("-SNAPSHOT")) {
      val base = v.takeWhile(_ != '+')
      val elems = base.split('.')
      val last = scala.util.Try(elems.last.toInt)
        .toOption
	.fold(elems.last)(n => (n + 1).toString)
      val bumpedBase = (elems.init :+ last).mkString(".")
      bumpedBase + "-SNAPSHOT"
    } else
      v
  }
))

lazy val logger = project
  .underShared
  .settings(
    getSharedSettings("logger"),
    testSettings,
    libraryDependencies += Deps.scalaReflect.value
  ).enablePlugins(JavaAppPackaging)

lazy val channels = project
  .underShared
  .dependsOn(logger)
  .settings(
    getSharedSettings("channels"),
    testSettings,
    libraryDependencies ++= Seq(
      Deps.fs2,
      Deps.jeromq
    )
  ).enablePlugins(JavaAppPackaging)

lazy val protocol = project
  .underShared
  .dependsOn(channels)
  .settings(
    getSharedSettings("protocol"),
    libraryDependencies += Deps.argonautShapeless
  ).enablePlugins(JavaAppPackaging)

lazy val `interpreter-api` = project
  .underShared
  .settings(
    getSharedSettings("interpreter-api"),
  ).enablePlugins(JavaAppPackaging)

lazy val interpreter = project
  .underShared
  .dependsOn(`interpreter-api`, protocol)
  .settings(
    getSharedSettings("interpreter"),
    libraryDependencies ++= Seq(
      Deps.scalatags,
      // picked by jboss-logging, that metabrowse transitively depends on
      Deps.slf4jNop
    ),
    testSettings
  ).enablePlugins(JavaAppPackaging)

lazy val kernel = project
  .underShared
  .dependsOn(interpreter, interpreter % "test->test")
  .settings(
    getSharedSettings("kernel"),
    testSettings,
    libraryDependencies ++= Seq(
      Deps.caseAppAnnotations,
      Deps.fs2
    )
  ).enablePlugins(JavaAppPackaging)

lazy val test = project
  .underShared
  .dependsOn(`interpreter-api`)
  .settings(
    getSharedSettings("test")
  ).enablePlugins(JavaAppPackaging)

lazy val `jupyter-api` = project
  .underScala
  .dependsOn(`interpreter-api`)
  .settings(
    getSharedSettings("jupyter-api"),
    libraryDependencies += Deps.jvmRepr
  ).enablePlugins(JavaAppPackaging)

lazy val `scala-kernel-api` = project
  .underScala
  .dependsOn(`interpreter-api`, `jupyter-api`)
  .settings(
    getSharedSettings("scala-kernel-api"),
    crossVersion := CrossVersion.full,
    generatePropertyFile("almond/almond.properties"),
    generateDependenciesFile,
    libraryDependencies ++= Seq(
      Deps.ammoniteReplApi.value,
      Deps.jvmRepr
    )
  ).enablePlugins(JavaAppPackaging)

lazy val `scala-interpreter` = project
  .underScala
  .dependsOn(interpreter, `scala-kernel-api`, kernel % "test->test", `almond-rx` % Test)
  .settings(
    getSharedSettings("scala-interpreter"),
    libraryDependencies ++= {
      val sv = scalaVersion.value
      if (sv.startsWith("2.12.")) {
        val patch = sv.stripPrefix("2.12.").takeWhile(_.isDigit).toInt
        if (patch <= 8)
          Seq(Deps.metabrowseServer)
        else
          Nil
      } else
        Nil
    },
    libraryDependencies ++= Seq(
      Deps.coursier,
      Deps.coursierApi,
      Deps.directories,
      Deps.jansi,
      Deps.ammoniteRepl.value
    ),
    crossVersion := CrossVersion.full,
    testSettings
  ).enablePlugins(JavaAppPackaging)

lazy val `scala-kernel` = project
  .underScala
  .enablePlugins(PackPlugin, JavaAppPackaging)
  .dependsOn(kernel, `scala-interpreter`)
  .settings(
    getSharedSettings("scala-kernel"),
    crossVersion := CrossVersion.full,
    libraryDependencies += Deps.caseApp,
    packExcludeArtifactTypes -= "source",
    packModuleEntries ++= {
      val report = updateClassifiers.value
      for {
        c <- report.configurations
        m <- c.modules
        (a, f) <- m.artifacts
        if a.classifier.contains("sources")
      } yield xerial.sbt.pack.PackPlugin.ModuleEntry(
        m.module.organization,
        m.module.name,
        xerial.sbt.pack.VersionString(m.module.revision),
        a.name,
        a.classifier,
        f
      )
    }
  )

lazy val echo = project
  .underModules
  .dependsOn(kernel, test % Test)
  .settings(
    getSharedSettings("echo"),
    generatePropertyFile("almond/echo.properties"),
    testSettings,
    libraryDependencies += Deps.caseApp
  ).enablePlugins(JavaAppPackaging)

lazy val `almond-spark` = project
  .underScala
  .dependsOn(`scala-kernel-api` % "provided")
  .settings(
    getSharedSettings("almond-spark"),
    libraryDependencies ++= Seq(
      Deps.ammoniteReplApi.value % "provided",
      Deps.ammoniteSpark,
      Deps.argonautShapeless,
      Deps.sparkSql % "provided"
    ),
    onlyIn("2.12")
  ).enablePlugins(JavaAppPackaging)

lazy val `almond-rx` = project
  .underScala
  .dependsOn(`scala-kernel-api` % Provided)
  .settings(
    getSharedSettings("almond-rx"),
    libraryDependencies += Deps.scalaRx,
    onlyIn("2.12")
  ).enablePlugins(JavaAppPackaging)

lazy val almond = project
  .in(file("."))
  .aggregate(
    `almond-rx`,
    `almond-spark`,
    channels,
    echo,
    `interpreter-api`,
    interpreter,
    `jupyter-api`,
    kernel,
    logger,
    protocol,
    `scala-interpreter`,
    `scala-kernel-api`,
    `scala-kernel`,
    test
  )
  .settings(
    getSharedSettings("almond")
  ).enablePlugins(JavaAppPackaging)

lazy val jupyterStart = taskKey[Unit]("")
lazy val jupyterStop = taskKey[Unit]("")
lazy val jupyterDir = taskKey[File]("")

jupyterDir := {
  baseDirectory.in(ThisBuild).value / "target" / "jupyter"
}

lazy val jupyterCommand = Seq("jupyter", "lab")

jupyterStart := {
  val pack0 = (pack.in(`scala-kernel`).value / "bin" / "scala-kernel").getAbsolutePath
  val jupyterDir0 = jupyterDir.value
  val dir = jupyterDir0 / "kernels" / "scala"
  dir.mkdirs()
  val kernelJson = s"""{
    "language": "scala",
    "display_name": "Scala (sources)",
    "argv": [
      "$pack0",
      "--metabrowse", "--log", "info",
      "--connection-file", "{connection_file}"
    ]
  }"""
  java.nio.file.Files.write((dir / "kernel.json").toPath, kernelJson.getBytes("UTF-8"))

  val b = new ProcessBuilder(jupyterCommand: _*).inheritIO()
  val env = b.environment()
  env.put("JUPYTER_PATH", jupyterDir0.getAbsolutePath)
  val p = b.start()
  val pidOpt = try {
    val fld = p.getClass.getDeclaredField("pid")
    fld.setAccessible(true)
    Some(fld.getInt(p))
  } catch {
    case _: Throwable => None
  }
  for (pid <- pidOpt) {
    java.nio.file.Files.write((jupyterDir0 / "pid").toPath, pid.toString.getBytes("UTF-8"))
    java.lang.Runtime.getRuntime.addShutdownHook(
      new Thread("jupyter-stop") {
        override def run() =
          Helper.jupyterStop(jupyterDir0)
      }
    )
  }
}

lazy val Helper = new {
  def jupyterStop(jupyterDir: File): Unit = {
    val pidFile = jupyterDir / "pid"
    if (pidFile.exists()) {
      val b = java.nio.file.Files.readAllBytes((jupyterDir / "pid").toPath)
      val pid = new String(b, "UTF-8").toInt
      new ProcessBuilder("kill", pid.toString).start().waitFor()
      java.nio.file.Files.deleteIfExists(pidFile.toPath)
    }
  }
}

jupyterStop := {
  val jupyterDir0 = jupyterDir.value
  Helper.jupyterStop(jupyterDir0)
}

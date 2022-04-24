import sbt._

val scala2 = "2.13.8"

// val scala3 = "3.1.0"

ThisBuild / organization := "com.caesars"
ThisBuild / scalaVersion := scala2
// ThisBuild / crossScalaVersions := List(scala2, scala3)
ThisBuild / versionScheme := Some("early-semver")
// usually, this is set by sbt-dynver. For some cases, like an out-of-bound docker image, allow override
ThisBuild / version ~= (v => sys.env.getOrElse("SBT_VERSION_OVERRIDE", v))

addCommandAlias("lint", "; scalafmtSbt; scalafmtAll")

lazy val root = (project in file("."))
  .settings(
    name := "trace4cats-zio-extras",
  )
  .aggregate(core, sttp, zhttp, testkit)

lazy val core = mkModule("core")
  .settings(
    libraryDependencies ++= List(
      // TODO: replace Calvin's version with the canonical one once its released
      "com.github.kaizen-solutions.trace4cats-newrelic" %% "trace4cats-newrelic-http-exporter" % "0.12.2",
      "org.http4s" %% "http4s-async-http-client" % "0.23.10",
    )
  )

lazy val sttp = mkModule("sttp")
  .settings(
    libraryDependencies ++= List(
      "com.softwaremill.sttp.client3" %% "async-http-client-backend-zio1" % "3.5.0",
      trace4cats("sttp-client3"),
    )
  )
  .dependsOn(core)

lazy val zhttp = mkModule("zhttp")
  .settings(
    libraryDependencies ++= List(
      "com.softwaremill.sttp.tapir" %% "tapir-zio1-http-server" % "0.20.1",
    )
  )
  .dependsOn(core)

lazy val testkit = mkModule("testkit")
  .dependsOn(core, sttp, zhttp)

def zio(name: String) =
  "dev.zio" %% name % "1.0.13"

def trace4cats(name: String) =
  "io.janstenpickle" %% s"trace4cats-$name" % "0.13.1"

def mkModule(id: String) = {
  val projectName = s"trace4cats-zio-extras-$id"
  Project(projectName, file("modules") / id)
    .settings(
      name := projectName,
      Compile / console / scalacOptions ~= (_.filterNot(Set("-Xfatal-warnings"))),
      Test / fork := true,
      Test / testForkedParallel := true,
      Test / parallelExecution := true,
      testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
      scalacOptions ++= {
        val opts = CrossVersion.partialVersion(scalaVersion.value) match {
          case Some((2, _)) =>
            List(
              "-language:postfixOps",
              "-Wconf:cat=w-flag-dead-code:silent",
              "-Wunused:_,-implicits",
              // helps with unused implicits warning
              "-Ywarn-macros:after",
              "-Xsource:3",
              "-P:kind-projector:underscore-placeholders",
              "-Xsource:3"
            )
          case _ =>
            List("-Ykind-projector:underscores")
        }
        "-language:postfixOps" :: opts
      },
      resolvers ++= Seq("jitpack" at "https://jitpack.io"),
      libraryDependencies ++= Seq(
        trace4cats("base-zio"),
        trace4cats("inject-zio"),
        "dev.zio" %% "zio-interop-cats" % "3.2.9.1",
        zio("zio"),
        zio("zio-test") % Test,
        zio("zio-test-sbt") % Test,
        "io.github.kitlangton" %% "zio-magic" % "0.3.11" % Test,
      ),
      libraryDependencies ++= {
        if (CrossVersion.partialVersion(scalaVersion.value).exists(_._1 == 2))
          List(
            compilerPlugin("org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full),
            compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
          )
        else
          Nil
      }
    )
}

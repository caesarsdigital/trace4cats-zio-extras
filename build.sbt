import sbt._

val scala2 = "2.13.8"
val scala3 = "3.1.2"

ThisBuild / organization := "com.caesars"
ThisBuild / scalaVersion := scala3
ThisBuild / crossScalaVersions := List(scala3, scala2)
ThisBuild / versionScheme := Some("early-semver")
// usually, this is set by sbt-dynver. For some cases, like an out-of-bound docker image, allow override
ThisBuild / version ~= (v => sys.env.getOrElse("SBT_VERSION_OVERRIDE", v))

addCommandAlias("lint", "; scalafmtSbt; scalafmtAll")

lazy val root = (project in file("."))
  .settings(name := "root")
  .aggregate(zio1)

lazy val zio1 = mkZIOModule("zio1")
  .aggregate(coreZIO1, sttpZIO1, zhttpZIO1, testKitZIO1)

lazy val coreZIO1 = mkCore("zio1", "1.0.15")
lazy val sttpZIO1 = mkSttp("zio1", "1.0.15").dependsOn(coreZIO1)
lazy val zhttpZIO1 = mkZhttp("zio1", "1.0.15").dependsOn(coreZIO1)
lazy val testKitZIO1 =
  mkTestKit("zio1", "1.0.15").dependsOn(coreZIO1, sttpZIO1, zhttpZIO1)

def mkZIOModule(zioAlias: String): Project =
  Project(zioAlias, file(s"./modules/$zioAlias"))
    .settings(
      name := s"trace4cats-$zioAlias-extras"
    )

def mkCore(zioAlias: String, zioVersion: String): Project =
  mkModule(zioAlias, zioVersion)("core")
    .settings(
      libraryDependencies ++= List(
        trace4cats("newrelic-http-exporter"),
        "org.http4s" %% "http4s-async-http-client" % "0.23.10"
      )
    )

def mkSttp(zioAlias: String, zioVersion: String): Project =
  mkModule(zioAlias, zioVersion)("sttp")
    .settings(
      libraryDependencies ++= List(
        "com.softwaremill.sttp.client3" %% s"async-http-client-backend-$zioAlias" % "3.6.1",
        trace4cats("sttp-client3")
      )
    )

def mkZhttp(zioAlias: String, zioVersion: String): Project =
  mkModule(zioAlias, zioVersion)("zhttp")
    .settings(
      libraryDependencies ++= List(
        "com.softwaremill.sttp.tapir" %% s"tapir-$zioAlias-http-server" % "0.20.1"
      )
    )

def mkTestKit(zioAlias: String, zioVersion: String): Project =
  mkModule(zioAlias, zioVersion)("testkit")

def trace4cats(name: String) =
  "io.janstenpickle" %% s"trace4cats-$name" % "0.13.1"

def zio(name: String, zioVersion: String) =
  "dev.zio" %% name % zioVersion

def mkModule(zioAlias: String, zioVersion: String)(id: String) = {
  val projectName = s"trace4cats-$zioAlias-extras-$id"
  Project(projectName, file("modules") / zioAlias / id)
    .settings(
      name := projectName,
      Compile / console / scalacOptions ~= (_.filterNot(
        Set("-Xfatal-warnings")
      )),
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
              "-P:kind-projector:underscore-placeholders",
              "-Xsource:3"
            )
          case _ =>
            Nil
        }
        "-language:postfixOps" :: opts
      },
      resolvers ++= Seq("jitpack" at "https://jitpack.io"),
      libraryDependencies ++= Seq(
        trace4cats("base-zio"),
        trace4cats("inject-zio"),
        "dev.zio" %% "zio-interop-cats" % "3.2.9.1",
        zio("zio", zioVersion),
        zio("zio-test", zioVersion) % Test,
        zio("zio-test-sbt", zioVersion) % Test
      ),
      libraryDependencies ++= {
        if (CrossVersion.partialVersion(scalaVersion.value).exists(_._1 == 2))
          List(
            compilerPlugin(
              "org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full
            ),
            compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
          )
        else
          Nil
      }
    )
}

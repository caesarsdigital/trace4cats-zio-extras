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
  .settings(name := "trace4cats-zio-extras")
  .aggregate(
    coreZIO1, sttpZIO1, zhttpZIO1, testKitZIO1,
    coreZIO2, sttpZIO2, zhttpZIO2, testKitZIO2,
  )

lazy val coreZIO1 = mkCore(ZioAlias.zio1)
lazy val sttpZIO1 = mkSttp(ZioAlias.zio1).dependsOn(coreZIO1)
lazy val zhttpZIO1 = mkZhttp(ZioAlias.zio1).dependsOn(coreZIO1)
lazy val testKitZIO1 = mkTestKit(ZioAlias.zio1).dependsOn(coreZIO1, sttpZIO1, zhttpZIO1)

lazy val coreZIO2 = mkCore(ZioAlias.zio2)
lazy val sttpZIO2 = mkSttp(ZioAlias.zio2).dependsOn(coreZIO2)
lazy val zhttpZIO2 = mkZhttp(ZioAlias.zio2).dependsOn(coreZIO2)
lazy val testKitZIO2 = mkTestKit(ZioAlias.zio2).dependsOn(coreZIO2, sttpZIO2, zhttpZIO2)

def mkCore(zioAlias: ZioAlias): Project =
  mkModule(zioAlias)("core")
    .settings(
      libraryDependencies ++= List(
        trace4cats("newrelic-http-exporter"),
        "org.http4s" %% "http4s-async-http-client" % "0.23.10"
      )
    )

/* excludeAll(ExclusionRule("org.scala-lang.modules")) because:
    [error] Modules were resolved with conflicting cross-version suffixes in ProjectRef(uri("file:/Users/anakos/projects/pam/trace4cats-zio-extras/"), "trace4cats-zio2-extras-sttp"):
    [error]    org.scala-lang.modules:scala-collection-compat _3, _2.13
 */
def mkSttp(zioAlias: ZioAlias): Project =
  mkModule(zioAlias)("sttp")
    .settings(
      libraryDependencies ++= List(
        zioAlias.sttp,
        trace4cats("sttp-client3")
      )
      .map { _.excludeAll(ExclusionRule("org.scala-lang.modules")) }
    )

def mkZhttp(zioAlias: ZioAlias): Project =
  mkModule(zioAlias)("zhttp")
    .settings( libraryDependencies += zioAlias.tapir)

def mkTestKit(zioAlias: ZioAlias): Project =
  mkModule(zioAlias)("testkit")

def trace4cats(name: String) =
  "io.janstenpickle" %% s"trace4cats-$name" % "0.13.1"

def zio(name: String, zioVersion: String) =
  "dev.zio" %% name % zioVersion

def mkModule(zioAlias: ZioAlias)(id: String) = {
  val projectName = s"trace4cats-$zioAlias-extras-$id"
  Project(projectName, file("modules") / zioAlias.toString() / id)
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
        zioAlias.interop,
        zio("zio", zioAlias.verzion),
        zio("zio-test", zioAlias.verzion) % Test,
        zio("zio-test-sbt", zioAlias.verzion) % Test
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

import sbt.librarymanagement.ModuleID
import sbt._

sealed abstract class ZioAlias(
    val verzion: String,
    val interop: ModuleID,
    val sttp: ModuleID,
    val tapir: ModuleID,
    val `sttp-core`: ModuleID,
    val zhttp: ModuleID
) extends Product with Serializable
object ZioAlias {
  def interop(version: String) = "dev.zio" %% "zio-interop-cats" % version
  def sttp(name: String) = "com.softwaremill.sttp.client3" %% s"async-http-client-backend-$name" % "3.8.0"
  def tapir(name: String) = "com.softwaremill.sttp.tapir" %% s"tapir-$name-http-server" % "1.1.0"

  val `sttp-core` = "com.softwaremill.sttp.model" %% "core" % "1.5.2"

  def zhttp(version: String) =  "io.d11" %% "zhttp" % version

  case object zio1 extends ZioAlias(
    "1.0.16",
    interop("13.0.0.1"),
    sttp("zio1"),
    tapir("zio1"),
    `sttp-core`,
    zhttp("1.0.0.0-RC29")
  )
  case object zio2 extends ZioAlias(
    "2.0.2",
    interop("3.3.0"),
    sttp("zio"),
    tapir("zio"),
    `sttp-core`,
    zhttp("2.0.0-RC10")
  )
}

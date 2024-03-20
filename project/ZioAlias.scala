import sbt.librarymanagement.ModuleID
import sbt._

sealed abstract class ZioAlias(
    val verzion: String,
    val interop: ModuleID,
    val sttp: ModuleID,
    val tapir: ModuleID,
) extends Product
    with Serializable
object ZioAlias {
  def interop(version: String) = "dev.zio"                       %% "zio-interop-cats"                 % version
  def sttp(name: String)       = "com.softwaremill.sttp.client3" %% s"async-http-client-backend-$name" % "3.9.5"
  def tapir(name: String)      = "com.softwaremill.sttp.tapir"   %% s"tapir-$name-http-server"         % "1.0.0"

  case object zio1
      extends ZioAlias(
        "1.0.15",
        interop("3.2.9.1"),
        sttp("zio1"),
        tapir("zio1"),
      )
  case object zio2
      extends ZioAlias(
        "2.0.0-RC6",
        interop("3.3.0-RC7"),
        sttp("zio"),
        tapir("zio"),
      )
}

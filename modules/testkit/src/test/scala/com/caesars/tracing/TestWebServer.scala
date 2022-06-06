package com.caesars.tracing

import zhttp.http.*
import zio.*
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir.*
import sttp.client3.asynchttpclient.zio.SttpClient.{Service as ZIOSttpClient}
import zhttp.service.Server
import zhttp.service.EventLoopGroup
import zhttp.service.server.ServerChannelFactory
import com.caesars.tracing.TracedBackend.TracedBackendConfig
import sttp.client3.asynchttpclient.zio.AsyncHttpClientZioBackend
import io.janstenpickle.trace4cats.`export`.RefSpanCompleter
import io.janstenpickle.trace4cats.model.TraceProcess
import io.janstenpickle.trace4cats.kernel.SpanCompleter
import io.janstenpickle.trace4cats.kernel.SpanSampler
import io.janstenpickle.trace4cats.model.CompletedSpan
import zio.random.Random

object TestWebServer {

  case class Port(value: Int) extends AnyVal {
    override def toString(): String = value.toString
  }

  val randomPort: ULayer[Has[Port]] =
    Random.live >>> ZIO.service[Random.Service].flatMap(_.nextIntBetween(1024, 65535)).map(Port(_)).toLayer

  private def server(client: ZIOSttpClient, port: Port) = (
    Http.collectZIO[Request] {
      case Method.GET -> !! / "success" => ZIO.succeed(Response.ok)
      case Method.GET -> !! / "failure" => ZIO.fail(new Throwable("boom"))
      case Method.GET -> !! / "rebound" =>
        import sttp.client3.basicRequest
        import sttp.client3.UriContext
        basicRequest.get(uri"http://localhost:$port/success")
        .send(client).as(Response.ok)
    } ++ Http.notFound
  ).catchAll(e => Http.error(e.getMessage))

  def trace[A](
      f: (Port, ZIOSttpClient) => Task[A]
  ): RIO[Has[Port] & Has[ZIOSttpClient] & Has[CompletedSpanQueue], (List[CompletedSpan], A)] = {
    for {
      port   <- ZIO.service[Port]
      client <- ZIO.service[ZIOSttpClient]
      result <- f(port, client)
      spans  <- ZIO.service[CompletedSpanQueue].flatMap(_.ref.get)
    } yield (spans.toList, result)
  }

  val mkClient: ULayer[Has[ZIOSttpClient]] =
    AsyncHttpClientZioBackend.layer().orDie

  private val mkZTracer = {
    import zio.interop.catz.*

    val sampler = ZLayer.succeed(SpanSampler.always[Task])
    val completer: URLayer[Has[CompletedSpanQueue], Has[SpanCompleter[Task]]] =
      ZIO
        .service[CompletedSpanQueue]
        .map(cq => new RefSpanCompleter(TraceProcess("ZTracerImplementationSpec"), cq.ref))
        .toLayer

    ((completer ++ sampler) >>> TracingUtils.entryPointLayer) >>> ZTracer.layer
  }

  val mkTracedClient: URLayer[Has[CompletedSpanQueue] & Has[ZIOSttpClient], Has[ZIOSttpClient]] =
    (ZLayer.service[ZIOSttpClient] ++ mkZTracer ++ ZLayer.succeed(TracedBackendConfig())) >>> TracedBackend.layer

  private val serverRequirements =
    ZLayer.service[ZIOSttpClient] ++ ZLayer.service[Port] ++ EventLoopGroup.auto() ++ ServerChannelFactory.auto

  val mkTracedServer = {
    val startServer = (
      for {
        port   <- ZManaged.service[Port]
        tracer <- ZManaged.service[ZTracer]
        client <- ZManaged.service[ZIOSttpClient]
        _      <- (Server.app(TracedHttp(tracer)(server(client, port))) ++ Server.port(port.value)).make
      } yield ()
    ).toLayer

    ((mkZTracer ++ serverRequirements) >>> startServer).orDie
  }

  val mkServer = {
    val startServer = (
      for {
        port <- ZManaged.service[Port]
        client <- ZManaged.service[ZIOSttpClient]
        _    <- (Server.app(server(client, port)) ++ Server.port(port.value)).make
      } yield ()
    ).toLayer

    (serverRequirements >>> startServer).orDie
  }
}

package com.caesars.tracing

import cats.effect.kernel.Ref
import io.janstenpickle.trace4cats.`export`.RefSpanCompleter
import io.janstenpickle.trace4cats.inject.EntryPoint
import io.janstenpickle.trace4cats.kernel.{SpanCompleter, SpanSampler}
import io.janstenpickle.trace4cats.model.{CompletedSpan, SpanKind, TraceHeaders, TraceProcess}
import sttp.client3
import sttp.client3.impl.zio.RIOMonadAsyncError
import sttp.client3.{Identity, RequestT, SttpBackend, UriContext, basicRequest, Response as SttpResponse}
import sttp.client3.testing.SttpBackendStub
import sttp.model.RequestMetadata
import zhttp.http.*
import zhttp.service.Server
import zio.interop.catz.*
import zio.interop.catz.implicits.rts
import zio.{Has, Task, UIO, ULayer, URLayer, ZIO, ZLayer}

import scala.collection.immutable.Queue

object TracingTestUtils {

  val refSpanCompleter: ULayer[Has[RefSpanCompleter[Task]]] =
    RefSpanCompleter[Task]("my-service").toLayer.orDie

  def entryPointRef: ZLayer[Has[RefSpanCompleter[Task]], Nothing, Has[EntryPoint[Task]]] =
    ZIO.service[RefSpanCompleter[Task]].map(EntryPoint(sampler, _)).toLayer

  val sampler: SpanSampler[Task] = SpanSampler.always[Task]

  val ref: ULayer[Has[Ref[Task, Queue[CompletedSpan]]]] =
    cats.effect.Ref.of[Task, Queue[CompletedSpan]](Queue.empty).toLayer.orDie

  val completer: URLayer[Has[Ref[Task, Queue[CompletedSpan]]], Has[SpanCompleter[Task]]] = ZIO
    .service[cats.effect.Ref[Task, Queue[CompletedSpan]]]
    .map(new RefSpanCompleter[Task](TraceProcess("ZTracerImplementationSpec"), _))
    .toLayer

  val successBackend: SttpBackendStub[Task, Nothing] =
    SttpBackendStub(new RIOMonadAsyncError[Any]).whenRequestMatchesPartial { req =>
      SttpResponse.ok(()).copy(request = RequestMetadata(req.method, req.uri, req.headers))
    }

  val failBackend: SttpBackendStub[Task, Nothing] =
    SttpBackendStub(new RIOMonadAsyncError[Any]).whenAnyRequest.thenRespondServerError()
}

object ZTracerSpecUtils {
  def spanSpec(
      name: String = "root",
      kind: SpanKind = SpanKind.Internal
  )(
      f: ZSpan => UIO[Unit],
      s: RefSpanCompleter[Task] => Task[CompletedSpan]
  ): ZIO[Has[ZTracer] with Has[RefSpanCompleter[Task]], Throwable, CompletedSpan] =
    for {
      (tracer, ctr) <- ZIO.services[ZTracer, RefSpanCompleter[Task]]
      _             <- tracer.span(name, kind)(f(_))
      span          <- s(ctr)
    } yield span

  def spanFromHeadersSpec(
      s: RefSpanCompleter[Task] => Task[CompletedSpan]
  ): ZIO[Has[ZTracer] & Has[RefSpanCompleter[Task]], Throwable, CompletedSpan] =
    for {
      (tracer, ctr) <- ZIO.services[ZTracer, RefSpanCompleter[Task]]
      _             <- tracer.fromHeaders(TraceHeaders.empty, SpanKind.Internal, "mySpan")(_ => ZIO.unit)
      span          <- s(ctr)
    } yield span
}

object ZTracerImplementationSpecUtils {
  def implementationSpec(
      be: SttpBackend[Task, ZioSttpCapabilities],
      requestMap: RequestT[Identity, Either[String, String], Any] => RequestT[Identity, Either[String, String], Any] = identity
  ) = {
    val req: client3.Request[Either[String, String], Any] = basicRequest.get(uri"http://www.google.com/")

    for {
      tracer <- ZIO.service[ZTracer]
      sbe = TracedBackend(be, tracer)
      res   <- requestMap(req).send(sbe)
      ref   <- ZIO.service[cats.effect.Ref[Task, Queue[CompletedSpan]]]
      spans <- ref.get
      span = spans.head
    } yield (spans, span, res)
  }

  val singleAppImplementationSpec = {
    val app =
      Http.collectZIO[Request] { case Method.GET -> !! / "foo" =>
        ZIO.succeed(Response.ok)
      }

    for {
      tracedApp1 <- TracedHttp.layer()(app)
      _          <- tracedApp1(Request(Method.GET, URL(Path("/foo"))))
      ref        <- ZIO.service[cats.effect.Ref[Task, Queue[CompletedSpan]]]
      spans      <- ref.get
      span = spans.head
    } yield (spans, span)
  }

  val failingSingleAppImplementationSpec = {
    val app =
      Http.collectZIO[Request] { case Method.GET -> !! / "foo" =>
        ZIO.fail(new Throwable("boom"))
      }

    val recover: Throwable => HttpApp[Any, Nothing] =
      e => Http.error(e.getMessage)

    for {
      tracedApp <- TracedHttp.layer()(app.catchAll(recover))
      _         <- tracedApp(Request(Method.GET, URL(Path("/foo")))).either
      ref       <- ZIO.service[cats.effect.Ref[Task, Queue[CompletedSpan]]]
      spans     <- ref.get
      span = spans.head
    } yield span
  }

  val notFoundSingleAppImplementationSpec = {
    val app =
      Http.collectZIO[Request] { case Method.GET -> !! / "bar" =>
        ZIO.fail(new Throwable("boom"))
      }

    for {
      tracedApp <- TracedHttp.layer()(app ++ Http.notFound)
      _         <- tracedApp(Request(Method.GET, URL(Path("/foo")))).either
      ref       <- ZIO.service[cats.effect.Ref[Task, Queue[CompletedSpan]]]
      spans     <- ref.get
      span = spans.head
    } yield span
  }

  val singleTapirAppSpec = {
    val tapirApp = {
      import sttp.tapir.server.ziohttp.ZioHttpInterpreter
      import sttp.tapir.ztapir.*

      val ep = endpoint.get
        .in("foo")
        .errorOut(stringBody)
        .zServerLogic(_ => ZIO.fail("boom"))

      ZioHttpInterpreter().toHttp(ep)
    }

    for {
      tracedApp <- TracedHttp.layer()(tapirApp)
      _         <- tracedApp(Request(Method.GET, URL(Path("/foo")))).either
      ref       <- ZIO.service[cats.effect.Ref[Task, Queue[CompletedSpan]]]
      spans     <- ref.get
      span = spans.head
    } yield span
  }

  val multiAppImplementationSpec = {
    val app1 =
      Http.collectZIO[Request] { case Method.GET -> !! / "foo" =>
        ZIO.service[SttpBackend[Task, ZioSttpCapabilities]].flatMap { client =>
          basicRequest
            .get(uri"http://localhost:9090/bar")
            .send(client)
            .as(Response.ok)
        }
      }

    val app2 =
      Http.collect[Request] { case Method.GET -> !! / "bar" =>
        Response.ok
      }

    for {
      tracedApp <- TracedHttp.layer()(app1 ++ app2)
      _ <- (Server.app(tracedApp) ++ Server.port(9090)).make.use(_ =>
        ZIO
          .service[SttpBackend[Task, ZioSttpCapabilities]]
          .flatMap(backend => basicRequest.get(uri"http://localhost:9090/foo").send(backend))
      )
      ref    <- ZIO.service[cats.effect.Ref[Task, Queue[CompletedSpan]]]
      result <- ref.get
      root = result.last
    } yield (result, root)
  }
}

package com.caesars.tracing

import cats.effect.{Ref as CERef}
import com.caesars.tracing.sttp.{HttpClient, TracedBackend}
import com.caesars.tracing.testing.SpanRecorder
import io.janstenpickle.trace4cats.`export`.RefSpanCompleter
import io.janstenpickle.trace4cats.kernel.{SpanCompleter, SpanSampler}
import io.janstenpickle.trace4cats.model.{CompletedSpan, SpanKind, TraceHeaders, TraceProcess}
import scala.collection.immutable.Queue
import _root_.sttp.client3.{Identity, RequestT, UriContext, basicRequest, Request as SttpRequest, Response as SttpResponse}
import _root_.sttp.client3.impl.zio.RIOMonadAsyncError
import _root_.sttp.client3.testing.SttpBackendStub
import _root_.sttp.model.RequestMetadata
import zhttp.http.*
import zhttp.service.Server
import zio.*
import zio.interop.catz.*
import zio.interop.catz.implicits.rts

object TracingTestUtils {
  val sampler: SpanSampler[Task] = SpanSampler.always[Task]

  val entryPointRef: URLayer[SpanCompleter[Task], ZEntryPoint] =
    ZLayer.service[SpanCompleter[Task]] ++ ZLayer.succeed(sampler) >>>
      ZEntryPoint.layer

  val spanRecorderLayer: ULayer[SpanRecorder] =
    ZLayer.fromZIO(
      CERef.of[Task, Queue[CompletedSpan]](Queue.empty).orDie
    )

  val completer: URLayer[SpanRecorder, SpanCompleter[Task]] =
    ZLayer.fromZIO(
      ZIO.serviceWith {
        new RefSpanCompleter[Task](TraceProcess("ZTracerImplementationSpec"), _)
      }
    )
}

object ZTracerSpecUtils {
  def spanSpec(
      name: String = "root",
      kind: SpanKind = SpanKind.Internal
  )(
      f: ZSpan => UIO[Unit],
      s: RefSpanCompleter[Task] => Task[CompletedSpan]
  ): RIO[ZTracer & RefSpanCompleter[Task], CompletedSpan] =
    for {
      tracer <- ZIO.service[ZTracer]
      ctr    <- ZIO.service[RefSpanCompleter[Task]]
      _      <- tracer.span(name, kind)(f(_))
      span   <- s(ctr)
    } yield span

  def spanFromHeadersSpec(
      s: RefSpanCompleter[Task] => Task[CompletedSpan]
  ): ZIO[ZTracer & RefSpanCompleter[Task], Throwable, CompletedSpan] =
    for {
      tracer <- ZIO.service[ZTracer]
      ctr    <- ZIO.service[RefSpanCompleter[Task]]
      _      <- tracer.fromHeaders(TraceHeaders.empty, SpanKind.Internal, "mySpan")(_ => ZIO.unit)
      span   <- s(ctr)
    } yield span
}

object ZTracerImplementationSpecUtils {
  val successBackend: HttpClient =
    SttpBackendStub(new RIOMonadAsyncError[Any]).whenRequestMatchesPartial { req =>
      SttpResponse.ok(()).copy(request = RequestMetadata(req.method, req.uri, req.headers))
    }

  val failBackend: HttpClient =
    SttpBackendStub(new RIOMonadAsyncError[Any]).whenAnyRequest.thenRespondServerError()

  def implementationSpec(be: HttpClient): RIO[
    ZTracer & SpanRecorder,
    (Queue[CompletedSpan], CompletedSpan, SttpResponse[Either[String, String]])
  ] = implementationSpec(be, identity)

  def implementationSpec(
      be: HttpClient,
      requestMap: RequestT[Identity, Either[String, String], Any] => RequestT[Identity, Either[String, String], Any]
  ): RIO[
    ZTracer & SpanRecorder,
    (Queue[CompletedSpan], CompletedSpan, SttpResponse[Either[String, String]])
  ] = {
    val req: SttpRequest[Either[String, String], Any] = basicRequest.get(uri"http://www.google.com/")

    for {
      tracer <- ZIO.service[ZTracer]
      sbe = TracedBackend(be, tracer)
      res   <- requestMap(req).send(sbe)
      ref   <- ZIO.service[SpanRecorder]
      spans <- ref.get
      span = spans.head
    } yield (spans, span, res)
  }

  val singleAppImplementationSpec: RIO[ZTracer & SpanRecorder, (Queue[CompletedSpan], CompletedSpan)] = {
    val app =
      Http.collectZIO[Request] { case Method.GET -> !! / "foo" =>
        ZIO.succeed(Response.ok)
      }

    for {
      tracedApp1 <- TracedHttp.layer()(app)
      _          <- tracedApp1(Request(method = Method.GET, url = URL(path = Path(Vector("foo"), trailingSlash = false)))).orDieWith(_.get)
      ref        <- ZIO.service[cats.effect.Ref[Task, Queue[CompletedSpan]]]
      spans      <- ref.get
      span = spans.head
    } yield (spans, span)
  }

  val failingSingleAppImplementationSpec: ZIO[ZTracer & SpanRecorder, Throwable, CompletedSpan] = {
    val app =
      Http.collectZIO[Request] { case Method.GET -> !! / "foo" =>
        ZIO.fail(new Throwable("boom"))
      }

    val recover: Throwable => HttpApp[Any, Nothing] =
      e => Http.error(e.getMessage)

    for {
      tracedApp <- TracedHttp.layer()(app.catchAll(recover))
      _         <- tracedApp(Request(method = Method.GET, url = URL(path = Path(Vector("foo"), trailingSlash = false)))).either
      ref       <- ZIO.service[cats.effect.Ref[Task, Queue[CompletedSpan]]]
      spans     <- ref.get
      span = spans.head
    } yield span
  }

  val notFoundSingleAppImplementationSpec: ZIO[ZTracer & SpanRecorder, Throwable, CompletedSpan] = {
    val app =
      Http.collectZIO[Request] { case Method.GET -> !! / "bar" =>
        ZIO.fail(new Throwable("boom"))
      }

    for {
      tracedApp <- TracedHttp.layer()(app ++ Http.notFound)
      _         <- tracedApp(Request(method = Method.GET, url = URL(path = Path(Vector("foo"), trailingSlash = false)))).either
      ref       <- ZIO.service[cats.effect.Ref[Task, Queue[CompletedSpan]]]
      spans     <- ref.get
      span = spans.head
    } yield span
  }

  val singleTapirAppSpec: RIO[ZTracer & SpanRecorder, CompletedSpan] = {
    val tapirApp = {
      import _root_.sttp.tapir.server.ziohttp.ZioHttpInterpreter
      import _root_.sttp.tapir.ztapir.*

      val ep = endpoint.get
        .in("foo")
        .errorOut(stringBody)
        .zServerLogic[ZTracer & SpanRecorder](_ => ZIO.fail("boom"))

      ZioHttpInterpreter().toHttp(ep)
    }

    for {
      tracedApp <- TracedHttp.layer()(tapirApp)
      _         <- tracedApp(Request(method = Method.GET, url = URL(path = Path(Vector("foo"), trailingSlash = false)))).either
      ref       <- ZIO.service[SpanRecorder]
      spans     <- ref.get
      span = spans.head
    } yield span
  }

  import zhttp.service.{EventLoopGroup, ServerChannelFactory}
  val multiAppImplementationSpec: RIO[
    ServerChannelFactory & Scope & HttpClient & ZTracer & EventLoopGroup & SpanRecorder,
    (Queue[CompletedSpan], CompletedSpan)
  ] = {
    val app1 =
      Http.collectZIO[Request] { case Method.GET -> !! / "foo" =>
        ZIO.serviceWithZIO[HttpClient] { client =>
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
      _         <- Server.make(Server.app(tracedApp) ++ Server.port(9090))
      _ <- ZIO.serviceWithZIO[HttpClient] {
        basicRequest
          .get(uri"http://localhost:9090/foo")
          .send(_)
      }
      result <- ZIO.serviceWithZIO[cats.effect.Ref[Task, Queue[CompletedSpan]]] { _.get }
      root = result.last
    } yield (result, root)
  }
}

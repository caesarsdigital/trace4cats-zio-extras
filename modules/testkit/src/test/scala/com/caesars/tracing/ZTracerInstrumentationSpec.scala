package com.caesars.tracing

import com.caesars.tracing.TracingUtils.entryPointLayer
import sttp.client3.asynchttpclient.zio.AsyncHttpClientZioBackend
import zhttp.service.EventLoopGroup
import zhttp.service.server.ServerChannelFactory
import zio.*
import zio.test.{DefaultRunnableSpec, assertTrue, assert, assertM}
import zio.test.Assertion.*
import io.janstenpickle.trace4cats.kernel.SpanCompleter

import sttp.client3.UriContext
import sttp.client3.basicRequest
import io.janstenpickle.trace4cats.model.CompletedSpan
import io.janstenpickle.trace4cats.Span
import scala.compiletime.ops.any
import sttp.model.StatusCode
import io.janstenpickle.trace4cats.ToHeaders
import org.typelevel.ci.CIString
import sttp.model.Header
import sttp.client3.Response
import io.janstenpickle.trace4cats.model.SpanKind
import io.janstenpickle.trace4cats.model.SpanStatus
import com.caesars.tracing.TracedBackend.TracedBackendConfig
import io.janstenpickle.trace4cats.model.Parent
import scala.io.Source
import zio.test.TestAspect.*
import zio.test.ZSpec

object ZTracerInstrumentationSpec extends DefaultRunnableSpec {

  import TestWebServer.*

  import com.caesars.tracing.Assertion.*
  import TracingAssertion.*
  import sttp.client3.asynchttpclient.zio.SttpClient.{Service as ZIOSttpClient}

  type TestEnvironment = Has[Port] & Has[CompletedSpanQueue] & Has[ZIOSttpClient]

  override def spec: ZSpec[zio.test.environment.TestEnvironment, Failure] =
    suite("Ztracer Instrumentation for zio-http and Sttp Client")(
      zioHttpServerInstrumentation,
      tracedBackendTestSuite
    )

  val tracedBackendTestSuite = (
    suite("Traced Backend test suite")(
      testM("creates a span for a successful request") {
        val assertSpan = hasFirst(hasName("GET success") && hasNoParent && hasHostname("localhost"))
        val expectations = assertSpan <*> isOk
        assertM(success())(expectations)
      },
      testM("preserves original headers") {
        val expectations = anything <*> containsHeader("foo", "bar")
        assertM(success(Header("foo", "bar")))(expectations)
      },
      testM("creates a span for a failed request") {
        val assertSpan = hasName("GET failure") && hasNoParent && hasHostname("localhost")
        val expectations = (hasFirst(assertSpan) && hasLength(1)) <*> isNotOk
        assertM(failure)(expectations).provideCustomLayer(tracedClientEnv(faultyCLient))
      },
      testM("forwards correlation headers") {
        assertM(success())(
          fromFunction("spans headers should be a subset of request headers") { case (spans, response) =>
            val headers = response.request.headers.map(h => CIString(h.name) -> h.value).toSet
            val traceHeaders = ToHeaders.standard.fromContext(spans.last.context).values
            hasSubset(traceHeaders)(headers)
          }
        )
      }
    ) @@ before(clearSpanQueue) @@ sequential
  ).provideCustomLayerShared(tracedClientEnv())

  val zioHttpServerInstrumentation = (
    suite("zio-http server instrumentation")(
      testM("creates a span for a successful request") {
        val assertSpan = hasKind(SpanKind.Server) && hasNoParent && hasName("GET /success")
        assertM(success())(((hasLength(1) && hasFirst(assertSpan)) <*> isOk))
      },
      testM("creates a span for a failed request") {
        val assertSpan = hasStatusCode(500) && hasStatus(SpanStatus.Internal("Internal Server Error"))
        assertM(failure)(((hasLength(1) && hasFirst(assertSpan)) <*> isNotOk))
      },
      testM("creates a span for an unmatched request") {
        val assertSpan = hasStatusCode(404) && hasStatus(SpanStatus.NotFound)
        assertM(notFound)(((hasLength(1) && hasFirst(assertSpan)) <*> isNotOk))
      }
    ) @@ before(clearSpanQueue) @@ sequential
  ).provideCustomLayerShared(tracedServerEnv())

  def success(headers: Header*): RIO[TestEnvironment, (List[CompletedSpan], Response[Either[String, String]])] =
    trace((port, client) => basicRequest.get(uri"http://localhost:$port/success").headers(headers: _*).send(client))

  val failure: RIO[TestEnvironment, (List[CompletedSpan], Response[Either[String, String]])] =
    trace((port, client) => basicRequest.get(uri"http://localhost:$port/failure").send(client))

  val notFound: RIO[TestEnvironment, (List[CompletedSpan], Response[Either[String, String]])] =
    trace((port, client) => basicRequest.get(uri"http://localhost:$port/notFound").send(client))

  val rebound: RIO[TestEnvironment, (List[CompletedSpan], Response[Either[String, String]])] =
    trace((port, client) => basicRequest.get(uri"http://localhost:$port/rebound").send(client))

  val faultyCLient = {
    import sttp.client3.testing.SttpBackendStub
    import sttp.client3.impl.zio.RIOMonadAsyncError
    ZLayer.succeed {
      SttpBackendStub(new RIOMonadAsyncError[Any]).whenAnyRequest.thenRespondServerError()
    }
  }

  def clearSpanQueue =
    ZIO.environment[TestEnvironment].flatMap(env => env.get[CompletedSpanQueue].ref.getAndUpdate(_.empty))

  def tracedClientEnv[A <: Has[_]: Tag](layer: ULayer[A] = ZLayer.succeed(())): ULayer[TestEnvironment] = {
    val client: ULayer[Has[ZIOSttpClient]] = mkClient ++ layer
    val tracedClient = (ZLayer.service[CompletedSpanQueue] ++ client) >>> mkTracedClient
    CompletedSpanQueue.mk >+> tracedClient >+> randomPort >+> mkServer
  }

  def tracedServerEnv[A <: Has[_]: Tag](layer: ULayer[A] = ZLayer.succeed(())): ULayer[TestEnvironment] = {
    val client: ULayer[Has[ZIOSttpClient]] = mkClient ++ layer
    (client ++ (CompletedSpanQueue.mk >+> randomPort)) >+> mkTracedServer
  }
}

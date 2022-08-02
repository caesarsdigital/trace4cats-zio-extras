package com.caesars.tracing

import com.caesars.tracing.sttp.{HttpClient, TracedBackend}
import com.caesars.tracing.testing.*
import trace4cats.ToHeaders
import trace4cats.modeltrace4cats.model.{SpanKind, SpanStatus}
import io.netty.channel.{EventLoopGroup as NEventLoopGroup}
import org.typelevel.ci.CIString
import _root_.sttp.client3.asynchttpclient.zio.AsyncHttpClientZioBackend
import zhttp.service.server.ServerChannelFactory
import zhttp.service.{EventLoopGroup, ServerChannelFactory as ServerChannelFactoryService}
import zio.*
import zio.test.*
import zio.test.TestEnvironment

object SttpClientInstrumentationSpec extends ZIOSpecDefault {
  import ZTracerImplementationSpecUtils.{failBackend, implementationSpec, successBackend}

  val testLayer: ULayer[ZTracer & SpanRecorder] =
    ZLayer.make[ZTracer & SpanRecorder](
      ZLayer.succeed(TracingTestUtils.sampler),
      TracingTestUtils.spanRecorderLayer,
      TracingTestUtils.completer,
      ZEntryPoint.layer,
      ZTracer.layer,
    )

  override val spec =
    suite("Sttp client instrumentation")(
      test("creates a span for a successful request") {
        implementationSpec(successBackend).map { case (spans, span, res) =>
          assertTrue(
            res.code.isSuccess,
            spans.length == 1,
            span.context.parent.isEmpty,
            span.name == "GET ",
            span.attributes.get("remote.service.hostname").map(_.value.value).contains("www.google.com")
          )
        }
      },
      test("forwards correlation headers") {
        implementationSpec(successBackend).map { case (spans, _, res) =>
          val traceHeaders = ToHeaders.standard.fromContext(spans.last.context).values
          assertTrue(
            traceHeaders.toSet.subsetOf(
              res.request.headers.map(h => CIString(h.name) -> h.value).toSet
            )
          )
        }
      },
      test("preserves original headers") {
        implementationSpec(successBackend, _.header("foo", "bar")).map { case (_, _, res) =>
          assertTrue(
            res.request.headers("foo").contains("bar")
          )
        }
      },
      test("creates a span for a failed request") {
        implementationSpec(failBackend).map { case (spans, span, res) =>
          assertTrue(
            !res.code.isSuccess,
            spans.length == 1,
            span.context.parent.isEmpty,
            span.name == "GET ",
            span.attributes.get("remote.service.hostname").map(_.value.value).contains("www.google.com")
          )
        }
      }
    ).provideSomeLayer(testLayer)
}

object ZioHttpInstrumentationSpec extends ZIOSpecDefault {
  import ZTracerImplementationSpecUtils.*

  val testLayer: ULayer[ZTracer & SpanRecorder] =
    ZLayer.make[ZTracer & SpanRecorder](
      ZLayer.succeed(TracingTestUtils.sampler),
      TracingTestUtils.spanRecorderLayer,
      TracingTestUtils.completer,
      ZEntryPoint.layer,
      ZTracer.layer,
    )

  override val spec = suite("zio-http server instrumentation")(
    test("creates a span for a successful request") {
      singleAppImplementationSpec.map { case (spans, span) =>
        assertTrue(
          spans.length == 1,
          span.kind == SpanKind.Server,
          span.context.parent.isEmpty,
          span.name == "GET /foo"
        )
      }
    },
    test("creates a span for a failed request") {
      failingSingleAppImplementationSpec.map { span =>
        assertTrue(
          span.status == SpanStatus.Internal("Internal Server Error"),
          span.attributes.contains("status.code", 500)
        )
      }
    },
    test("creates a span for an unmatched request") {
      notFoundSingleAppImplementationSpec.map { span =>
        assertTrue(
          span.status == SpanStatus.NotFound,
          span.attributes.contains("status.code", 404)
        )
      }
    },
    test("creates a span for an http app generated from Tapir") {
      singleTapirAppSpec.map { span =>
        assertTrue(
          span.status == SpanStatus.Internal("Bad Request"),
          span.attributes.contains("status.code", 400)
        )
      }
    },
  )
    .provideSomeLayer(testLayer)
}
object ZTracerInstrumentationSpec extends ZIOSpecDefault {
  import ZTracerImplementationSpecUtils.*

  val tracedBackend: URLayer[ZTracer, HttpClient] =
    ZLayer.service[ZTracer] ++ ZLayer.succeed(TracedBackend.Config()) ++ AsyncHttpClientZioBackend.layer().orDie >>>
      TracedBackend.layer

  val testLayer =
    ZLayer.make[
      ServerChannelFactoryService & HttpClient & ZTracer & NEventLoopGroup & SpanRecorder
    ](
      ZLayer.succeed(TracingTestUtils.sampler),
      TracingTestUtils.spanRecorderLayer,
      TracingTestUtils.completer,
      ZEntryPoint.layer,
      ZTracer.layer,
      tracedBackend,
      EventLoopGroup.auto(),
      ServerChannelFactory.auto
    )

  override val spec = suite("Ztracer Instrumentation for zio-http and Sttp Client")(
    test("A client -> server -> client -> server interaction is recorded correctly") {
      multiAppImplementationSpec.map { case (result, root) =>
        assertTrue(
          // only one root
          result.count(_.context.parent.isEmpty) == 1,
          result.map(span => (span.name, span.kind)).toList ==
            List(
              "GET /bar" -> SpanKind.Server,
              "GET bar" -> SpanKind.Client,
              "GET /foo" -> SpanKind.Server,
              "GET foo" -> SpanKind.Client
            ),
          root.name == "GET foo",
          root.context.parent.isEmpty,
          result.forall(_.status.isOk)
        )
      }
    }
  )
    .provideSomeLayer[TestEnvironment & Scope](testLayer)
}

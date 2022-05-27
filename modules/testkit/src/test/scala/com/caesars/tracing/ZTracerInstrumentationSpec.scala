package com.caesars.tracing

import com.caesars.tracing.TracingUtils.entryPointLayer
import com.caesars.tracing.testing.*
import io.janstenpickle.trace4cats.ToHeaders
import io.janstenpickle.trace4cats.model.{SpanKind, SpanStatus}
import org.typelevel.ci.CIString
import sttp.client3.asynchttpclient.zio.AsyncHttpClientZioBackend
import sttp.model.Header
import zhttp.service.EventLoopGroup
import zhttp.service.server.ServerChannelFactory
import zio.*
import zio.test.*

object ZTracerInstrumentationSpec extends DefaultRunnableSpec {
  import TracingTestUtils.*
  import ZTracerImplementationSpecUtils.*

  override def spec = suite("Ztracer Instrumentation for zio-http and Sttp Client")(
    suite("Sttp client instrumentation")(
      testM("creates a span for a successful request") {
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
      testM("forwards correlation headers") {
        implementationSpec(successBackend).map { case (spans, _, res) =>
          val traceHeaders = ToHeaders.standard.fromContext(spans.last.context).values
          assertTrue(
            traceHeaders.toSet.subsetOf(
              res.request.headers.map(h => CIString(h.name) -> h.value).toSet
            )
          )
        }
      },
      testM("preserves original headers") {
        implementationSpec(successBackend, _.header("foo", "bar")).map { case (_, _, res) =>
          assertTrue(
            res.request.headers.contains(Header("foo", "bar"))
          )
        }
      },
      testM("creates a span for a failed request") {
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
    ),
    suite("zio-http server instrumentation")(
      testM("creates a span for a successful request") {
        singleAppImplementationSpec.map { case (spans, span) =>
          assertTrue(
            spans.length == 1,
            span.kind == SpanKind.Server,
            span.context.parent.isEmpty,
            span.name == "GET /foo"
          )
        }
      },
      testM("creates a span for a failed request") {
        failingSingleAppImplementationSpec.map { span =>
          assertTrue(
            span.status == SpanStatus.Internal("Internal Server Error"),
            span.attributes.contains("status.code", 500)
          )
        }
      },
      testM("creates a span for an unmatched request") {
        notFoundSingleAppImplementationSpec.map { span =>
          assertTrue(
            span.status == SpanStatus.NotFound,
            span.attributes.contains("status.code", 404)
          )
        }
      },
      testM("creates a span for an http app generated from Tapir") {
        singleTapirAppSpec.map { span =>
          assertTrue(
            span.status == SpanStatus.Internal("Bad Request"),
            span.attributes.contains("status.code", 400)
          )
        }
      }
    ),
    testM("A client -> server -> client -> server interaction is recorded correctly") {
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
  ).provideCustomLayer(yy) /*.inject(
    ZLayer.succeed(sampler),
    ref,
    completer,
    entryPointLayer,
    ZTracer.layer,
    ZLayer.succeed(TracedBackendConfig()),
    ZLayer.identity[Has[ZTracer]] ++ AsyncHttpClientZioBackend.layer().orDie ++ ZLayer
      .identity[Has[TracedBackendConfig]] >>> TracedBackend.layer,
    EventLoopGroup.auto(),
    ServerChannelFactory.auto
  )*/


  val xx = ref >+> (((ZLayer.succeed(sampler) ++ completer) >>> entryPointLayer) >>> ZTracer.layer)

  val yy = EventLoopGroup.auto() ++ ServerChannelFactory.auto ++ AsyncHttpClientZioBackend.layer().orDie ++ xx
}

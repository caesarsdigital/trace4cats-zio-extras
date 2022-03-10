package com.caesars.tracing

import com.caesars.tracing.testing.*
import io.janstenpickle.trace4cats.`export`.RefSpanCompleter
import io.janstenpickle.trace4cats.model.AttributeValue.StringValue
import io.janstenpickle.trace4cats.model.{SpanContext, SpanKind, SpanStatus}
import zio.*
import zio.magic.*
import zio.test.*
import zio.test.environment.TestEnvironment

object ZTracerSpec extends DefaultRunnableSpec {
  import TracingTestUtils.*
  import ZTracerSpecUtils.*

  override def spec: ZSpec[TestEnvironment, Any] = {
    suite("ZTracer")(
      testM("starts out with no span") {
        ZIO.service[ZTracer].flatMap(_.context).map(context => assertTrue(context == SpanContext.invalid))
      },
      testM("can create a span and add attributes to it") {
        spanSpec()(span => span.putAll(("fieldName", StringValue("fieldValue"))), completer => completer.get.map(_.head)).map {
          cs =>
            assertTrue(
              cs.attributes.contains("fieldName", "fieldValue"),
              cs.name == "root"
            )
        }
      },
      testM("can create a span and set its status") {
        spanSpec()(_.setStatus(SpanStatus.Aborted), completer => completer.get.map(_.head)).map { cs =>
          assertTrue(
            cs.status == SpanStatus.Aborted
          )
        }
      },
      testM("can continue a span from empty headers") {
        spanFromHeadersSpec(_.get.map(_.head)).map { cs =>
          assertTrue(
            cs.isRoot,
            cs.name == "mySpan"
          )
        }
      },
      testM("can simultaneously create multiple roots") {
        def app(name: String) = for {
          tracer <- ZIO.service[ZTracer]
          _      <- tracer.span(name)(_.putAll("a" -> StringValue(name)))
        } yield ()

        val appNames = 1 to 20 map (i => s"app $i") toSet

        for {
          _         <- ZIO.foreachPar_(appNames)(app)
          completer <- ZIO.service[RefSpanCompleter[Task]]
          result    <- completer.get
        } yield assertTrue(
          result.length == 20,
          result.forall(span => span.isRoot),
          result.flatMap(span => evalAttributes(span.attributes).get("a")).toSet ==
            appNames.asInstanceOf[Set[Any]]
        )
      },
      testM("can create nested spans") {
        for {
          tracer    <- ZIO.service[ZTracer]
          _         <- tracer.span("parent")(_.child("child", SpanKind.Internal).use(_ => ZIO.unit))
          completer <- ZIO.service[RefSpanCompleter[Task]]
          result    <- completer.get
          parent = result.find(s => s.name == "parent").getOrElse(throw new Exception("boom"))
          child = result.find(s => s.name == "child").getOrElse(throw new Exception("boom"))
        } yield assertTrue(
          result.length == 2,
          result.map(s => s.name).toSet == Set("parent", "child"),
          child.context.parent.map(_.spanId).contains(parent.context.spanId)
        )
      }
    ).inject(
      refSpanCompleter,
      entryPointRef,
      ZTracer.layer
    )
  }
}

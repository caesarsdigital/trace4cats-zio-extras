package com.caesars.tracing

import com.caesars.tracing.testing.*
import io.janstenpickle.trace4cats.`export`.RefSpanCompleter
import io.janstenpickle.trace4cats.inject.EntryPoint
import io.janstenpickle.trace4cats.model.{SpanContext, SpanKind, SpanStatus}
import io.janstenpickle.trace4cats.model.AttributeValue.StringValue
import zio.*
import zio.interop.catz.*
import zio.test.*

object ZTracerSpec extends ZIOSpecDefault {
  import TracingTestUtils.*
  import ZTracerSpecUtils.*

  override val spec =
    suite("ZTracer")(
      test("starts out with no span") {
        ZIO.service[ZTracer].flatMap(_.context).map(context => assertTrue(context == SpanContext.invalid))
      },
      test("can create a span and add attributes to it") {
        spanSpec()(span => span.putAll(("fieldName", StringValue("fieldValue"))), completer => completer.get.map(_.head)).map {
          cs =>
            assertTrue(
              cs.attributes.contains("fieldName", "fieldValue"),
              cs.name == "root"
            )
        }
      },
      test("can create a span and set its status") {
        spanSpec()(_.setStatus(SpanStatus.Aborted), completer => completer.get.map(_.head)).map { cs =>
          assertTrue(
            cs.status == SpanStatus.Aborted
          )
        }
      },
      test("can continue a span from empty headers") {
        spanFromHeadersSpec(_.get.map(_.head)).map { cs =>
          assertTrue(
            cs.isRoot,
            cs.name == "mySpan"
          )
        }
      },
      test("can simultaneously create multiple roots") {
        def app(name: String) = for {
          tracer <- ZIO.service[ZTracer]
          _      <- tracer.span(name)(_.putAll("a" -> StringValue(name)))
        } yield ()

        val appNames = 1 to 20 map (i => s"app $i") toSet

        for {
          _         <- ZIO.foreachPar(appNames)(app)
          completer <- ZIO.service[RefSpanCompleter[Task]]
          result    <- completer.get
        } yield assertTrue(
          result.length == 20,
          result.forall(span => span.isRoot),
          result.flatMap(span => evalAttributes(span.attributes).get("a")).toSet ==
            appNames.asInstanceOf[Set[Any]]
        )
      },
      test("can create nested spans") {
        for {
          tracer <- ZIO.service[ZTracer]
          _ <- tracer.span("parent") { span =>
            ZIO.scoped { span.child("child", SpanKind.Internal) }
          }
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
    )
      .provideLayer(
        ZLayer.make[RefSpanCompleter[Task] & EntryPoint[Task] & ZTracer](
          ZLayer.fromZIO(RefSpanCompleter[Task]("my-service").orDie),
          entryPointRef,
          ZTracer.layer
        )
      )
}

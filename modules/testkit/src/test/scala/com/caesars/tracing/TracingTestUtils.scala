package com.caesars.tracing

import io.janstenpickle.trace4cats.`export`.RefSpanCompleter
import io.janstenpickle.trace4cats.kernel.{SpanCompleter, SpanSampler}
import io.janstenpickle.trace4cats.model.{CompletedSpan, SpanKind, TraceHeaders, TraceProcess}
import zhttp.http.*
import zio.interop.catz.*
import zio.interop.catz.implicits.rts
import zio.{Has, Task, UIO, ULayer, URLayer, ZIO, ZLayer, Queue}
import io.janstenpickle.trace4cats.ToHeaders
import zio.blocking.Blocking
import zio.clock.Clock
import io.janstenpickle.trace4cats.inject.EntryPoint
import zhttp.service.Server

object TracingTestUtils {

  val refSpanCompleter: ULayer[Has[RefSpanCompleter[Task]]] =
    RefSpanCompleter[Task]("my-service").toLayer.orDie

  def entryPointRef: ZLayer[Has[RefSpanCompleter[Task]], Nothing, Has[EntryPoint[Task]]] =
    ZIO.service[RefSpanCompleter[Task]].map(EntryPoint(sampler, _)).toLayer

  val sampler: SpanSampler[Task] = SpanSampler.always[Task]
}

object ZTracerSpecUtils {
  def spanSpec(
      name: String = "root",
      kind: SpanKind = SpanKind.Internal
  )(
      f: ZSpan => UIO[Unit],
      s: RefSpanCompleter[Task] => Task[CompletedSpan]
  ): ZIO[Has[ZTracer] & Has[RefSpanCompleter[Task]], Throwable, CompletedSpan] =
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

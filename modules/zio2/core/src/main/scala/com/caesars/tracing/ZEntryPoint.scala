package com.caesars.tracing

import trace4cats.ErrorHandler
import trace4catstrace4cats.{EntryPoint, SpanName}
import trace4cats.kerneltrace4cats.kernel.{SpanCompleter, SpanSampler}
import trace4cats.model.*

import zio.*
import zio.interop.catz.*

/* NOTE that this should be an opaque type in scala 3, i.e. -
      opaque type ZEntryPoint = EntryPoint[Task]
  ...but but ZIO chokes and moans about not finding a Tag
  https://github.com/zio/zio/issues/6829
 */
class ZEntryPoint(private val underlying: EntryPoint[Task]) extends AnyVal {
  def rootSpan(
      name: String,
  ): URIO[Scope, ZSpan] =
    rootSpan(name, SpanKind.Internal)

  def rootSpan(
      name: String,
      kind: SpanKind,
  ): URIO[Scope, ZSpan] =
    rootSpan(name, kind, ErrorHandler.empty)

  def rootSpan(
      name: String,
      kind: SpanKind,
      errorHandler: ErrorHandler,
  ): URIO[Scope, ZSpan] =
    underlying
      .root(name, kind, errorHandler)
      .toScopedZIO
      .orDie
      .map(ZSpan(_))

  def continueOrElseRootSpan(name: SpanName, headers: TraceHeaders): URIO[Scope, ZSpan] =
    continueOrElseRootSpan(name, SpanKind.Server, headers)

  def continueOrElseRootSpan(name: SpanName, kind: SpanKind, headers: TraceHeaders): URIO[Scope, ZSpan] =
    continueOrElseRootSpan(name, kind, headers, ErrorHandler.empty)

  def continueOrElseRootSpan(
      name: SpanName,
      kind: SpanKind,
      headers: TraceHeaders,
      errorHandler: ErrorHandler
  ): URIO[Scope, ZSpan] =
    underlying
      .continueOrElseRoot(name, kind, headers, errorHandler)
      .toScopedZIO
      .orDie
      .map(ZSpan(_))
}
object ZEntryPoint {
  val make: URIO[SpanSampler[Task] & SpanCompleter[Task], ZEntryPoint] =
    for {
      sampler   <- ZIO.service[SpanSampler[Task]]
      completer <- ZIO.service[SpanCompleter[Task]]
    } yield new ZEntryPoint(EntryPoint[Task](sampler, completer))

  val layer: URLayer[SpanSampler[Task] & SpanCompleter[Task], ZEntryPoint] =
    ZLayer.fromZIO { ZEntryPoint.make }

  def rootSpan(
      name: String,
  ): URIO[Scope & ZEntryPoint, ZSpan] =
    rootSpan(name, SpanKind.Internal)

  def rootSpan(
      name: String,
      kind: SpanKind,
  ): URIO[Scope & ZEntryPoint, ZSpan] =
    rootSpan(name, kind, ErrorHandler.empty)

  def rootSpan(
      name: String,
      kind: SpanKind,
      errorHandler: ErrorHandler,
  ): URIO[Scope & ZEntryPoint, ZSpan] =
    ZIO.serviceWithZIO[ZEntryPoint] {
      _.rootSpan(name, kind, errorHandler)
    }

  def continueOrElseRootSpan(name: SpanName, headers: TraceHeaders): URIO[Scope & ZEntryPoint, ZSpan] =
    continueOrElseRootSpan(name, SpanKind.Server, headers)

  def continueOrElseRootSpan(name: SpanName, kind: SpanKind, headers: TraceHeaders): URIO[Scope & ZEntryPoint, ZSpan] =
    continueOrElseRootSpan(name, kind, headers, ErrorHandler.empty)

  def continueOrElseRootSpan(
      name: SpanName,
      kind: SpanKind,
      headers: TraceHeaders,
      errorHandler: ErrorHandler
  ): URIO[Scope & ZEntryPoint, ZSpan] =
    ZIO.serviceWithZIO[ZEntryPoint] {
      _.continueOrElseRootSpan(name, kind, headers, errorHandler)
    }

}

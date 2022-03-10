package com.caesars.tracing

import cats.data.NonEmptyList
import io.janstenpickle.trace4cats.inject.EntryPoint
import io.janstenpickle.trace4cats.model.*
import io.janstenpickle.trace4cats.{ErrorHandler, Span}
import zio.*
import zio.interop.catz.*

//TODO: find away to remove the option
//We have the following problem:
//In application code, we should be blissfully unaware of what spans have been created before and where we are in the structure
//Specifically, you should not need to know whether you are creating a root span or a child span
//Also, applications may have more than one root span

//What we would like, is a ZTracer that always has a span, and can perform `context`, `setStatus`, `putAll` and `span` safely
final case class ZTracer private (
    private val currentSpan: FiberRef[Option[ZSpan]],
    private val entryPoint: EntryPoint[Task]
) { self =>
  def context: UIO[SpanContext] =
    currentSpan.get.map(_.fold(SpanContext.invalid)(_.context))

  def span[R, E, A](
      name: String,
      kind: SpanKind = SpanKind.Internal,
      errorHandler: ErrorHandler = ErrorHandler.empty
  )(
      f: ZSpan => ZIO[R, E, A]
  ): ZIO[R, E, A] =
    currentSpan.get.toManaged_
      .flatMap {
        case None =>
          entryPoint
            .root(name, kind, errorHandler)
            .map(ZSpan(_))
            .toManagedZIO
            .orElse(ZSpan.noop)
        case Some(span) =>
          span.child(name, kind, errorHandler)
      }
      .use(child => currentSpan.locally(Some(child))(f(child)))

  def fromHeaders[R, E, A](
      headers: TraceHeaders,
      kind: SpanKind = SpanKind.Internal,
      name: String
  )(f: ZSpan => ZIO[R, E, A]): ZIO[R, E, A] =
    entryPoint
      .continueOrElseRoot(name, kind, headers)
      .toManagedZIO
      .map(ZSpan(_))
      .orElse(ZSpan.noop)
      .use(child => currentSpan.locally(Some(child))(f(child)))
}

object ZTracer {
  val layer: URLayer[Has[EntryPoint[Task]], Has[ZTracer]] =
    (for {
      entryPoint <- ZIO.service[EntryPoint[Task]]
      fiberRef   <- FiberRef.make(Option.empty[ZSpan])
    } yield new ZTracer(
      currentSpan = fiberRef,
      entryPoint = entryPoint
    )).toLayer
}

final class ZSpan(span: Span[Task]) {
  def context: SpanContext = span.context
  def put(key: String, value: AttributeValue): UIO[Unit] = span.put(key, value).ignore
  def putAll(fields: (String, AttributeValue)*): UIO[Unit] = span.putAll(fields*).ignore
  def putAll(fields: Map[String, AttributeValue]): UIO[Unit] = span.putAll(fields).ignore
  def setStatus(spanStatus: SpanStatus): UIO[Unit] = span.setStatus(spanStatus).ignore
  def addLink(link: Link): UIO[Unit] = span.addLink(link).ignore
  def addLinks(links: NonEmptyList[Link]): UIO[Unit] = span.addLinks(links).ignore
  def child(name: String, kind: SpanKind): UManaged[ZSpan] =
    span.child(name, kind).map(ZSpan(_)).toManagedZIO.orElse(ZSpan.noop)
  def child(name: String, kind: SpanKind, errorHandler: ErrorHandler): UManaged[ZSpan] =
    span.child(name, kind, errorHandler).map(ZSpan(_)).toManagedZIO.orElse(ZSpan.noop)
}

object ZSpan {
  def apply(span: Span[Task]): ZSpan = new ZSpan(span)
  val noop: UManaged[ZSpan] = Span.noop[Task].toManagedZIO.map(ZSpan(_)).orDie
}

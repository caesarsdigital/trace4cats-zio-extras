package com.caesars.tracing

import cats.syntax.option.*
import io.janstenpickle.trace4cats.{ErrorHandler, ToHeaders}
import io.janstenpickle.trace4cats.inject.SpanParams
import io.janstenpickle.trace4cats.model.*
import zio.*

//TODO: find a way to remove the option
//We have the following problem:
//In application code, we should be blissfully unaware of what spans have been created before and where we are in the structure
//Specifically, you should not need to know whether you are creating a root span or a child span
//Also, applications may have more than one root span

//What we would like, is a ZTracer that always has a span, and can perform `context`, `setStatus`, `putAll` and `span` safely
case class ZTracer private (
    private val currentSpan: FiberRef[Option[ZSpan]],
    private val entryPoint: ZEntryPoint,
) {
  val context: UIO[SpanContext] =
    currentSpan.get.map(_.fold(SpanContext.invalid)(_.context))

  def blankCurrentSpan: UIO[Unit] = currentSpan.set(None)

  def setCurrentSpan(in: ZSpan): UIO[Unit] =
    currentSpan.set(in.some)

  def extractHeaders(headerTypes: ToHeaders): UIO[TraceHeaders] =
    currentSpan.get.map {
      case Some(span) => span.extractHeaders(headerTypes)
      case None       => headerTypes.fromContext(SpanContext.invalid)
    }

  def span[R, E, A](
      name: String,
      kind: SpanKind = SpanKind.Internal,
      errorHandler: ErrorHandler = ErrorHandler.empty,
  )(f: ZSpan => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.scoped[R] {
      currentSpan.get
        .flatMap {
          case None =>
            entryPoint.rootSpan(name, kind, errorHandler)
          case Some(span) =>
            span.child(name, kind, errorHandler)
        }
        .flatMap { child =>
          currentSpan.locally(Some(child))(f(child))
        }
    }

  def fromHeaders[R, E, A](
      headers: TraceHeaders,
      kind: SpanKind = SpanKind.Internal,
      name: String,
  )(f: ZSpan => ZIO[R, E, A]): ZIO[R, E, A] =
    fromHeaders(headers, kind, name, ErrorHandler.empty)(f)

  def fromHeaders[R, E, A](
      headers: TraceHeaders,
      kind: SpanKind,
      name: String,
      errorHandler: ErrorHandler,
  )(f: ZSpan => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.scoped[R] {
      for {
        child  <- entryPoint.continueOrElseRootSpan(name, kind, headers, errorHandler)
        result <- currentSpan.locally(Some(child))(f(child))
      } yield result
    }

  def fromHeaders[R, E, A](
      toHeaders: ToHeaders,
      name: String,
      kind: SpanKind,
      errorHandler: ErrorHandler,
  )(f: ZSpan => ZIO[R, E, A]): ZIO[R, E, A] =
    extractHeaders(toHeaders).flatMap {
      fromHeaders(_, kind, name, errorHandler)(f)
    }

  def putAll(attrs: Map[String, AttributeValue]): UIO[Unit] =
    currentSpan.get
      .flatMap(
        ZIO.whenCase(_) { case Some(cs) =>
          cs.putAll(attrs)
        },
      )
      .unit
}

object ZTracer {
  // Accessors
  val context: URIO[ZTracer, SpanContext] =
    ZIO.serviceWithZIO[ZTracer] { _.context }

  val blankCurrentSpan: URIO[ZTracer, Unit] =
    ZIO.serviceWithZIO[ZTracer] { _.blankCurrentSpan }

  def setCurrentSpan(in: ZSpan): URIO[ZTracer, Unit] =
    ZIO.serviceWithZIO[ZTracer] { _.setCurrentSpan(in) }

  def putAll(attrs: Map[String, AttributeValue]): URIO[ZTracer, Unit] =
    ZIO.serviceWithZIO[ZTracer] { _.putAll(attrs) }

  def extractHeaders(headerTypes: ToHeaders): URIO[ZTracer, TraceHeaders] =
    ZIO.serviceWithZIO[ZTracer] { _.extractHeaders(headerTypes) }

  def span[R, E, A](
      name: String,
      kind: SpanKind = SpanKind.Internal,
      errorHandler: ErrorHandler = ErrorHandler.empty,
  )(f: ZSpan => ZIO[R, E, A]): ZIO[R & ZTracer, E, A] =
    ZIO.serviceWithZIO[ZTracer] { _.span(name, kind, errorHandler)(f) }

  def fromHeaders[R, E, A](
      headers: TraceHeaders,
      name: String,
      kind: SpanKind = SpanKind.Internal,
  )(f: ZSpan => ZIO[R, E, A]): ZIO[R & ZTracer, E, A] =
    fromHeaders(headers, name, kind, ErrorHandler.empty)(f)

  def fromHeaders[R, E, A](
      headers: TraceHeaders,
      name: String,
      kind: SpanKind,
      errorHandler: ErrorHandler,
  )(f: ZSpan => ZIO[R, E, A]): ZIO[R & ZTracer, E, A] =
    ZIO.serviceWithZIO[ZTracer] { _.fromHeaders(headers, kind, name, errorHandler)(f) }

  def fromHeaders[R, E, A](
      toHeaders: ToHeaders,
      name: String,
      kind: SpanKind,
      errorHandler: ErrorHandler,
  )(f: ZSpan => ZIO[R, E, A]): ZIO[R & ZTracer, E, A] =
    ZIO.serviceWithZIO[ZTracer] { _.fromHeaders(toHeaders, name, kind, errorHandler)(f) }

  // Constructors
  def make(
      params: SpanParams,
      entryPoint: ZEntryPoint,
  ): URIO[Scope, ZTracer] =
    entryPoint
      .continueOrElseRootSpan(params._1, params._2, params._3, params._4)
      .flatMap { span => make(span.some, entryPoint) }

  def make(current: ZSpan, entryPoint: ZEntryPoint): URIO[Scope, ZTracer] =
    make(current.some, entryPoint)

  def make(entryPoint: ZEntryPoint): URIO[Scope, ZTracer] =
    make(none[ZSpan], entryPoint)

  def make(current: Option[ZSpan], entryPoint: ZEntryPoint): URIO[Scope, ZTracer] =
    FiberRef
      .make[Option[ZSpan]](
        initial = current,
        // join = (parent, _) => parent // child spans should never be committed to parent, or should they?
      )
      .map { new ZTracer(_, entryPoint) }

  val layer: URLayer[ZEntryPoint, ZTracer] = ZLayer.scoped(
    ZIO
      .service[ZEntryPoint]
      .flatMap(make(_)),
  )
}

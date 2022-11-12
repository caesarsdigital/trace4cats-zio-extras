package com.caesars.tracing

import cats.data.NonEmptyList
import io.janstenpickle.trace4cats.{ErrorHandler, Span, ToHeaders}
import io.janstenpickle.trace4cats.model.*
import zio.*
import zio.interop.catz.*

class ZSpan private (span: Span[Task]) {
  def context: SpanContext = span.context
  def put(key: String, value: AttributeValue): UIO[Unit] = span.put(key, value).ignore
  def putAll(fields: (String, AttributeValue)*): UIO[Unit] = span.putAll(fields*).ignore
  def putAll(fields: Map[String, AttributeValue]): UIO[Unit] = span.putAll(fields).ignore
  def setStatus(spanStatus: SpanStatus): UIO[Unit] = span.setStatus(spanStatus).ignore
  def addLink(link: Link): UIO[Unit] = span.addLink(link).ignore
  def addLinks(links: NonEmptyList[Link]): UIO[Unit] = span.addLinks(links).ignore
  def child(name: String, kind: SpanKind): URIO[Scope, ZSpan] =
    span.child(name, kind).map(ZSpan(_)).toScopedZIO.orElse(ZSpan.noop)
  def child(name: String, kind: SpanKind, errorHandler: ErrorHandler): URIO[Scope, ZSpan] =
    span.child(name, kind, errorHandler).map(ZSpan(_)).toScopedZIO.orElse(ZSpan.noop)
  def extractHeaders(headerTypes: ToHeaders): TraceHeaders =
    headerTypes.fromContext(span.context)
}

object ZSpan {
  def apply(span: Span[Task]): ZSpan = new ZSpan(span)
  val noop: URIO[Scope, ZSpan] = Span.noop[Task].map(ZSpan(_)).toScopedZIO.orDie
}

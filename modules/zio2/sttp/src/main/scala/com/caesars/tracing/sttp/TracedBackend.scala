package com.caesars.tracing
package sttp

import io.janstenpickle.trace4cats.{ErrorHandler, ToHeaders}
import io.janstenpickle.trace4cats.model.{SampleDecision, SpanKind}
import io.janstenpickle.trace4cats.sttp.client3.{SttpRequest, SttpSpanNamer}
import io.janstenpickle.trace4cats.sttp.common.{SttpHeaders, SttpStatusMapping}
import _root_.sttp.capabilities.Effect
import _root_.sttp.client3.impl.zio.RIOMonadAsyncError
import _root_.sttp.client3.*
import _root_.sttp.monad.MonadError
import zio.*

object TracedBackend {
  case class Config(
      spanNamer: SttpSpanNamer = SttpSpanNamer.methodWithPath,
      toHeaders: ToHeaders = ToHeaders.all
  )

  def apply(
      delegate: HttpClient,
      tracer: ZTracer,
      config: Config = Config()
  ): HttpClient =
    new HttpClient {
      override def send[T, R >: ZioSttpCapabilities & Effect[Task]](request: Request[T, R]): Task[Response[T]] = {
        def createSpannedResponse(span: ZSpan, request: Request[T, R]): Task[Response[T]] = {
          val headers = config.toHeaders.fromContext(span.context)
          val reqWithTraceHeaders = request.headers(SttpHeaders.converter.to(headers).headers*)
          for {
            _ <- span
              .putAll(SttpRequest.toAttributes(request).toList*)
              .unless(span.context.traceFlags.sampled == SampleDecision.Drop)
            response <- delegate.send(reqWithTraceHeaders)
            _        <- span.setStatus(SttpStatusMapping.statusToSpanStatus(response.statusText, response.code))
          } yield response
        }

        def createErrorHandler: ErrorHandler = { case HttpError(body, statusCode) =>
          SttpStatusMapping.statusToSpanStatus(body.toString, statusCode)
        }

        tracer.span(
          config.spanNamer(request),
          SpanKind.Client,
          createErrorHandler
        )(createSpannedResponse(_, request))
      }

      override def close(): Task[Unit] = delegate.close()

      override def responseMonad: MonadError[Task] = new RIOMonadAsyncError[Any]
    }

  val layer: URLayer[ZTracer & HttpClient & Config, HttpClient] =
    ZLayer.fromZIO(
      for {
        delegate <- ZIO.service[HttpClient]
        tracer   <- ZIO.service[ZTracer]
        config   <- ZIO.service[Config]
      } yield TracedBackend(delegate, tracer, config)
    )
}

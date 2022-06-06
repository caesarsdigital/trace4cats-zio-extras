package com.caesars.tracing

import io.janstenpickle.trace4cats.ErrorHandler
import io.janstenpickle.trace4cats.ToHeaders
import io.janstenpickle.trace4cats.model.SampleDecision
import io.janstenpickle.trace4cats.model.SpanKind
import io.janstenpickle.trace4cats.sttp.client3.SttpRequest
import io.janstenpickle.trace4cats.sttp.client3.SttpSpanNamer
import io.janstenpickle.trace4cats.sttp.common.SttpHeaders
import io.janstenpickle.trace4cats.sttp.common.SttpStatusMapping
import sttp.capabilities.{Effect, WebSockets}
import sttp.capabilities.zio.ZioStreams
import sttp.client3.*
import sttp.client3.asynchttpclient.zio.SttpClient.{Service as ZIOSttpClient}
import sttp.client3.impl.zio.RIOMonadAsyncError
import sttp.monad.MonadError
import zio.*

object TracedBackend {
  type ZioSttpCapabilities = ZioStreams & WebSockets

  final case class TracedBackendConfig(
      spanNamer: SttpSpanNamer = SttpSpanNamer.methodWithPath,
      toHeaders: ToHeaders = ToHeaders.all
  )

  def apply(
      delegate: ZIOSttpClient,
      tracer: ZTracer,
      config: TracedBackendConfig = TracedBackendConfig()
  ): ZIOSttpClient =
    new SttpBackend[Task, ZioSttpCapabilities] {
      override def send[T, R >: ZioSttpCapabilities & Effect[Task]](
          request: Request[T, R]
      ): Task[Response[T]] = {
        def createSpannedResponse(
            span: ZSpan,
            request: Request[T, R]
        ): Task[Response[T]] = {
          val headers = config.toHeaders.fromContext(span.context)
          val reqWithTraceHeaders =
            request.headers(SttpHeaders.converter.to(headers).headers*)
          for {
            _ <- span
              .putAll(SttpRequest.toAttributes(request).toList*)
              .unless(span.context.traceFlags.sampled == SampleDecision.Drop)
            response <- delegate.send(reqWithTraceHeaders)
            _ <- span.setStatus(
              SttpStatusMapping.statusToSpanStatus(
                response.statusText,
                response.code
              )
            )
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

  val layer: URLayer[
    Has[ZTracer] & Has[ZIOSttpClient] & Has[TracedBackendConfig],
    Has[ZIOSttpClient]
  ] =
    (for {
      tracer <- ZIO.service[ZTracer]
      client <- ZIO.service[ZIOSttpClient]
      config <- ZIO.service[TracedBackendConfig]
    } yield TracedBackend(client, tracer, config)).toLayer
}

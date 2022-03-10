package com.caesars.tracing

import io.janstenpickle.trace4cats.{ErrorHandler, ToHeaders}
import io.janstenpickle.trace4cats.model.{SampleDecision, SpanKind}
import io.janstenpickle.trace4cats.sttp.client3.{SttpRequest, SttpSpanNamer}
import io.janstenpickle.trace4cats.sttp.common.{SttpHeaders, SttpStatusMapping}
import sttp.capabilities.Effect
import sttp.client3.impl.zio.RIOMonadAsyncError
import sttp.client3.*
import sttp.monad.MonadError
import zio.*

object TracedBackend {
  final case class TracedBackendConfig(
      spanNamer: SttpSpanNamer = SttpSpanNamer.methodWithPath,
      toHeaders: ToHeaders = ToHeaders.all
  )

  def apply(
      delegate: SttpBackend[Task, ZioSttpCapabilities],
      tracer: ZTracer,
      config: TracedBackendConfig = TracedBackendConfig()
  ): SttpBackend[Task, ZioSttpCapabilities] =
    new SttpBackend[Task, ZioSttpCapabilities] {
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

  val layer: URLayer[
    Has[ZTracer] & Has[SttpBackend[Task, ZioSttpCapabilities]] & Has[TracedBackendConfig],
    Has[SttpBackend[Task, ZioSttpCapabilities]]
  ] = (TracedBackend(_, _, _)).toLayer
}

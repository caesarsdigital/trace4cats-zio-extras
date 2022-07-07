package com.caesars.tracing

import io.janstenpickle.trace4cats.model.AttributeValue.{LongValue, StringValue}
import io.janstenpickle.trace4cats.model.{AttributeValue, SpanKind, SpanStatus, TraceHeaders}
import sttp.model.HeaderNames
import zhttp.http.*
import zio.*

object TracedHttp {
  def apply[R](
      ztracer: ZTracer,
      dropRequestWhen: Request => Boolean = _ => false,
      dropHeadersWhen: String => Boolean = HeaderNames.isSensitive,
  )(app: HttpApp[R, Throwable]): HttpApp[R, Throwable] = {
    import TracedHttpUtils.*

    Http.fromOptionFunction {
      case request if !dropRequestWhen(request) =>
        createUndroppedSpannedResponse(request, ztracer, app, dropHeadersWhen)
      case request => app(request)
    }
  }

  def layer[R](
      dropRequestWhen: Request => Boolean = _ => false,
      dropHeadersWhen: String => Boolean = HeaderNames.isSensitive,
  )(app: HttpApp[R, Throwable]): URIO[Has[ZTracer], HttpApp[R, Throwable]] =
    ZIO.service[ZTracer].map(apply[R](_, dropRequestWhen, dropHeadersWhen)(app))
}

object TracedHttpUtils {
  def putHeadersInSpan(
      res: Response,
      span: ZSpan,
      dropHeadersWhen: String => Boolean = HeaderNames.isSensitive,
  ): UIO[Unit] = {
    val headers: List[(String, AttributeValue)] =
      "status.code"      -> LongValue(res.status.asJava.code().toLong) ::
        "status.message" -> StringValue(res.status.asJava.reasonPhrase()) ::
        res.headers.toList
          .filterNot { case (k, _) => dropHeadersWhen(k) }
          .map { case (k, v) => k -> StringValue(v) }

    span.putAll(headers*)
  }

  def createSpannedResponse[R](
      request: Request,
      span: ZSpan,
      app: HttpApp[R, Throwable],
      dropHeadersWhen: String => Boolean = HeaderNames.isSensitive,
  ): ZIO[R, Option[Throwable], Response] = {
    app(request).tapBoth(
      {
        case None    => span.setStatus(SpanStatus.NotFound)
        case Some(e) => span.setStatus(SpanStatus.Internal(e.toString))
      },
      { res =>
        span.setStatus(toSpanStatus(res.status)) *> putHeadersInSpan(
          res,
          span,
          dropHeadersWhen,
        )
      },
    )
  }

  def createUndroppedSpannedResponse[R](
      request: Request,
      ztracer: ZTracer,
      app: HttpApp[R, Throwable],
      dropHeadersWhen: String => Boolean = HeaderNames.isSensitive,
  ): ZIO[R, Option[Throwable], Response] = {
    val method  = request.method
    val url     = request.url
    val headers = request.headers
    val name    = s"$method ${url.path}"
    val traceHeaders =
      TraceHeaders.of(
        headers.toList.filterNot { case (k, _) => dropHeadersWhen(k) }*,
      )

    ztracer.fromHeaders(traceHeaders, SpanKind.Server, name)(
      createSpannedResponse(request, _, app, dropHeadersWhen),
    )
    // All errors on this effects should be converted into responses by the Http app composition
    // e.g. app.catchAll(e => Response)
    // otherwise, the fiber dies  and tracing is moot
  }

  implicit class StatusOps(val self: Status) extends AnyVal {
    def isSuccess: Boolean =
      self.asJava.code() >= 200 && self.asJava.code() < 400
  }

  // TODO: incorporate response body into the span status
  val toSpanStatus: Status => SpanStatus = {
    case Status.BadRequest => SpanStatus.Internal("Bad Request")
    case Status.InternalServerError =>
      SpanStatus.Internal("Internal Server Error")
    case Status.Unauthorized        => SpanStatus.Unauthenticated
    case Status.Forbidden           => SpanStatus.PermissionDenied
    case Status.NotFound           => SpanStatus.NotFound
    case Status.TooManyRequests   => SpanStatus.Unavailable
    case Status.BadGateway         => SpanStatus.Unavailable
    case Status.ServiceUnavailable => SpanStatus.Unavailable
    case Status.GatewayTimeout     => SpanStatus.Unavailable
    case status if status.isSuccess => SpanStatus.Ok
    case _                          => SpanStatus.Unknown
  }

}

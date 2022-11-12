package com.caesars.tracing

import com.caesars.tracing.newrelic.LogTarget
import io.janstenpickle.trace4cats.kernel.{SpanCompleter, SpanSampler}
import io.janstenpickle.trace4cats.model.TraceProcess
import io.janstenpickle.trace4cats.newrelic.{Endpoint, NewRelicSpanCompleter}
import org.asynchttpclient.DefaultAsyncHttpClientConfig
import org.http4s.asynchttpclient.client.AsyncHttpClient
import org.http4s.client.Client
import org.http4s.client.middleware.Logger
import org.typelevel.ci.CIString
import zio.*
import zio.interop.catz.*

object NewRelic {
  case class LoggingConfig(level: LogLevel, targets: List[LogTarget]) {
    def hasTarget(target: LogTarget) = targets.exists(_ == target)
  }
  case class CompleterConfig(
      traceProcess: TraceProcess,
      apiKey: String,
      endpoint: Endpoint,
      logSettings: Option[LoggingConfig],
  )

  val SpanCompleterLayer: URLayer[CompleterConfig, SpanCompleter[Task]] = {
    def log(level: LogLevel)(msg: String): UIO[Unit] =
      level match {
        case LogLevel.Fatal   => ZIO.logFatal(msg)
        case LogLevel.Error   => ZIO.logError(msg)
        case LogLevel.Warning => ZIO.logWarning(msg)
        case LogLevel.Info    => ZIO.logInfo(msg)
        case LogLevel.All     => ZIO.log(msg)
        case LogLevel.Debug   => ZIO.logDebug(msg)
        case LogLevel.Trace   => ZIO.logTrace(msg)
        case _                => ZIO.unit
      }

    def client(cfg: CompleterConfig): URIO[Scope, Client[Task]] =
      // Blaze client does not support proxy, se we use Async client implementation (same underlying impl as Sttp)
      AsyncHttpClient
        .resource[Task] {
          // Using the default configs disrupts setting the proxy, so...
          // use the AHC defaults https://github.com/AsyncHttpClient/async-http-client/blob/master/client/src/main/resources/org/asynchttpclient/config/ahc-default.properties
          // these are overridable using system properties if need be
          new DefaultAsyncHttpClientConfig.Builder().setUseProxySelector(true).build()
        }
        .map(
          Logger(
            logHeaders = cfg.logSettings.exists(_.hasTarget(LogTarget.Headers)),
            logBody = cfg.logSettings.exists(_.hasTarget(LogTarget.Body)),
            redactHeadersWhen = _.compareTo(CIString("Api-Key")) == 0,
            logAction = cfg.logSettings.map(x => log(x.level)),
          ),
        )
        .toScopedZIO
        .orDie

    ZLayer.scoped {
      for {
        config <- ZIO.service[CompleterConfig]
        client <- client(config)
        result <- NewRelicSpanCompleter[Task](
          client = client,
          process = config.traceProcess,
          apiKey = config.apiKey,
          endpoint = config.endpoint,
        ).toScopedZIO
      } yield result
    }.orDie
  }

  val ZEntryPointLayer: URLayer[CompleterConfig, ZEntryPoint] = {
    val completer = ZLayer.service[CompleterConfig] >>> SpanCompleterLayer

    ZLayer.succeed(SpanSampler.always[Task]) ++
      completer >>>
      ZEntryPoint.layer
  }
}

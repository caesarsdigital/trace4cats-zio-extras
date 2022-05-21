package com.caesars.tracing

import io.janstenpickle.trace4cats.kernel.{SpanCompleter, SpanSampler}
import io.janstenpickle.trace4cats.model.TraceProcess
import io.janstenpickle.trace4cats.newrelic.{Endpoint, NewRelicSpanCompleter}
import org.asynchttpclient.DefaultAsyncHttpClientConfig
import org.http4s.asynchttpclient.client.AsyncHttpClient
import org.http4s.client.Client
import org.http4s.client.middleware.Logger
import zio.*
import zio.interop.catz.*
import io.janstenpickle.trace4cats.inject.EntryPoint

object NewRelic {
  case class CompleterConfig(traceProcess: TraceProcess, apiKey: String, endpoint: Endpoint)

  val SpanCompleterLayer: URLayer[CompleterConfig, SpanCompleter[Task]] = {
    val client: ULayer[Client[Task]] =
      // Blaze client does not support proxy, se we use Async client implementation (same underlying impl as Sttp)
      ZLayer.scoped(
        AsyncHttpClient
          .resource[Task] {
            // Using the default configs disrupts setting the proxy, so...
            // use the AHC defaults https://github.com/AsyncHttpClient/async-http-client/blob/master/client/src/main/resources/org/asynchttpclient/config/ahc-default.properties
            // these are overridable using system properties if need be
            new DefaultAsyncHttpClientConfig.Builder().setUseProxySelector(true).build()
          }
          .map(Logger(logHeaders = true, logBody = false, logAction = Some(s => ZIO.debug(s))))
          .toScopedZIO
          .orDie
      )

    ZLayer.service[CompleterConfig] ++ client >>>
      ZLayer.scoped {
        for {
          client <- ZIO.service[Client[Task]]
          config <- ZIO.service[CompleterConfig]
          result <- NewRelicSpanCompleter[Task](
            client = client,
            process = config.traceProcess,
            apiKey = config.apiKey,
            endpoint = config.endpoint
          ).toScopedZIO
        } yield result
      }.orDie
  }

  val EntryPointLayer: URLayer[CompleterConfig, EntryPoint[Task]] = {
    val completer = ZLayer.service[CompleterConfig] >>> SpanCompleterLayer

    ZLayer.succeed(SpanSampler.always[Task]) ++
      completer >>>
      com.caesars.tracing.EntryPointLayer
  }
}
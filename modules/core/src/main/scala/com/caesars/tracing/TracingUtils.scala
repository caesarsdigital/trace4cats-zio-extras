package com.caesars.tracing

import io.janstenpickle.trace4cats.inject.EntryPoint
import io.janstenpickle.trace4cats.kernel.{SpanCompleter, SpanSampler}
import io.janstenpickle.trace4cats.model.TraceProcess
import io.janstenpickle.trace4cats.newrelic.{Endpoint, NewRelicSpanCompleter}
import org.asynchttpclient.DefaultAsyncHttpClientConfig
import org.http4s.asynchttpclient.client.AsyncHttpClient
import org.http4s.client.Client
import org.http4s.client.middleware.Logger
import zio.*
import zio.interop.catz.*
import zio.interop.catz.implicits.rts

object TracingUtils {
  private val sampler: ULayer[Has[SpanSampler[Task]]] = ZLayer.succeed(SpanSampler.always[Task])
  private val client: ULayer[Has[Client[Task]]] =
    // Blaze client does not support proxy, se we use Async client implementation (same underlying impl as Sttp)
    AsyncHttpClient
      .resource[Task] {
        // Using the defailt configs disrupts setting the proxy, so...
        // use the AHC defaults https://github.com/AsyncHttpClient/async-http-client/blob/master/client/src/main/resources/org/asynchttpclient/config/ahc-default.properties
        // these are overridable using system properties if need be
        new DefaultAsyncHttpClientConfig.Builder().setUseProxySelector(true).build()
      }
      .map(Logger(logHeaders = true, logBody = false, logAction = Some(s => Task(org.log4s.getLogger.debug(s)))))
      .toManagedZIO
      .orDie
      .toLayer

  final case class NewrelicCompleterConfig(traceProcess: TraceProcess, apiKey: String, endpoint: Endpoint)

  val newRelicCompleter: URLayer[Has[NewrelicCompleterConfig], Has[SpanCompleter[Task]]] =
    ZLayer.service[NewrelicCompleterConfig] ++ client >>>
      ZManaged
        .services[Client[Task], NewrelicCompleterConfig]
        .flatMap { case (client, config) =>
          NewRelicSpanCompleter[Task](
            client = client,
            process = config.traceProcess,
            apiKey = config.apiKey,
            endpoint = config.endpoint
          ).toManagedZIO
        }
        .orDie
        .toLayer

  val entryPointLayer: URLayer[Has[SpanSampler[Task]] & Has[SpanCompleter[Task]], Has[EntryPoint[Task]]] =
    (for {
      sampler   <- ZIO.service[SpanSampler[Task]]
      completer <- ZIO.service[SpanCompleter[Task]]
    } yield EntryPoint[Task](sampler, completer)).toLayer

  val newRelicEntryPoint: URLayer[Has[NewrelicCompleterConfig], Has[EntryPoint[Task]]] =
    sampler ++ (ZLayer.service[NewrelicCompleterConfig] >>> newRelicCompleter) >>> entryPointLayer
}

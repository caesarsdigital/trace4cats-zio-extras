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

def EntryPointLayer: URLayer[SpanSampler[Task] & SpanCompleter[Task], EntryPoint[Task]] =
  ZLayer.fromZIO(
    for {
      sampler   <- ZIO.service[SpanSampler[Task]]
      completer <- ZIO.service[SpanCompleter[Task]]
    } yield EntryPoint[Task](sampler, completer)
  )

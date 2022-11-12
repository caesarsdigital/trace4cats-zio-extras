package com.caesars.tracing.newrelic

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

enum LogTarget:
   case Headers, Body

   def fromString(value: String): Option[LogTarget] =
     LogTarget.values.find(_.toString().equalsIgnoreCase(value.trim()))

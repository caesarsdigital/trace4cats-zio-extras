package com.caesars

import io.janstenpickle.trace4cats.inject.EntryPoint
import io.janstenpickle.trace4cats.kernel.{SpanCompleter, SpanSampler}
import zio.*
import zio.interop.catz.*

package object tracing {
  def EntryPointLayer: URLayer[SpanSampler[Task] & SpanCompleter[Task], EntryPoint[Task]] =
    ZLayer.fromZIO(
      for {
        sampler   <- ZIO.service[SpanSampler[Task]]
        completer <- ZIO.service[SpanCompleter[Task]]
      } yield EntryPoint[Task](sampler, completer)
    )
}

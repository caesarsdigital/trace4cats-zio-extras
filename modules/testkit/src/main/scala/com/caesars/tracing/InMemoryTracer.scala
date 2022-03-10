package com.caesars.tracing

import io.janstenpickle.trace4cats.inject.EntryPoint
import zio.interop.catz.*
import zio.{Task, ZLayer}

object InMemoryTracer {
  val make = ZLayer.succeed(EntryPoint.noop[Task]) >>> ZTracer.layer
}

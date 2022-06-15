package com.caesars.tracing

import io.janstenpickle.trace4cats.inject.EntryPoint
import zio.interop.catz.concurrentInstance
import zio.{Has, Task, ULayer, ZLayer}

object InMemoryTracer {
  val make: ULayer[Has[ZTracer]] =
    ZLayer.succeed(EntryPoint.noop[Task]) >>> ZTracer.layer
}

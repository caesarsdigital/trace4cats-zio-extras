package com.caesars.tracing

import trace4cats.EntryPoint
import zio.interop.catz.*
import zio.{Task, ZLayer}

object InMemoryTracer {
  val make = ZLayer.succeed(EntryPoint.noop[Task]) >>> ZTracer.layer
}

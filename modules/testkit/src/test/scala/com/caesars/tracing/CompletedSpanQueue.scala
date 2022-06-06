package com.caesars.tracing

import cats.effect.Ref
import io.janstenpickle.trace4cats.model.CompletedSpan
import zio.Task
import zio.interop.catz.asyncRuntimeInstance
import zio.interop.catz.implicits.rts

import scala.collection.immutable.{Queue => ScalaQueue}

type CompletedSpanRef = cats.effect.Ref[Task, ScalaQueue[CompletedSpan]]

class CompletedSpanQueue(val ref: CompletedSpanRef) {
  def get = ref.get
}
object CompletedSpanQueue {
  def mk = Ref
    .of[Task, ScalaQueue[CompletedSpan]](ScalaQueue.empty)
    .map(new CompletedSpanQueue(_))
    .toLayer
    .orDie
}

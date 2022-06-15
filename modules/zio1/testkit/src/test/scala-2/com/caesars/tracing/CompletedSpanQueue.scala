package com.caesars.tracing

import scala.collection.immutable.{Queue => StdQueue}

import cats.effect.Ref as CERef
import io.janstenpickle.trace4cats.model.CompletedSpan
import zio.Task
import zio.interop.catz.asyncRuntimeInstance
import zio.interop.catz.implicits.rts

import CompletedSpanQueue.*

class CompletedSpanQueue(val ref: CompletedSpanRef) {
  def get = ref.get
}
object CompletedSpanQueue {
  type CompletedSpanRef = CERef[Task, StdQueue[CompletedSpan]]

  def mk = CERef
    .of[Task, StdQueue[CompletedSpan]](StdQueue.empty)
    .map(new CompletedSpanQueue(_))
    .toLayer
    .orDie
}

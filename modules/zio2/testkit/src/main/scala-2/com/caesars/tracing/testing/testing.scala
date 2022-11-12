package com.caesars.tracing

import cats.effect.{Ref as CERef}
import io.janstenpickle.trace4cats.model.{AttributeValue, CompletedSpan}
import scala.collection.immutable.Queue
import zio.Task

package object testing {
  type SpanRecorder = CERef[Task, Queue[CompletedSpan]]

  def evalAttributes(xs: Map[String, AttributeValue]): Map[String, Any] =
    xs.view.mapValues(_.value.value).toMap

  implicit class AttributeOps(self: Map[String, AttributeValue]) {
    val xs = evalAttributes(self)

    def contains(key: String, value: Any): Boolean =
      xs.get(key).contains(value)
  }

  implicit class CompletedSpanOps(val self: CompletedSpan) extends AnyVal {
    def isRoot: Boolean = self.context.parent.isEmpty
  }
}

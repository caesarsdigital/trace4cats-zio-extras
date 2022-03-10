package com.caesars.tracing

import io.janstenpickle.trace4cats.model.{AttributeValue, CompletedSpan}

package object testing {
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

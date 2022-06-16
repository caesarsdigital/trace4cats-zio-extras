package com.caesars.tracing.newrelic

sealed trait LogTarget extends Product with Serializable
object LogTarget {
  case object Body extends LogTarget
  case object Headers extends LogTarget

  val values: Array[LogTarget] = Array(Headers, Body)
  def fromString(value: String): Option[LogTarget] =
    values.find(_.toString().equalsIgnoreCase(value.trim()))
}

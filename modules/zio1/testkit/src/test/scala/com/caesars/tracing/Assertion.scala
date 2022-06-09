package com.caesars.tracing

import io.janstenpickle.trace4cats.model.{CompletedSpan, SpanKind, SpanStatus}
import sttp.client3.Response
import sttp.model.StatusCode
import zio.test.Assertion.*
import zio.test.AssertionM.Render.*
import zio.test.{AssertResult, Assertion, TestResult, assertTrue}

object Assertion {

  def hasLength[A](n: Int): Assertion[List[A]] =
    hasSize(equalTo(n))

  def hasLast[A](assertion0: Assertion[A]) =
    assertion[List[A]]("hasLast")(param(assertion0)) { values =>
      values.lastOption.map(assertion0(_)).getOrElse(nothing(())).isSuccess
    }

  def fromFunction[A](name: String)(
      assertion: A => AssertResult,
  ): zio.test.Assertion[A] =
    assertionDirect(name)() { a => assertion(a) }

  def map[A]: MapAssert[A] = new MapAssert

  class MapAssert[A] {
    def apply[B](name: String, f: A => B)(
        assertion0: Assertion[B],
    ): Assertion[A] =
      assertion(name)(param(assertion0))(value => assertion0(f(value)).isSuccess)
  }

  def mapN[A]: MapAssertN[A] = new MapAssertN

  class MapAssertN[A] {
    def apply[B](name: String, f: A => B)(
        assertion0: Assertion[List[B]],
    ): Assertion[List[A]] =
      assertion(name)(param(assertion0))(value => assertion0(value.map(f)).isSuccess)
  }

  implicit class ZipAssertion[A](self: zio.test.Assertion[A]) {
    def zip[B](that: Assertion[B]): Assertion[(A, B)] =
      assertion[(A, B)]("zip")(param((self, that))) { (l, r) =>
        (self(l) && that(r)).isSuccess
      }

    def <*>[B](that: Assertion[B]): Assertion[(A, B)] =
      zip(that)
  }
}

object TracingAssertion {

  def containsHeader(
      key: String,
      value: String,
  ): Assertion[Response[Either[String, String]]] =
    hasField("header", _.request.header(key), equalTo(Some(value)))

  def hasAttribute[A](key: String, value: A): Assertion[CompletedSpan] =
    hasField(
      key,
      _.attributes.get(key).map(_.value.value),
      equalTo(Some(value)),
    )

  def hasStatusCode(code: Int) = hasAttribute("status.code", code)

  def hasKind(kind: SpanKind): Assertion[CompletedSpan] =
    hasField("kind", _.kind, equalTo(kind))

  def hasName(name: String): Assertion[CompletedSpan] =
    hasField("name", _.name, equalTo(name))

  def hasNoParent: Assertion[CompletedSpan] =
    hasField("parent", _.context.parent, isNone)

  def hasStatus(status: SpanStatus): Assertion[CompletedSpan] =
    hasField("status", _.status, equalTo(status))

  def hasHostname(value: String): Assertion[CompletedSpan] =
    hasAttribute("remote.service.hostname", "localhost")

  def isOk: Assertion[Response[Either[String, String]]] =
    hasField("code", _.code.isSuccess, isTrue)

  def isNotOk: Assertion[Response[Either[String, String]]] =
    isOk.negate
}

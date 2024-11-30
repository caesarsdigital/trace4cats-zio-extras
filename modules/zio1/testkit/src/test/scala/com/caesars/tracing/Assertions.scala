package com.caesars.tracing

import trace4cats.modeltrace4cats.modeltrace4cats.model.{CompletedSpan, SpanKind, SpanStatus}
import sttp.client3.Response
import zio.test.Assertion.*
import zio.test.AssertionM.Render.*
import zio.test.{AssertResult, Assertion}
import scala.reflect.ClassTag
import scala.util.Success
import scala.util.Failure

object Assertions {

  def hasLength(n: Int): Assertion[List[?]] =
    hasSize(equalTo(n))

  def hasLast[A](assertion0: Assertion[A]) =
    assertionDirect[List[A]]("hasLast")(param(assertion0)) { values =>
      values.lastOption.map(assertion0(_)).getOrElse(nothing(()))
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
      assertionDirect(name)(param(assertion0))(value => assertion0(value.map(f)))
  }

  implicit class ZipAssertion[A](self: zio.test.Assertion[A]) {
    def zip[B](that: Assertion[B]): Assertion[(A, B)] =
      assertionDirect[(A, B)]("zip")(param((self, that))) { case (l, r) =>
        (self(l) && that(r))
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
    hasField("header", _.request.header(key), isSome(equalTo(value)))

  // sealed trait AttributeLookup[-A]
  // object AttributeLookup {
  //   case class Found[A](value: A)                            extends AttributeLookup[A]
  //   case class NotFound(name: String)                        extends AttributeLookup[Any]
  //   case class TypeMismatch(name: String, typeFoung: String) extends AttributeLookup[Nothing]
  // }
  // import AttributeLookup.*

  def hasAttribute[A: ClassTag](key: String, value: A): Assertion[CompletedSpan] =
    hasField(
      key,
      _.attributes.get(key).map(_.value.value) match {
        case Some(v: A) => Success(v)
        case None       => Failure(new RuntimeException("Attribute not found: " + key))
        case _          => Failure(new RuntimeException(s"Attribute type mismatch for [$key]: ${value.getClass}"))
      },
      isSuccess(equalTo(value)),
    )

  def hasStatusCode(code: Long) =
    hasAttribute("status.code", code)

  def hasKind(kind: SpanKind): Assertion[CompletedSpan] =
    hasField("kind", _.kind, equalTo(kind))

  def hasName(name: String): Assertion[CompletedSpan] =
    hasField("name", _.name, equalTo(name))

  def hasNoParent: Assertion[CompletedSpan] =
    hasField("parent", _.context.parent, isNone)

  def hasStatus(status: SpanStatus): Assertion[CompletedSpan] =
    hasField("status", _.status, equalTo(status))

  def hasHostname(value: String): Assertion[CompletedSpan] =
    hasAttribute("remote.service.hostname", value)

  def isOk: Assertion[Response[Either[String, String]]] =
    hasField("code", _.code.isSuccess, isTrue)

  def isNotOk: Assertion[Response[Either[String, String]]] =
    isOk.negate
}

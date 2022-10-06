package ensime

import SigParser.parse

class SigParserTests extends junit.framework.TestCase {

  def assertEquals[A](expected: A, got: A): Unit = assert(expected == got, s"got $got but expected $expected")

  def testMethods: Unit = {

    assertEquals(
      Sig("apply", List(List("foo: String"))),
      parse("apply(foo: String): Unit")
    )

    assertEquals(
      Sig("apply", List(List("foo: A"))),
      parse("apply[A](foo: A): Unit")
    )

    assertEquals(
      Sig("apply", Nil),
      parse("apply: Unit")
    )

    assertEquals(
      Sig("::", Nil),
      parse(":: Unit")
    )

    assertEquals(
      Sig("apply", List(List("foo: String", "bar: Int"))),
      parse("apply(foo: String, bar: Int): Unit")
    )

    assertEquals(
      Sig("apply", List(List("foo: String", "bar: Map[String, Set[Int]]"))),
      parse("apply(foo: String, bar: Map[String, Set[Int]]): Unit")
    )

    assertEquals(
      Sig("++:", List(List("prefix: Array[? <: B]"), List("implicit _: ClassTag[B]"))),
      parse("++:[B >: A](prefix: Array[? <: B])(implicit _: ClassTag[B]): Array[B]")
    )

    assertEquals(
      Sig("distinct", Nil),
      parse("distinct: C")
    )

    assertEquals(
      Sig("array", Nil),
      parse("array: => Array[String]")
    )

    assertEquals(
      Sig("canEqual", List(List("that: Any"))),
      parse("canEqual(that: Any)Boolean")
    )
  }
}

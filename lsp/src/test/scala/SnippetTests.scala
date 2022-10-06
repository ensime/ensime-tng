package ensime

import SigParser.snippet

class SnippetTests extends junit.framework.TestCase {

  def assertEquals[A](expected: A, got: A): Unit = assert(expected == got, s"got $got but expected $expected")

  def testMethods: Unit = {

    assertEquals(
      "(${1:foo: String})",
      snippet(Sig("apply", List(List("foo: String"))).stripImplicit)
    )

    assertEquals(
      "",
      snippet(Sig("apply", Nil).stripImplicit)
    )

    assertEquals(
      "::",
      snippet(Sig("::", Nil).stripImplicit)
    )

    assertEquals(
      "(${1:foo: String}, ${2:bar: Int})",
      snippet(Sig("apply", List(List("foo: String", "bar: Int"))).stripImplicit)
    )

    assertEquals(
      "++: ${1:prefix: Array[? <: B]}",
      snippet(Sig("++:", List(List("prefix: Array[? <: B]"), List("implicit _: ClassTag[B]"))).stripImplicit)
    )

  }
}

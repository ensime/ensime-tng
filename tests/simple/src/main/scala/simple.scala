package simple

import java.lang.String

object Main {

  def main(args: Array[String]): Unit = {
    args.map(_.toLowerCase).toList

    val _ = Foo.foo

    java.util.regex.Pattern.compile("foo"): Unit
    java.nio.file.Files.notExists(java.nio.file.Path.of("/")): Unit
  }

  @scala.annotation.tailrec
  def wibble(b: Boolean): Boolean = if (b) b else wibble(true)

  def selector = Array[String]()

  def literal = classOf[String]

  def aliases: Serializable = null
}

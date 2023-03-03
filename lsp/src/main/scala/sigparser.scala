package ensime

import scala.util.control.NoStackTrace

case class Sig(name: String, paramss: List[List[String]]) {
  def stripImplicit = {
    val update = paramss.filter { params =>
      params.isEmpty || !params(0).startsWith("implicit ")
    }
    copy(paramss = update)
  }

  lazy val isInfix = SigParser.isSymbolic(name) && paramss.length == 1 && paramss(0).length == 1
}

// a very simple scala method signature parser that is able to extract the
// method name and all the paramss, so that the individual parts may be used in
// a completion snippet.
object SigParser {
  def isSymbolic(name: CharSequence) = name.toString.forall(!_.isLetterOrDigit)

  def snippet(s: Sig): String = {
    val builder = new StringBuilder

    if (s.name != "apply")
      builder.append(s.name)

    if (s.isInfix) {
      builder.append(" ${1:")
      builder.append(s.paramss(0)(0))
      builder.append("}")
    } else {
      var i = 1

      s.paramss.foreach { params =>
        if (params.isEmpty) builder.append("()") // not everybody's cup of tea
        else {
          builder.append("(")
          var first = true
          params.foreach { p =>
            if (!first) builder.append(", ")
            builder.append("${")
            builder.append(i)
            builder.append(":")
            builder.append(p)
            builder.append("}")
            first = false
            i += 1
          }
          builder.append(")")
        }
      }
    }

    builder.toString
  }

  def parse(s: String): Sig = parseSig(new RetractReader(s))

  private def parseSig(r: RetractReader): Sig = {
    val name = new StringBuilder

    var paramss: List[List[String]] = Nil

    var finished = false
    try while (!finished) {
      val c = r.readChar()
      if (c == '[') {
        r.retract()
        parseParams(r): Unit
      } else if (c == '(') {
        r.retract()
        while (!finished) {
          if (r.readChar() == '(') {
            r.retract()
            paramss = paramss :+ parseParams(r)
          } else {
            finished = true
          }
        }
      } else if (c.isWhitespace || (c == ':' && !isSymbolic(name))) {
        finished = true
      } else {
        name.append(c)
      }
    } catch {
      case UnexpectedEnd =>
    }

    Sig(name.toString, paramss)
  }

  private def parseParams(r: RetractReader): List[String] = {
    var depth = 0
    var parts: List[String] = Nil
    val builder = new StringBuilder

    def push(): Unit = {
      parts = parts :+ builder.toString.trim
      //println(s"PUSH $parts")

      builder.clear()
    }
    do {
      val c = r.readChar()
      //println(s"DEBUG $parts |||| $builder |||| $c")

      if (opener.contains(c)) {
        depth += 1
        if (depth > 1) builder.append(c)
      } else if (closer.contains(c)) {
        depth -= 1
        if (depth == 0) push()
        else builder.append(c)
      } else if (c == ',' && depth == 1) {
        push()
      } else if (depth > 0) {
        builder.append(c)
      }
    } while (depth > 0)

    parts
  }

  private val opener: Set[Char] = Set('[', '(', '{')
  private val closer: Set[Char] = Set(']', ')', '}')
}


object UnexpectedEnd extends Exception("if you see this a dev made a mistake") with NoStackTrace

final class RetractReader(cs: CharSequence) {
  private[this] var i: Int = 0

  private[this] def read_(): Char = {
    val c = cs.charAt(i)
    i += 1
    c
  }

  @inline def eof(): Boolean = i >= cs.length

  def read(): Int = {
    if (eof()) {
      i += 1
      return -1
    }

    read_()
  }

  def readChar(): Char = {
    if (eof()) {
      i += 1
      throw UnexpectedEnd
    }

    read_()
  }

  def retract(): Unit = {
    if (i <= 0) throw new IllegalStateException("if you see this a dev made a mistake")

    i -= 1
  }
}

// Copyright: Sam Halliday
// License: GPLv3+
package ensime

import java.io.{ File, PrintStream }
import java.net.URI
import java.nio.file.{ Files, Path }
import java.util.concurrent.atomic.AtomicReference

import dotty.tools.dotc.ast.Positioned
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.config.{ Feature, PathResolver }
import dotty.tools.dotc.core.Contexts.{ Context, ContextBase, FreshContext }
import dotty.tools.dotc.core.TypeErasure
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.Symbols
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.core.Types.Type
import dotty.tools.dotc.core.{ MacroClassLoader, Mode }
import dotty.tools.dotc.interactive.{ Completion, InteractiveCompiler, InteractiveDriver, Interactive, SourceTree }
import dotty.tools.dotc.interfaces.{ Diagnostic, SimpleReporter }
import dotty.tools.dotc.reporting.Reporter
import dotty.tools.dotc.transform.SymUtils._
import dotty.tools.dotc.transform.TypeUtils._
import dotty.tools.dotc.typer.ImportInfo
import dotty.tools.dotc.util.{ SourceFile, SourcePosition, Spans }
import dotty.tools.io.{ AbstractFile, VirtualFile }

import Compiler._

class Compiler(driver: InteractiveDriver)(implicit ctx: Context) {
  // many thanks to Guillaume Martres for helping to implement this class

  // .fullName.show is giving the Java name, not the Scala name we also don't
  // seem to be able to dealias type symbols. Do a diff between 2.13 and 3.1
  // output to see the kinds of changes. Consider using a custom pretty printer,
  // e.g. RefinedPrinter#fullNameString

  private def symbolAt_(pos: SourcePosition): Option[Symbol] = {
    val uri = Path.of(pos.source.file.path).toUri
    val path = Interactive.pathTo(driver.openedTrees(uri), pos)

    // should really dialias types here
    Interactive.enclosingSourceSymbols(path, pos).headOption
  }

  def symbolAt(pos: SourcePosition): Option[String] = {
    symbolAt_(pos).map(_.fullName.show.replace("$.", ".").replace("$", "."))
    // Scala 3 is not always consistent with Scala 2 here, e.g. classOf
  }

  // note that we could also get the binaryFile, which points to the .class file
  // that idiomatically holds the symbol (except it might not physically exist
  // in the .class in the case of inline methods). The compiler should also be
  // able to provide the file name and line number of a .scala file (not a .java
  // file) that produced that .class thanks to TASTY. That path is relative to
  // the compiler that generated them (which will be on somebody else's machine
  // in the case of maven dependencies), and must therefore still be resolved to
  // local jar files.
  def fqnAt(pos: SourcePosition): Option[(FullyQualifiedName, Option[SourcePosition])] = {
    symbolAt_(pos).map { sym =>
      // the line number always seems to be 0 when the file has been typechecked
      // but isn't the active compilation unit, which seems like a Scala 3 bug.
      val p = if (sym.srcPos.focus.exists) Some(sym.srcPos.focus) else None
      (toFqn(sym), p)
    }
  }

  // implementation note: in Scala 2 we did this off the Tree, but now we're
  // doing it off the Symbol just because it's more convenient.
  def typeAt(pos: SourcePosition): Option[String] = {
    // it doesn't seem possible to get fully qualified names for types
    symbolAt_(pos).map { s =>
      if (s.isTerm) s.asTerm.info.dealias.show
      else s.fullName.show // no way to dealias
    }
  }

  def completeAt(pos: SourcePosition): List[String] = {
    if (pos.source.content()(pos.withSpan(pos.span.shift(-1)).point) != '.') {
      throw new IllegalArgumentException("completions only allowed immediately following a dot.")
    }

    val prefix = symbolAt_(pos.withSpan(pos.span.shift(-1)))

    val (_, completions) = Completion.completions(pos)

    val bannedOwners: Set[Symbol] = Set(
      ctx.definitions.AnyClass,
      ctx.definitions.ObjectClass,
      Symbols.requiredClass("scala.Predef.any2stringadd"),
      Symbols.requiredClass("scala.Predef.ArrowAssoc"),
      Symbols.requiredClass("scala.Predef.Ensuring")
    )
    val unbannedFields: Set[String] = Set(
      "hashCode",
      "toString"
    )
    def isBanned(name: String, s: Symbol) = name.startsWith("unary_") ||
      (s.owner.isPrimitiveValueClass && s.name.decode != s.name.encode) ||
      (bannedOwners(s.owner) && !unbannedFields(name))

    completions
      .filter(c => c.symbols.length == 1 )
      .filter { c =>
        val sym = c.symbols.head
        sym.isTerm || sym.is(Package) || sym.is(Module)
      }
      .filterNot { c =>
        val sym = c.symbols.head
        // scala 3 is returning inner classes from deeper nesting
        c.label.contains("$") ||
          sym.isConstructor || sym.is(Synthetic) || sym.isDeprecated || isBanned(c.label, sym)
      }
      .map { c =>
        val name = c.label.trim
        val sym = c.symbols.head
        val tpe = prefix match {
          case None => sym.info
          case Some(pre) => sym.asSeenFrom(pre.info).info
        }
        // Scala 3's asSeenFrom doesn't do as thorough a job as Scala 2's
        // typeSignatureIn. For example, the generic methods on an Array[String]
        // still come back with T rather than being resolved to String.
        var signature = tpe.dealias.show.trim
          .replace("\n", "")
          .replaceAll(" {2,}", " ")
          .replaceAll(" ([]\\[,\\(\\)])", "$1")
        if (sym.is(Method) || !sym.info.isByName)
          signature = signature.stripPrefix("=> ")
        if (!signature.contains(":")) signature = s": $signature"

        name + signature
      }
      .distinct
      .sorted(Ordering.comparatorToOrdering(String.CASE_INSENSITIVE_ORDER))
  }

  private def toFqn(sym: Symbol): FullyQualifiedName = {
    if (sym.is(Package)) packageName(sym)
    else if (sym.isType) className(sym).normalise
    else if (sym.is(Module)) className(sym)
    else if (sym.is(Method)) methodName(sym)
    else fieldName(sym)
  }

  private def packageName(sym: Symbol): ClassName =
    ClassName(
      sym.ownersIterator.takeWhile(!_.isRoot).toList.reverse.map(_.name.encode.show),
      "package$"
    )

  private def className(sym: Symbol): ClassName = {
    val (packages, nested) = sym.ownersIterator.takeWhile(!_.isRoot).toList.reverse.span(_.is(Package))
    val pkg = packages.map(_.name.encode.show)
    val name = nested.map(_.name.encode.show).mkString("$")
    val postfix = if (nested.last.is(Module) && !nested.last.is(JavaDefined)) "$" else ""
    ClassName(pkg, name + postfix)
  }

  private def methodName(sym: Symbol): MethodName = {
    val clazz = className(sym.owner) // scala 2 used the enclosingClass
    val name = sym.name.encode.show

    val descriptor: Descriptor = {
      val params = sym.info.paramInfoss.flatten.map(descriptorType(_))
      val ret = descriptorType(sym.info.finalResultType)
      Descriptor(params, ret)
    }

    MethodName(clazz, name, descriptor.descriptorString)
  }

  private def descriptorType(t: Type): DescriptorType = {
    var t_ = t
    var vararg = false

    // Scala 2 used to convert varargs to array, but Scala 3 requires us to special case
    if (t.isRepeatedParam) {
      t_ = t.repeatedToSingle
      vararg = true
    }

    var c: DescriptorType = className(TypeErasure.erasure(t_.dealias).typeSymbol).normalise
    if (c == ClassName.ScalaArray) {
      c = ArrayDescriptor(descriptorType(t_.typeParamSymbols.head.info))
    }
    if (vararg) {
      c = ArrayDescriptor(c)
    }
    c
  }

  private def fieldName(sym: Symbol): FieldName = {
    val clazz = className(sym.owner)
    val name = sym.name.encode.show
    FieldName(clazz, name)
  }

}

object Compiler {
  // In Scala 2 we parsed the args into a Settings object and the remaining
  // arguments for us to interpret. In Scala 3, there is no Settings object,
  // it's all implemented as typeless Array[Any] inside a huge mutable Context
  // object. So what we do is hack it so that we at least split the code into
  // the parts that are the scalac args and the parts that are our args.
  def mkSettings(args: Array[String], err: PrintStream): (List[String], List[String]) = {
    val ctx = ContextBase().initialCtx
    val parsed = ctx.settings.processArguments(args.toList, processAll = false, ctx.settingsState)

    (args.take(args.length - parsed.arguments.length).toList, parsed.arguments)
  }

  def pos(f: SourceFile, s: String): SourcePosition = s.split(":").toList match {
    case o :: Nil => SourcePosition(f, Spans.Span(o.toInt))
    case col :: row :: Nil => throw new IllegalArgumentException(s"col:row ($col:$row) positions are not supported yet")
    case other => throw new IllegalArgumentException(s"not a valid position '$other'")
  }

  def cp(settings: List[String]): List[URI] = {
    val ctx = ContextBase().initialCtx
    ctx.settings.processArguments(settings, processAll = true, ctx.settingsState)

    val resolver = new PathResolver(using ctx)
    resolver.result.asURLs.map(_.toURI).toList
  }

  // a cache of the last successful compiler that has the given context loaded.
  private val cached: AtomicReference[Option[(String, List[(Long, File)], InteractiveDriver)]] = new AtomicReference(None)
  def withCompiler[A](settings: List[String], target: String, others: List[String])(f: (SourceFile, Compiler) => A): A = {
    val key = (
      settings.mkString,
      others.sorted.map(new File(_)).filter(_.isFile).map(f => (f.lastModified, f))
    )

    // InteractiveDriver / Driver are indirection over the Context /
    // InteractiveCompiler that we actually want access to. But the setup is
    // tedious so we just use the Driver code, even though it does a lot of
    // stuff we don't need.
    val (driver, deps) = cached.getAndSet(None) match {
      case Some((k_1, k_2, warm)) if k_1 == key._1 && k_2 == key._2 =>
        (warm, Nil)
      case other =>
        val idriver = new InteractiveDriver(settings ++ List("--color", "never"))
        (idriver, others)
    }

    val sources = (target :: deps).map { s =>
      val p = Path.of(s).toAbsolutePath
      p.toUri -> new SourceFile(
        new VirtualFile(p.toString),
        Files.readString(p).toCharArray
      )
    }

    // in scala 2 we needed to filter out bad files, but apparently scala 3 is better
    sources.foreach {
      case (uri, source) => driver.run(uri, source) // discarding diagnostics
    }

    // reset the compilation unit to the target file
    val ctx = driver.compilationUnits.get(sources.head._1) match {
      case Some(unit) => driver.currentCtx.fresh.setCompilationUnit(unit)
      case None => driver.currentCtx.fresh
    }

    // Note, for when we move to a cached version. According to Guillaume, there
    // is no way to unload a source file. There is supposed to be some mechanism
    // that does a fast re-typecheck of all files on an update which will
    // automagically unload symbols that were defined in the previous versions.
    // Either we trust that this works and update one hot compiler, or we play
    // it safe and do the same as we do for Scala 2 and only cache based on file
    // content of the working set.
    try {
      val result = f(sources.head._2, new Compiler(driver)(ctx))
      cached.set(Some((key._1, key._2, driver)))
      result
    } finally {
      driver.close(sources.head._1)
    }
  }

}

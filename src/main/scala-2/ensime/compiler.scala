// Copyright: Sam Halliday
// License: GPLv3+
package ensime

import java.io.File
import java.io.PrintStream
import java.net.URI
import java.nio.file.{ Files, Path }
import java.util.concurrent.atomic.AtomicReference

import scala.annotation._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive
import scala.tools.nsc.reporters.Reporter
import scala.reflect.internal.util.{Position, BatchSourceFile, SourceFile}
import scala.reflect.io.VirtualFile
import scala.tools.util.PathResolver

final class Compiler(
  override val settings: Settings
) extends interactive.Global(settings, NilReporter) with SymbolToFqn {

  def parses(src: SourceFile): Boolean = try {
    reporter.reset()
    var isIncomplete = false
    val handler = (_: Position, _: String) => isIncomplete = true
    currentRun.parsing.withIncompleteHandler(handler) {
      val unit = new CompilationUnit(src)
      val trees = newUnitParser(unit).parse()
      !isIncomplete && !reporter.hasErrors
    }
  } finally reporter.reset()

  def symbolAt(pos: Position): Option[String] = symbolAt(treeAt(pos), pos).map(_.fullName)
  def fqnAt(pos: Position): Option[(FullyQualifiedName, Option[Position])] = symbolAt(treeAt(pos), pos).map { s =>
    val p = if (s.pos.isDefined) Some(s.pos) else None
    (toFqn(s), p)
  }

  def typeAt(pos: Position): Option[String] = typeAt_(pos).map(_.safeToString)
  def completeAt(pos: Position): List[String] = {
    if (pos.source.content(pos.withShift(-1).point) != '.') {
      throw new IllegalArgumentException(pos.showError("completions only allowed immediately following a dot."))
    }

    // ENSIME:TOS mutates the source file if a `.` doesn't have a member
    // following it, because older versions of the compiler produced unreliable
    // trees. However, that doesn't seem to be an issue with modern compilers.
    // Note, however, that if the parser doesn't succeed then our compiler
    // plugin fails to run, which complicates the testing workflow.
    val callback = new Response[List[Member]]
    askTypeCompletion(pos.withShift(-1), callback)
    val members = callback.success

    val bannedOwners: Set[Symbol] = Set(
      definitions.AnyClass,
      definitions.AnyRefClass,
      definitions.ObjectClass,
      rootMirror.getRequiredClass("scala.Predef.any2stringadd"),
      rootMirror.getRequiredClass("scala.Predef.ArrowAssoc"),
      rootMirror.getRequiredClass("scala.Predef.Ensuring")
    )
    val unbannedFields: Set[String] = Set(
      "hashCode",
      "toString"
    )
    def onlyParamType(s: Symbol): Option[Symbol] = s.paramss match {
      case List(List(only)) => Some(only.tpe.typeSymbol)
      case _ => None
    }
    def isBanned(s: Symbol) = s.name.startsWith("unary_") ||
      (s.owner.isPrimitiveValueClass && s.name.isOperatorName && Some(s.owner) != onlyParamType(s)) ||
      (bannedOwners(s.owner) && !unbannedFields(s.decodedName))

    ask { () =>
      members
        .collect {
          case m: TypeMember => m
        }
        .filter(m => m.accessible && (m.sym.isTerm || m.sym.isModule))
        .filterNot(m => m.sym.isConstructor || m.sym.isSynthetic || m.sym.isDeprecated || isBanned(m.sym))
        .map { m =>
          val name = m.sym.decodedName.trim

          // note that scala 2.12 changed the format to include colons before
          // the return type. We might want to clean up older versions to align.
          var signature = m.sym.typeSignatureIn(m.prefix).safeToString.trim

          if (!signature.contains(":")) signature = s": $signature"

          name + signature
        }
    }
      .distinct
      .sorted(Ordering.comparatorToOrdering(String.CASE_INSENSITIVE_ORDER))
  }

  private def treeAt(pos: Position): Tree = {
    val callback = new interactive.Response[Tree]
    askTypeAt(pos, callback)
    callback.success
  }

  private def symbolAt(tree: Tree, pos: Position): Option[Symbol] = ask { () =>
    val wannabes = tree match {
      case Import(expr, selectors) =>
        if (expr.pos.includes(pos)) {
          @tailrec def locate(p: Position, inExpr: Tree): Symbol = inExpr match {
            case Select(qualifier, name) =>
              if (qualifier.pos.includes(p)) locate(p, qualifier)
              else inExpr.symbol
            case tree => tree.symbol
          }
          List(locate(pos, expr))
        } else {
          selectors
            .filter(_.namePos <= pos.point)
            .sortBy(_.namePos)
            .lastOption map { sel =>
              val tpe = stabilizedType(expr)
              List(tpe.member(sel.name), tpe.member(sel.name.toTypeName))
            } getOrElse Nil
        }
      case st: SymTree if st.symbol ne null =>
        List(tree.symbol)
      case Literal(Constant(TypeRef(_, sym, _))) =>
        // special cased by the compiler for classOf[type]
        List(sym)
      // these commented matches are from ENSIME:TOS but it's unclear what they
      // are picking out. By all means add tests and re-enable if these are
      // considered useful.
      //
      // case Annotated(atp, _) =>
      //   List(atp.symbol)
      // case lit: Literal =>
      //   List(lit.tpe.typeSymbol)
      case other =>
        Nil
    }
    wannabes.filterNot(_.isError).find(_.exists).map(dealias(_))
  }

  private def dealias(sym: Symbol): Symbol = {
    if (sym.isAliasType) dealias(sym.info.dealias.typeSymbol)
    else sym
  }

  private def typeAt_(pos: Position): Option[Type] = {
    val t = treeAt(pos)
    val tree = t match {
      case Import(_, _) => return symbolAt(t, pos).map(_.tpe)
      case Select(qualifier, name) if t.tpe == ErrorType => qualifier
      case t: ImplDef if t.impl != null => t.impl
      case t: ValOrDefDef if t.tpt != null => t.tpt
      case t: ValOrDefDef if t.rhs != null => t.rhs
      case otherTree => otherTree
    }
    Option(tree.tpe)
  }

}

object Compiler {
  type Settings = scala.tools.nsc.Settings

  def mkSettings(args: Array[String], err: PrintStream): (Settings, List[String]) = {
    val settings = new Settings(err.println)
    val (_, params) = settings.processArguments(args.toList, processAll = false)

    settings.Ymacroexpand.value = settings.MacroExpand.Discard

    (settings, params)
  }

  def cp(s: Settings): List[URI] = {
    val resolver = new PathResolver(s)
    resolver.result.asURLs.map(_.toURI).toList
  }

  implicit class RichResponse[A](resp: interactive.Response[A]) {
    def success: A = resp.get match {
      case Left(a) => a
      case Right(err) => throw err
    }
  }

  // offset or col:row
  def pos(f: SourceFile, s: String): Position = s.split(":").toList match {
    case o :: Nil => Position.offset(f, o.toInt)
    case row :: col :: Nil => Position.offset(f, Launcher.toOffset(row.toInt, col.toInt, f.content))
    case other => throw new IllegalArgumentException(s"not a valid position '$other'")
  }

  // a cache of the last successful compiler that has the given ctx loaded.
  private val cached: AtomicReference[Option[(String, List[(Long, File)], Compiler)]] = new AtomicReference(None)
  def withCompiler[A](settings: Settings, target: String, ctx: List[String])(f: (SourceFile, Compiler) => A): A = {
    val key = (
      settings.toConciseString,
      ctx.sorted.map(new File(_)).filter(_.isFile).map(f => (f.lastModified, f))
    )

    val (compiler, deps) = cached.getAndSet(None) match {
      case Some((k_1, k_2, warm)) if k_1 == key._1 && k_2 == key._2 =>
        (warm, Nil)
      case other =>
        other match {
          case Some((_, _, old)) => old.askShutdown()
          case None =>
        }
        (new Compiler(settings), ctx)
    }

    val target_ :: deps_ = (target :: deps).map { s =>
      val p = Path.of(s).toAbsolutePath
      new BatchSourceFile(
        new VirtualFile(p.toString),
        Files.readString(p).toCharArray
      )
    }

    // A parser error tends to halt the compilation, so it makes sense to load
    // the good deps first, then the current file, ignoring the bad files
    // because they never result in symbols or types anyway.
    //
    // If there was a way to calculate the dependency graph, we'd do that here,
    // but we can't, because it's Scala. A consequence of this is that we
    // probably won't see any new symbols defined in the working set if the
    // working set depends on a (broken) active file.
    val (good, bad) = deps_.partition { src =>
      compiler.parses(src)
    }

    // if (bad.nonEmpty) {
    //   val names = bad.map(_.file.path)
    //   System.err.println(s"Unparseable working set: $names")
    // }

    val callback = new interactive.Response[Unit]
    compiler.askReload(good :+ target_, callback)
    callback.success

    try {
      val result = f(target_, compiler)
      // removeUnitOf doesn't remove the symbols that were loaded from this file
      // so the compiler can get stale after a while. E.g. if you define a
      // symbol and then delete it, future cached invocations of this compiler
      // will still see that symbol. Opening up another file or touching a
      // dependency is enough to flush the cache but we might want to have
      // another escape hatch.
      Future {
        // backgrounded, to avoid delay
        compiler.removeUnitOf(target_)
        if (!cached.compareAndSet(None, Some((key._1, key._2, compiler))))
          compiler.askShutdown()
      }(ExecutionContext.global)
      result
    } catch {
      case t: Throwable =>
        compiler.askShutdown()
        throw t
    }
  }

}

object NilReporter extends Reporter {
  override def info0(pos: Position, msg: String, severity: Severity, force: Boolean): Unit = ()
    // System.err.println(s"[$severity] ${pos.source}:${pos.line}:${pos.column} $msg")
}

// converts some compiler symbols into java descriptors
trait SymbolToFqn { self: interactive.Global =>
  // not strictly true, but it's better than nothing...
  private def packageName(sym: Symbol): ClassName =
    ClassName(
      sym.ownerChain.takeWhile(!_.isRootSymbol).reverse.map(_.encodedName),
      "package$"
    )

  private def className(sym: Symbol): ClassName = {
    val (packages, nested) =
      sym.ownerChain.takeWhile(!_.isRootSymbol).reverse.span(_.hasPackageFlag)
    val pkg     = packages.map(_.encodedName)
    val name    = nested.map(_.encodedName).mkString("$")
    val postfix = if (nested.last.isModuleOrModuleClass && !nested.last.isJava) "$" else ""

    ClassName(pkg, name + postfix)
  }

  private def descriptorType(t: Type): DescriptorType = {
    val c = className(t.dealias.erasure.typeSymbol).normalise
    if (c == ClassName.ScalaArray) {
      ArrayDescriptor(descriptorType(t.typeArgs.head))
    } else c
  }

  private def methodName(sym: MethodSymbol): MethodName = {
    val owner = sym.enclClass
    val clazz = className(owner)
    val name  = sym.encodedName

    val descriptor: Descriptor = {
      val params = sym.paramLists.flatten.map { p =>
        descriptorType(p.tpe)
      }
      val ret = descriptorType(sym.returnType)
      Descriptor(params, ret)
    }

    MethodName(clazz, name, descriptor.descriptorString)
  }

  private def fieldName(sym: Symbol): FieldName = {
    val clazz = className(sym.owner)
    val name  = sym.encodedName
    FieldName(clazz, name)
  }

  def toFqn(sym: Symbol): FullyQualifiedName = ask { () =>
    sym match {
      case p if sym.hasPackageFlag => packageName(sym)
      case ts: TypeSymbol          => className(ts).normalise
      case ms: ModuleSymbol        => className(ms)
      case ms: MethodSymbol        => methodName(ms)
      case ts: TermSymbol          => fieldName(ts)
    }
  }

}

// Local Variables:
// scala-compile-suggestion: "./tests.sh"
// End:

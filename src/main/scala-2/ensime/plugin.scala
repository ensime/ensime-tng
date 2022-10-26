// Copyright: Sam Halliday
// License: GPLv3+
package ensime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption.{ APPEND, CREATE, TRUNCATE_EXISTING }

import scala.reflect.internal.util.Position
import scala.tools.nsc._
import scala.tools.nsc.reporters._
import scala.util.control.NonFatal

class ReporterHack(
  val underlying: Reporter,
  val out: File
) extends Reporter {
  // scala 2.13 introduces a doReport that is nicer to use, but this is the only
  // way to do it that works for all 2.x
  override def info0(pos: Position, msg: String, severity: Severity, force: Boolean): Unit = {
    severity.id match {
      case 0 => underlying.echo(pos, msg)
      case 1 => underlying.warning(pos, msg)
      case 2 => underlying.error(pos, msg)
    }
    withDiagnosticsFile {
      val file = new File(pos.source.file.path)
      if (file.isFile) {
        // NULL character used as separator
        Files.writeString(out.toPath(), s"$severity\n$file\n${pos.line}\n${pos.column}\n$msg\n\u0000", APPEND, CREATE)
      }
    }
  }
  override def comment(pos: Position, msg: String): Unit = underlying.comment(pos, msg)
  override def hasErrors: Boolean = super.hasErrors || underlying.hasErrors
  override def reset(): Unit = {
    underlying.reset()
    withDiagnosticsFile {
      Files.writeString(out.toPath, "", CREATE, TRUNCATE_EXISTING)
    }
  }
  override def flush(): Unit = underlying.flush()

  private def withDiagnosticsFile(f: => Unit): Unit = Launcher.synchronized {
    try {
      out.getParentFile().mkdirs()
      f
    } catch {
      case NonFatal(_) =>
    }
  }
}

final class Plugin(override val global: Global) extends plugins.Plugin {
  override val description: String = "extracts build information for use by ENSIME"
  override val name: String = Launcher.pluginName

  val isInteractive: Boolean = global.isInstanceOf[tools.nsc.interactive.Global]
  val isScaladoc: Boolean = global.isInstanceOf[tools.nsc.doc.ScaladocGlobal]
  val isBatch: Boolean = !isInteractive && !isScaladoc

  private lazy val (launcher: String, tmpdir: File) = Launcher.mkScript(global.settings.userSetSettings.toList.flatMap(_.unparse))

  if (isBatch) {
    // there's no way to be sure that this is the only time this is done without
    // tracking references, we might be wrapping many times. Experiments with
    // sbt suggest that every invocation of `compile` creates a fresh Reporter
    // so it is safe to do this. There may be issues with other build tools.
    //
    // using the tmpdir ties it to the build hash (transient), not the
    // individual files (semi-permanent).
    // System.err.println(s"ENSIME installing reporter hack over existing ${System.identityHashCode(global.reporter)}")
    global.reporter = new ReporterHack(global.reporter, new File(tmpdir, "diagnostics.log"))
    //wobble
    // System.err.println(s"ENSIME reporter is now ${System.identityHashCode(global.reporter)}")
  }

  // `outputDirs.outputs` would have been better, since it contains the source
  // to target mapping, but doesn't seem to be used by sbt. `outdirs` doesn't
  // seem to be used anymore.
  lazy val userTarget: Option[File] = global.settings.outputDirs.getSingleOutput.map { out =>
    new File(out.path).getCanonicalFile()
  }

  // the only way to extract the list of source files is to run a phase and
  // extract the source files from each callback. There's no way to know their
  // common directory base, since it is not necessarilly passed to the cli.
  private def phase = new plugins.PluginComponent {
    override val global = Plugin.this.global
    override val phaseName = Plugin.this.name
    override final def newPhase(prev: Phase): Phase =
      new StdPhase(prev) {
        override def apply(unit: global.CompilationUnit): Unit = userTarget match {
          case None =>
          case Some(target) =>
            val file = new File(unit.source.file.path)
            if (file.isFile)
              Launcher.write(file, target, launcher)
        }
      }
    override val runsAfter: List[String] = "parser" :: Nil
    override val runsBefore: List[String] = "namer" :: Nil
  }

  // A note on sbt support: `.sbt` files are manually compiled in
  // sbt.compiler.Eval which, roughly:
  //
  // 1. parses imports
  // 2. parses sbt top level forms
  // 3. starts the scala compiler from "namer" until completion
  //
  // This has several implications for ENSIME.
  //
  // Firstly, we don't see `.sbt` files unless our compiler plugin is attached
  // after the "namer" phase.
  //
  // Secondly, we cannot interpret the `.sbt` file because it would require us
  // to be using the Eval steps, not starting the regular interactive compiler.
  //
  // Thirdly, we don't seem to have any mechanism to recover the autoimports
  // added by sbt except to extract it from the AST during the compilation and
  // then capture this in the launcher.
  //
  // Either we need to detect `.sbt` files early on and apply the Eval logic
  // (especially the `parse*` methods), or we need to have a compiler plugin
  // that runs after the parser has constructed (invalid) Trees that we can
  // rearrange while retaining source position information.
  //
  // Metals treats `.sbt` files (and others) as ScriptSourceFile inputs which
  // means that each block of text is interpreted as a standalone, throwaway,
  // piece of text. Which presumably gives enough information to infer types
  // whilst incurring many caveats.

  override val components = if (isBatch) List(phase) else Nil
}

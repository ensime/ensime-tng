// Copyright: Sam Halliday
// License: GPLv3+
package ensime

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, OpenOption, Path }
import java.nio.file.StandardOpenOption.{ APPEND, CREATE, TRUNCATE_EXISTING }

import scala.util.control.NonFatal

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.Trees._
import dotty.tools.dotc.config.ScalaSettings
import dotty.tools.dotc.config.Settings.Setting
import dotty.tools.dotc.core.Mode
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.interfaces.Diagnostic.{ ERROR, INFO, WARNING }
import dotty.tools.dotc.parsing.Parser
import dotty.tools.dotc.plugins.{ PluginPhase, StandardPlugin }
import dotty.tools.dotc.reporting.*
import dotty.tools.dotc.typer.TyperPhase
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.io.AbstractFile

class ReporterHack(
  val underlying: Reporter,
  val out: File
) extends Reporter  {

  resetThis()

  private def writeString(p: Path, s: String, opts: OpenOption*): Unit =
    Files.write(p, s.getBytes(StandardCharsets.UTF_8), opts: _*)

  override def flush()(using ctx: Context): Unit = underlying.flush()

  override def doReport(diagnostic: Diagnostic)(using ctx: Context): Unit = {

    underlying.doReport(diagnostic)
    withDiagnosticsFile {
      val pos = diagnostic.pos
      val file = new File(pos.source.file.path)
      if (file.isFile) {
        // NULL character used as separator
        val severity = diagnostic.level match{
          case ERROR => "ERROR"
          case WARNING => "WARNING"
          case INFO => "INFO"
        }

        writeString(out.toPath(), s"$severity\n$file\n${pos.startLine + 1}\n${pos.startColumn + 1}\n${pos.endLine + 1}\n${pos.endColumn + 1}\n${diagnostic.msg}\n\u0000", APPEND, CREATE)
      }
    }
  }

  def resetThis(): Unit = withDiagnosticsFile {
    writeString(out.toPath, "", CREATE, TRUNCATE_EXISTING)
  }

  private def withDiagnosticsFile(f: => Unit): Unit = Launcher.synchronized {
    try {
      out.getParentFile().mkdirs()
      f
    } catch {
      case NonFatal(_) =>
    }
  }
}

class Plugin extends StandardPlugin {
  import Plugin._

  override val description: String = "extracts build information for use by ENSIME"
  override val name: String = "ensime"

  private def phase = new PluginPhase {
    import dotty.tools.dotc.ast.tpd._

    override val phaseName = Plugin.this.name

    override def runOn(units: List[CompilationUnit])(implicit ctx: Context): List[CompilationUnit] = {
      if (!ctx.mode.is(Mode.Interactive)) {


        val target = ctx.settings.outputDir.value.file
        val (launcher, tmpdir) = Launcher.mkScript(ctx.settings.userSetSettings(ctx.settingsState).toList.flatMap(_.unparse))

        ctx.typerState.setReporter(new ReporterHack(ctx.reporter, new File(tmpdir, "diagnostics.log")))
        units.foreach { unit =>
          val file = unit.source.file.file
          if (file.isFile)
            Launcher.write(file, target, launcher)
        }
      }

      super.runOn(units)
    }

    override val runsAfter: Set[String] = Set(Parser.name)
    override val runsBefore: Set[String] = Set(TyperPhase.name)
  }

  override def init(options: List[String]): List[PluginPhase] = phase :: Nil
}

object Plugin {
  // functionality missing in Scala 3... would be good to upstream this
  implicit class UnparseSettings(setting: Setting[_]) {
    def unparse(implicit ctx: Context): List[String] = setting.value match {
      case f: AbstractFile => List(setting.name, f.path)
      case s: String => List(setting.name, s)
      case lst: List[_] => List((setting.name :: lst.map(_.toString)).mkString(":"))
      case i: Int => List(setting.name, i.toString)
      case b: Boolean => List(setting.name)
      case other =>
        System.err.println(s"ENSIME unexpected scalac Setting type ${other.getClass}")
        Nil
    }
  }

}

// Copyright: Sam Halliday
// License: GPLv3+
package ensime

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.Trees._
import dotty.tools.dotc.config.ScalaSettings
import dotty.tools.dotc.config.Settings.Setting
import dotty.tools.dotc.core.Mode
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.parsing.Parser
import dotty.tools.dotc.plugins.{ PluginPhase, StandardPlugin }
import dotty.tools.dotc.typer.TyperPhase
import dotty.tools.io.AbstractFile

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
        val (launcher, _) = Launcher.mkScript(ctx.settings.userSetSettings(ctx.settingsState).toList.flatMap(_.unparse))

        units.foreach { unit =>
          val file = unit.source.file.file
          if (file.isFile)
            Launcher.write(file, target, launcher)
        }
      }

      super.runOn(units)
    }

    // pre-typer plugins are apparently not allowed in releases, just research
    // plugions, but they apparently forgot to disable it :-D
    // https://github.com/lampepfl/dotty/pull/13173
    override val runsAfter: Set[String] = Set(Parser.name)
    override val runsBefore: Set[String] = Set(TyperPhase.name)
  }

  def init(options: List[String]): List[PluginPhase] = phase :: Nil
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

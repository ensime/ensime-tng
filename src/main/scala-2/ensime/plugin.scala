// Copyright: Sam Halliday
// License: GPLv3+
package ensime

import java.io.File

import scala.tools.nsc._

final class Plugin(override val global: Global) extends plugins.Plugin {
  override val description: String = "extracts build information for use by ENSIME"
  override val name: String = Launcher.pluginName

  val isInteractive: Boolean = global.isInstanceOf[tools.nsc.interactive.Global]
  val isScaladoc: Boolean = global.isInstanceOf[tools.nsc.doc.ScaladocGlobal]
  val isEnsime: Boolean = global.isInstanceOf[Compiler]

  private lazy val launcher: String = Launcher.mkScript(global.settings.userSetSettings.toList.flatMap(_.unparse))

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

  override val components = if (!isInteractive && !isScaladoc) List(phase) else Nil
}

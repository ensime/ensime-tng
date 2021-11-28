package ensime

import sbt._
import Keys._

object EnsimePlugin extends AutoPlugin {
  override def trigger = allRequirements

  object autoImport {
    val ensimeJar = taskKey[File]("Location of ENSIME")
  }

  import autoImport._
  override lazy val globalSettings: Seq[Setting[_]] = Seq(
    // perhaps an overreach...
    transitiveClassifiers := Seq("sources")
  )

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    ensimeJar := (Def.taskDyn {
      val jar = file(s"""${sys.props("user.home")}/.cache/ensime/lib/ensime-${scalaVersion.value}.jar""")
      if (jar.isFile) Def.task {
        // make sure the user always has sources if ENSIME is enabled
        val _ = updateClassifiers.value
        jar
      } else Def.task {
        streams.value.log.warn(s"ENSIME not found. Try\n\n      sbt ++${scalaVersion.value}! install\n\nin the ensime-tng repo.")
        jar
      }
    }).value,

    scalacOptions ++= {
      val jar = ensimeJar.value
      if (jar.isFile) Seq(s"-Xplugin:${jar.getAbsolutePath}")
      else Seq()
    },

    clean := {
      val _ = clean.value
      // clears the reverse lookup of source files to the target
      file(s"""${sys.props("user.home")}/.cache/ensime${target.value}""").delete(): Unit
    }
  )
}

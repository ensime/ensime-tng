ThisBuild / scalaVersion := "2.13.7"

name := "simple"

scalacOptions ++= {
  if (scalaVersion.value.startsWith("2.11.")) List("-target:jvm-1.8") else Nil
}

// override the default to use the jar we just built
ensimeJar := file(s"""${sys.props("user.dir")}/../../target/scala-${scalaVersion.value}/ensime.jar""")

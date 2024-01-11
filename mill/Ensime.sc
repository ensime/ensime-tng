import mill._
import mill.scalalib._

// To install this Mill plugin, add
//
// ```
// import $file.plugins.Ensime
// import Ensime.EnsimeModule
// 
// object ... extends EnsimeModule
// ```
//
// to your build.sc

trait EnsimeModule extends ScalaModule { module =>

  def ensimeWanted = T.input {
    os.exists(os.home / ".cache" / "ensime")
  }

  def ensimeJar : T[Option[PathRef]] = T {
    val jar = os.home / ".cache" / "ensime" / "lib" / s"""ensime-${scalaVersion()}.jar"""
    if (os.isFile(jar) && ensimeWanted()) {
      // make sure the user always has sources if ENSIME is enabled
      val fetchTask = fetchDepSources()
      fetchTask()
      Some(PathRef(jar))
    } else {
      if (ensimeWanted()){
        T.ctx().log.error(s"ENSIME not found. Try\n\n      sbt ++${scalaVersion()}! install\n\nin the ensime-tng repo.")
      }
      None
    }
  }

  override def scalacOptions = T {
    super.scalacOptions() ++ {ensimeJar() match {
      case Some(jar) => Seq(s"-Xplugin:${jar.path.toIO.getAbsolutePath}")
      case None => Seq()
    }}
  }

  private def fetchDepSources: mill.define.Task[() => Unit] = T.task {
    import coursier._
    import coursier.util._
    import scala.concurrent.ExecutionContext.Implicits.global

    val repos = module.repositoriesTask()
    val allIvyDeps = module.transitiveIvyDeps() ++ module.transitiveCompileIvyDeps()
    val withSources = Resolution(
      // for mill 0.10.x use...
      // allIvyDeps.map(module.resolveCoursierDependency())
      allIvyDeps.map(_.dep)
        .toList
        .map(d =>
          d.withAttributes(
            d.attributes.withClassifier(coursier.Classifier("sources"))
          )
        )
        .toSeq
    )
    () => {
      val fetch = ResolutionProcess.fetch(repos, coursier.cache.Cache.default.fetch)
      val _ = withSources.process.run(fetch).unsafeRun()
    }
  }
}

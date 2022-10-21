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

  def ensimeJar : T[Option[PathRef]] = T.input {
    val jar = os.home / ".cache" / "ensime" / "lib" / s"""ensime-${scalaVersion()}.jar"""
    val ensimeWanted = os.exists(os.home / ".cache" / "ensime")
    if (os.isFile(jar) && ensimeWanted) {
      // make sure the user always has sources if ENSIME is enabled
      val fetchTask = fetchDepSources()
      fetchTask()
      Some(PathRef(jar))
    } else {
      if (ensimeWanted){
        T.ctx.log.error(s"ENSIME not found. Try\n\n      sbt ++${scalaVersion()}! install\n\nin the ensime-tng repo.")
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
    val coursierDeps = allIvyDeps.map(module.resolveCoursierDependency()).toList
    val withSources = Resolution(
      coursierDeps
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

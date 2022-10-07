// Copyright: Sam Halliday
// License: GPLv3+
package ensime

import java.io.{ File, PrintStream }
import java.net.URI
import java.nio.file.{ Files, Path, Paths, FileSystem, FileSystems, FileSystemAlreadyExistsException }
import java.util.concurrent.Executors
import java.util.{ Timer, TimerTask }
import java.util.regex.Pattern

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters._

import org.objectweb.asm.{ ClassReader, Opcodes, ClassVisitor, FieldVisitor, MethodVisitor, Label }

import com.facebook.nailgun.{ NGContext, NGServer }

import Compiler._

object Main {

  private var heartbeat_ = System.currentTimeMillis()
  private var shutdowner = false
  private def heartbeat(ng: NGServer): Unit = synchronized {
    heartbeat_ = System.currentTimeMillis()

    val timeout = 60 * 60 * 1000
    if (!shutdowner) {
      shutdowner = true
      val checker = new TimerTask {
        def run(): Unit = if (System.currentTimeMillis() > (heartbeat_ + timeout)) ng.signalExit()
      }
      new Timer("shutdowner", true).scheduleAtFixedRate(checker, timeout, 1000)
    }
  }

  def nailMain(ng: NGContext): Unit = try {
    heartbeat(ng.getNGServer())
    run(ng.getArgs(), ng.out, ng.err)
  } catch {
    case t: Throwable =>
      t.printStackTrace(ng.err)
      ng.exit(1)
  }

  def main(args: Array[String]): Unit = {
    run(args, System.out, System.err)
  }

  def run(args: Array[String], out: PrintStream, err: PrintStream): Unit = {
    val (settings, params) = mkSettings(args, err)

    val results: List[String] = params match {
      case "type" :: target :: p :: ctx => withCompiler(settings, target, ctx) { (f, c) =>
        c.typeAt(pos(f, p)).toList }
      case "complete" :: target :: p :: ctx => withCompiler(settings, target, ctx) { (f, c) =>
        c.completeAt(pos(f, p)).map(_
          .replaceAll("(?-i)\\b(?:[a-z]+[.]){2,}", "")
          .replaceAll("(?-i)\\b(?:x|evidence)\\$[0-9]+:", "_:")) }
      case "search" :: q :: Nil => search(cp(settings), q)
      case "source" :: target :: p :: ctx => withCompiler(settings, target, ctx) { (f, c) =>
        c.fqnAt(pos(f, p)) match {
          case Some((_, Some(pos))) =>
            if (pos.source == f) List(s":${pos.line}")
            else List(s"${pos.source.file.canonicalPath}:${pos.line}")

          case Some((sym, None)) =>
            source(cp(settings), sym, err).flatMap { case (file, line) => file.editorStringWithLine(line) }

          case None => Nil
        }
      }

      // lower value / debugging apis
      case "symbol" :: target :: p :: ctx => withCompiler(settings, target, ctx) { (f, c) =>
        c.symbolAt(pos(f, p)).toList }
      case "fqn" :: target :: p :: ctx => withCompiler(settings, target, ctx) { (f, c) =>
        c.fqnAt(pos(f, p)).map(_._1).map(_.internalString).toList }
      case "binary" :: target :: p :: ctx => withCompiler(settings, target, ctx) { (f, c) =>
        c.fqnAt(pos(f, p)).toList.map(_._1)
          .flatMap(binary(cp(settings), _))
          .flatMap(_.editorString) }

      case Nil => Nil // shortcut in nailgun to exit cleanly
      case other => throw new IllegalArgumentException(s"unexpected arguments $other")
    }

    results.foreach(out.println)
  }

  // searches the classpath for class files and applies the users filter,
  // returning the Scala version of the class name.
  //
  // We don't support members because there's no way to do a quick scan based on
  // class name. We'd need to pre-index for that.
  def search(cp: List[URI], q: String): List[String] = {
    // could allow capital letter based fuzzy search, this only does exact
    // matching (would need editor support)
    val p = Pattern.compile(".*[$/]\\Q" + q + "\\E[$]?$")

    // could have a user-specified banlist
    def pick(c: String): Boolean = p.matcher(c).matches()

    classes(cp)(pick)
      .filter { case (name, file, bytes) =>
        import Opcodes._

        val reader = new ClassReader(bytes)
        val access = reader.getAccess

        // https://asm.ow2.io/javadoc/org/objectweb/asm/Opcodes.html
        (access & ACC_PUBLIC) != 0 &&
          (access & ACC_DEPRECATED) == 0 &&
          (access & ACC_SYNTHETIC) == 0
      }
      .map { case (f, _, _) => f.scalaString }
      .distinct
      .sorted
  }

  def source(cp: List[URI], q: FullyQualifiedName, err: PrintStream): List[(LocalFile, Int)] = {
    binary_(cp, q) match {
      case None =>
        //System.err.println(s"didn't match ${q} with a binary")
        Nil
      case Some((cn, f, bytes)) =>
        import ClassReader._

        val reader = new ClassReader(bytes)
        val sigs = new SignatureReader(cn)
        reader.accept(sigs, SKIP_FRAMES) // CODE is needed for line numbers

        for {
          filename <- Option(sigs.filename).toList
          line <- q match {
            case m: MemberName => sigs.members.get(m).map(_.getOrElse(0)).toList
            case _ =>
              val lines = sigs.members.values.flatten
              // if an interface or class has no methods, then we won't see any
              // members and can't extract a line number, so 0 is the best we
              // can do
              List(if (lines.isEmpty) 0 else lines.min)
          }
          resolved = attachedSourceRoot(f, filename)
          _ = if (resolved.isEmpty) {
            // this line probably means that we need more heuristics to find source jars
            err.println(s"[WARNING] resolved binary to $f which references $filename, which was not found.")
          }
          src <- resolved
        } yield (src, line)
    }
  }

  private def attachedSourceRoot(bin: LocalFile, filename: String): List[LocalFile] = {
    def find(walk: =>java.util.stream.Stream[Path]): List[Path] = {
      def tester(s: String) = walk
        .filter(Files.isRegularFile(_))
        .filter { e => e.toString.endsWith(s) }
        .iterator.asScala.toList

      // we could filter these guesses if we inspected the Trees from our parses
      // method, and then looked for qualified classes, methods and fields. But
      // is it really worth it...

      val mostlikely = tester(bin.file.getParent.toString.stripPrefix("/modules/") + "/" + filename)
      if (mostlikely.nonEmpty) mostlikely
      else tester("/" + filename)
    }

    bin.container match {
      case Left(parent) =>
        val lookup = Files.readAllLines(Path.of(Launcher.cacheDir + parent.toString))
        find(lookup.stream.map(Path.of(_)))
          .map(e => LocalFile(Left(Path.of("/")), e))

      case Right(container) => {
        container.getScheme match {
          case "file" =>
            val jar = new File(container)
            // there is no standard convention for the location of the sources
            // jar or zip file, so we apply heuristics. This also requires the
            // user to have downloaded the sources in the first place.
            val candidates = sbtBootHack(jar) ++ List(
              new File(jar.getParent, jar.getName.replaceAll("[.]jar$", "-sources.jar"))
            )
            candidates.find(_.exists())
          case "jrt" =>
            Some(new File(sys.props("java.home"), "/lib/src.zip"))
          case _ => None
        }
      }.toList.filter(_.isFile).flatMap { f =>
        withJarFileSystem(f.toURI) { root =>
          find(Files.walk(root))
        }.map(e => LocalFile(Right(f.toURI), e))
      }
    }
  }

  // sbt re-uses its own scala library and compiler to avoid downloading for the
  // user's project when versions match, but the sources are downloaded
  // elsewhere.
  def sbtBootHack(f: File): List[File] = {
    val m = Pattern.compile(".*/[.]sbt/boot/scala-([^/]+)/lib/([^/.]+)[.]jar$").matcher(f.toString)
    if (!m.find()) Nil else {
      val version = m.group(1)
      val artifact = m.group(2)
      List(new File(s"""${sys.props("user.home")}/.cache/coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-library/${version}/${artifact}-${version}-sources.jar"""))
    }
  }

  def binary(cp: List[URI], q: FullyQualifiedName): Option[LocalFile] = binary_(cp, q).map(_._2)
  private def binary_(cp: List[URI], q: FullyQualifiedName): Option[(ClassName, LocalFile, Array[Byte])] = {
    val c = q.className.internalString

    classes(cp)(cn => c.contains(cn) && c == s"L${cn};").headOption
  }

  // lists all classes on the classpath (functionality not provided by scalac)
  // that match a predicate (to reduce the footprint). this is probably going to
  // be quite expensive for very large projects.
  def classes(cp: List[URI])(predicate: String => Boolean): List[(ClassName, LocalFile, Array[Byte])] = {
    def pathToClass(name: String): Option[String] =
      if (!name.endsWith(".class")) None
      else Some(
        name.stripSuffix(".class")
          .replaceFirst("^/modules/[^/]+/", "") // jrt:/ cleanup
          .replaceFirst("^/", "") // jar file cleanup
      )

    // lists all the files contained underneath this URI, making sure to close
    // resources if appropriate
    def files(uri: URI) = {
      // forces evaluation so that we can clean up after calling this method,
      // and also applies the predicate as early as possible to keep memory
      // usage down.
      def files_(path: Path, container: Option[URI]) = {
        if (!Files.exists(path)) Nil
        else {
          Files.walk(path)
            .filter(Files.isRegularFile(_))
            .map[(Path, Option[String])] { (f: Path) =>
              if (container.isEmpty) (f, pathToClass(path.relativize(f).toString))
              else (f, pathToClass(f.toString))
            }
            .filter(_._2.exists(predicate))
            .map[(ClassName, LocalFile, Array[Byte])] {
              // loading the class data is very expensive so we only do it after
              // applying the class name predicate.
              case (f, cn) =>
                val container_ = container.toRight(path)
                (ClassName.fromInternal(cn.get), LocalFile(container_, f), Files.readAllBytes(f))
            }
            .iterator.asScala.toList
        }
      }

      if (uri.toString.startsWith("jrt:")) {
        val fs = FileSystems.getFileSystem(uri)
        fs.getRootDirectories.asScala.toList.flatMap(files_(_, Some(uri)))
      } else if (uri.getPath.endsWith(".jar")) {
        withJarFileSystem(uri)(files_(_, Some(uri)))
      } else {
        files_(Paths.get(uri), None)
      }
    }

    import ExecutionContext.Implicits.global
    val work = Future.sequence {
      cp.map(uri => Future(files(uri))(io_pool))
    }
    Await.result(work, Duration.Inf).flatten
  }

  // for I/O bound work (scanning classpath files)
  private val io_pool: ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(4))

  def withJarFileSystem[A](jar: URI)(f: Path => A): A = {
    val env = new java.util.HashMap[String, Any]
    val uri = URI.create("jar:" + jar.toString)
    val fs =
      try FileSystems.newFileSystem(uri, env, null)
      catch {
        case _: FileSystemAlreadyExistsException => FileSystems.getFileSystem(uri)
      }
    f(fs.getRootDirectories.asScala.toList.head)
    // we don't close the filesystem because it can be accessed again
    // concurrently and we have no simple way of tracking that
  }
}

// a Left container is a parent directory on the same filesystem, used to relativize.
// a Right container is a parent on a different filesystem, e.g. a jar file.
case class LocalFile(container: Either[Path, URI], file: Path) {
  def editorString: Option[String] = container match {
    case Left(_) => Some(file.toAbsolutePath.toString)
    case Right(container) if container.getScheme == "file" => Some(container.getPath + "!" + file.toString)
    // editors can't render the jrt stuff, so don't show it
    // case Right(container) if container.getScheme == "jrt" => Some("jrt:/" + file.toString)
    case _ => None
  }
  def editorStringWithLine(line: Int): Option[String] = editorString.map(s => s"$s:$line")
}

final class SignatureReader(cn: ClassName) extends ClassVisitor(SignatureReader.ASM) {
  import Opcodes._

  var members: Map[MemberName, Option[Int]] = Map.empty
  var filename: String = null

  override def visitSource(filename: String, debug: String): Unit = {
    this.filename = filename
  }

  override def visitField(access: Int, name: String, desc: String, signature: String, value: AnyRef): FieldVisitor = {
    if ((access & ACC_PRIVATE) != 0) return null

    members = members + (FieldName(cn, name) -> None)
    null
  }

  override def visitMethod(access: Int, name: String, desc: String, signature: String, exceptions: Array[String]): MethodVisitor = {
    if ((access & ACC_PRIVATE) != 0) return null

    val member = MethodName(cn, name, desc)
    members += (member -> None)
    new MethodVisitor(SignatureReader.ASM) {
      override def visitLineNumber(line: Int, start: Label): Unit = {
        if (members(member) == None)
          members += (member -> Some(line))
      }
    }
  }
}
object SignatureReader {
  val ASM = Opcodes.ASM9
}

// Local Variables:
// scala-compile-suggestion: "./tests.sh"
// End:

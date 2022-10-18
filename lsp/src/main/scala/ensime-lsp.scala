package ensime

import java.io.File
import java.net.URI
import java.nio.file.{ Files, Path }
import java.nio.file.StandardOpenOption.{ CREATE, TRUNCATE_EXISTING }
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.{ Timer, TimerTask }
import java.util.concurrent.CompletableFuture
import java.util.zip.ZipFile

import scala.concurrent.Future
import scala.jdk.FutureConverters._
import scala.jdk.CollectionConverters._
import scala.sys.process._

import org.eclipse.lsp4j._
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services._
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.messages.{ Either => LspEither }
import org.eclipse.lsp4j.jsonrpc.services._

import java.util.{ List => JList }

object EnsimeLsp {
  def main(args: Array[String]): Unit = {
    System.err.println("Starting ENSIME LSP")
    val server = new EnsimeLsp
    val launcher = LSPLauncher.createServerLauncher(server, System.in, System.out)
    val client = launcher.getRemoteProxy
    server.connect(client)
    launcher.startListening()
  }

  // be nice and shut down automatically if the user doesn't talk to us in a while
  @volatile private var heartbeat_ = System.currentTimeMillis()
  @volatile private var shutdowner = false
  private def heartbeat(): Unit = synchronized {
    heartbeat_ = System.currentTimeMillis()

    val timeout = 60 * 60 * 1000L
    if (!shutdowner) {
      shutdowner = true
      val checker = new TimerTask {
        def run(): Unit = if (System.currentTimeMillis() > (heartbeat_ + timeout)) {
          System.err.println("Shutting down ENSIME LSP due to inactivity")
          sys.exit(0)
        }
      }
      new Timer("shutdowner", true).scheduleAtFixedRate(checker, timeout, 1000)
    }
  }
}

// see https://github.com/eclipse/lsp4j/issues/321 regarding annotations
class EnsimeLsp extends LanguageServer with LanguageClientAware {
  private def async[A](f: => A): CompletableFuture[A] = {
    EnsimeLsp.heartbeat()

    import scala.concurrent.ExecutionContext.Implicits.global

    Future(f).asJava.toCompletableFuture
  }

  override def initialize(params: InitializeParams): CompletableFuture[InitializeResult] = async {
    val res = new InitializeResult

    val capabilities = new ServerCapabilities

    // - TODO import / search for class (unknown which LSP endpoint this is, possibly code action)

    // this is inefficient, consider swapping to Incremental and applying diffs
    // as they are received by didChange.
    capabilities.setTextDocumentSync(TextDocumentSyncKind.Full)

    capabilities.setHoverProvider(true)
    capabilities.setDefinitionProvider(true)
    capabilities.setCompletionProvider(
      new CompletionOptions(false, List(".").asJava)
    )

    val serverinfo = new ServerInfo
    serverinfo.setName("ENSIME")
    serverinfo.setVersion("TNG")

    res.setCapabilities(capabilities)
    res.setServerInfo(serverinfo)
    res
  }

  // contains the list of files that have been opened along with their content
  // as last communicated by the client (it may match what is on disk). It is
  // expensive to maintain this, since we can get full-file updates on every
  // character typed, but LSP doesn't give us reliable alternatives.
  @volatile private var openFiles: Map[File, String] = Map()

  private def uriToFile(s: String): Option[File] = {
    val uri = new URI(s)
    if (uri.getScheme == "file")
      Some(new File(uri.getPath))
    else
      None
  }

  private def withDoc[A](uri: String)(f: File => A): A = uriToFile(uri) match {
    case Some(file) => f(file)
    case None => throw new UnsupportedOperationException(s"${uri} is not a file")
  }

  // uses the ensime HASH to compute which open files are part of the current
  // active set (i.e. files which are part of the same artefact). A consequence
  // of this is that files which are changed but not part of the active set will
  // be ignored, which is an intentional design decision (the user is expected
  // to compile files all dependencies). The response does not contain the
  // input.
  private def activeSet(focus: File): Set[File] = ensimeFile(focus) match {
    case None => Set()
    case Some(focusEnsime) =>
      val hash = ensimeHash(focusEnsime)
      // System.err.println(s"CALCULATING ACTIVE SET FOR $hash")
      val others = (openFiles.keySet - focus).flatMap(ensimeFile).filter {
        e => ensimeHash(e) == hash
      }
      others
  }

  private val cacheDir = sys.props("user.home") + "/.cache/ensime/"
  private def ensimeFile(focus: File): Option[File] = {
    val probe = new File(s"${cacheDir}${focus.getAbsolutePath}")
    if (probe.isFile) Some(probe)
    else None
  }
  private def ensimeHash(ensime: File): String = {
    Files.readAllLines(ensime.toPath).asScala.find(_.startsWith("HASH=")).map(_.drop(5)) match {
      case Some(hash) => hash
      case None => throw new IllegalStateException(s"ENSIME file $ensime is corrupted")
    }
  }

  // like ensimeFile but also tries to use a "last best known" file which covers
  // the corner case of new files that haven't been compiled yet.
  private def ensimeExe(focus: File): File = {
    val exe = ensimeFile(focus)
    if (exe.isDefined) {
      lastEnsimeExe = exe
      exe.get
    } else {
      lastEnsimeExe.getOrElse(throw new IllegalStateException("ENSIME is not available, blah blah instructions to set it up"))
    }
  }
  @volatile private var lastEnsimeExe: Option[File] = None

  // shared between all ensime instances, this could be made cleaner by adding HASH
  private val tmp_prefix: String = s"/tmp/${sys.props("user.name")}/ensime/"

  // assumes that File is present in openFiles and will return the file as-is if
  // the in-memory representation matches what is on disk. Otherwise, a
  // temporary file is created (having the same name) and returned. Note that
  // this will break the ability to link compiler reporter messages back to
  // their original filenames.
  private def tmpIfDifferent(f: File): File = {
    val inmemory = openFiles.get(f).getOrElse(throw new IllegalStateException(s"expected $f to exist in ${openFiles.keySet}"))
    val disk = Files.readString(f.toPath)

    // maybe need to do some whitespace normalisation...
    if (inmemory == disk) f
    else {
      // we never clean these up
      val tmp = new File(tmp_prefix + f.getAbsolutePath)
      // System.err.println(s"writing temp file for $f as $tmp")
      tmp.getParentFile.mkdirs()
      Files.writeString(tmp.toPath, inmemory, CREATE, TRUNCATE_EXISTING)
      tmp
    }
  }

  private def ensime(mode: String, f: File, pos: Position): String = {
    val exe = ensimeExe(f)
    val target = tmpIfDifferent(f)
    val active = activeSet(f).map(tmpIfDifferent(_))
    val context = active.mkString(" ")

    val stderr = new StringBuilder
    val processLogger = ProcessLogger(_ => (), stderr.append(_))

    val command = s"$exe $mode $target ${pos.getLine}:${pos.getCharacter} $context"
    System.err.println(command)

    try command.!!
    finally {
      if (stderr.nonEmpty)
        System.err.println(stderr.toString)
    }
  }

  override def getTextDocumentService(): TextDocumentService = new TextDocumentService {
    // we only care about monitoring the active set
    override def didClose(p: DidCloseTextDocumentParams): Unit = withDoc(p.getTextDocument.getUri) { f =>
      // System.err.println(s"CLOSED $f")
      openFiles -= f
    }
    override def didOpen(p: DidOpenTextDocumentParams): Unit = withDoc(p.getTextDocument.getUri) { f =>
      // System.err.println(s"OPENED $f")
      val content = p.getTextDocument.getText
      openFiles = openFiles + (f -> content)
    }
    override def didChange(p: DidChangeTextDocumentParams): Unit = withDoc(p.getTextDocument.getUri) { f =>
      // System.err.println(s"CHANGED $f")
      val content = p.getContentChanges.get(0).getText // Full means this is not a diff
      openFiles = openFiles + (f -> content)
    }

    override def didSave(p: DidSaveTextDocumentParams): Unit = ()

    // https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_completion
    override def completion(params: CompletionParams): CompletableFuture[LspEither[JList[CompletionItem], CompletionList]] = async {
      withDoc(params.getTextDocument.getUri) { f =>
        val pos = params.getPosition
        // editors can sometimes send completion requests in places other than
        // the defined trigger characters, so be a little protective against
        // that.
        val before = new Position(pos.getLine, pos.getCharacter - 1)
        val charBefore = openFiles(f).split('\n')(before.getLine)(before.getCharacter)
        val completions = if (charBefore != '.') {
          Nil
        } else {
          ensime("complete", f, pos).split("\n").toList.map { sig =>
            val item = new CompletionItem
            item.setLabel(sig)
            item.setInsertTextFormat(InsertTextFormat.Snippet)

            val parsed = SigParser.parse(sig).stripImplicit
            val snippet = SigParser.snippet(parsed)
            item.setInsertText(snippet)

            if (parsed.isInfix) {
              // infix operators replace the dot with a space
              val edit = new TextEdit(new Range(before, pos), " ")
              item.setAdditionalTextEdits(List(edit).asJava)
            }

            item
          }
        }

        LspEither.forRight(new CompletionList(completions.asJava))
      }
    }

    override def hover(params: HoverParams): CompletableFuture[Hover] = async {
      withDoc(params.getTextDocument.getUri) { f =>
        val output = ensime("type", f, params.getPosition)
        val content = new MarkupContent("plaintext", output)
        new Hover(content)
      }
    }

    override def definition(params: DefinitionParams) = async {
      withDoc(params.getTextDocument.getUri) { f =>

        val defns = ensime("source", f, params.getPosition).split("\n").toList.map { resp =>
          val parts = resp.split(":")
          val file = if (parts(0).isEmpty) f.toString else parts(0).replace(tmp_prefix, "")
          val pos = new Position(0 max (parts(1).toInt - 1), 0)
          val range = new Range(pos, pos)

          val cleaned =
            if (file.contains("!")) extractZipEntry(file)
            else s"file://$file"
          // System.err.println(s"FOUND $cleaned")

          new Location(cleaned, range)
        }

        LspEither.forLeft(defns.asJava)
      }
    }

    // given an ensime style entry "/foo/bar.jar!/baz/gaz.scala" extract the
    // entry into the tmp directory and return a File path to that entry for a
    // text editor to open naturally.
    private def extractZipEntry(uri: String): String = {
      val parts = uri.split("!")
      val name = parts(0)
      val archive = new ZipFile(name)
      val path = parts(1).stripPrefix("/")
      val out = Path.of(tmp_prefix + name + "/" + path)
      out.getParent().toFile().mkdirs()
      // System.err.println(s"EXTRACTING $uri to $out")
      try {
        val entry = archive.getEntry(path) // recently checked, shouldn't be null
        val in = archive.getInputStream(entry)
        Files.copy(in, out, REPLACE_EXISTING)
        out.toString
      } finally {
        archive.close()
      }
    }
  }

  override def getWorkspaceService(): WorkspaceService = new WorkspaceService {
    // ignore client notifications, ensime does it's own monitoring
    def didChangeConfiguration(p: DidChangeConfigurationParams): Unit = ()
    def didChangeWatchedFiles(p: DidChangeWatchedFilesParams): Unit = ()
  }

  override def getNotebookDocumentService(): NotebookDocumentService = new NotebookDocumentService {
    override def didChange(p: DidChangeNotebookDocumentParams): Unit = ()
    override def didClose(p: DidCloseNotebookDocumentParams): Unit = ()
    override def didOpen(p: DidOpenNotebookDocumentParams): Unit = ()
    override def didSave(p: DidSaveNotebookDocumentParams): Unit = ()
  }

  override def shutdown(): CompletableFuture[Object] = async { new Object }
  override def exit(): Unit = sys.exit(0)

  // recommended by
  // https://github.com/eclipse/lsp4j/blob/main/documentation/README.md
  override def connect(client: LanguageClient): Unit = ()
}

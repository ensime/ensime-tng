package ensime

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

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
    System.err.println("Starting ENSIME LSP (stderr)")
    val server = new EnsimeLsp
    val launcher = LSPLauncher.createServerLauncher(server, System.in, System.out)
    val client = launcher.getRemoteProxy()
    server.connect(client)
    launcher.startListening()
  }
}

// see https://github.com/eclipse/lsp4j/issues/321 regarding annotations
class EnsimeLsp extends LanguageServer with LanguageClientAware {
  private def async[A](f: => A): CompletableFuture[A] = {
    import scala.concurrent.ExecutionContext.Implicits.global

    Future(f).asJava.toCompletableFuture
  }

  override def initialize(params: InitializeParams): CompletableFuture[InitializeResult] = async {
    val res = new InitializeResult

    val capabilities = new ServerCapabilities

    // TODO populate with capabilities for these features, and implement below.
    //
    // - dot completion (TextDocumentCompletion)
    // - infer type (TextDocumentHover)
    // - import / search for class
    // - jump to source (DeclarationProvider)

    // this is inefficient, consider swapping to Incremental and applying diffs
    // as they are received by didChange.
    capabilities.setTextDocumentSync(TextDocumentSyncKind.Full)

    capabilities.setHoverProvider(true)

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
  // input. None means that the focus does not have an ensime file.
  private def activeSet(focus: File): Option[Set[File]] = ensimeFile(focus) match {
    case None => None
    case Some(focusEnsime) =>
      val hash = ensimeHash(focusEnsime)
      System.err.println(s"CALCULATING ACTIVE SET FOR $hash")
      val others = (openFiles.keySet - focus).flatMap(ensimeFile).filter {
        e => ensimeHash(e) == hash
      }
      Some(others)
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

  override def getTextDocumentService(): TextDocumentService = new TextDocumentService {
    // we only care about monitoring the active set
    override def didClose(p: DidCloseTextDocumentParams): Unit = withDoc(p.getTextDocument.getUri) { f =>
      System.err.println(s"CLOSED $f")
      openFiles -= f
    }
    override def didOpen(p: DidOpenTextDocumentParams): Unit = withDoc(p.getTextDocument.getUri) { f =>
      System.err.println(s"OPENED $f")
      val content = p.getTextDocument.getText
      openFiles = openFiles + (f -> content)
    }
    override def didChange(p: DidChangeTextDocumentParams): Unit = withDoc(p.getTextDocument.getUri) { f =>
      System.err.println(s"CHANGED $f")
      val content = p.getContentChanges.get(0).getText // Full means this is not a diff
      openFiles = openFiles + (f -> content)
    }

    override def didSave(p: DidSaveTextDocumentParams): Unit = ()

    override def completion(params: CompletionParams): CompletableFuture[LspEither[JList[CompletionItem], CompletionList]] = async {
      withDoc(params.getTextDocument.getUri) { f =>
        System.err.println(s"COMPLETE $f at ${params.getPosition}, active set is ${activeSet(f)}")

        // TODO implement completion
        // TODO insert/replace stuff
        // TODO label should be the full signature, insertText should be the name only
        // TODO port over special cases from Emacs (e.g. removing the dot for symbols)
        // TODO how to do parameter templates
        val completion = new CompletionList(
          List(new CompletionItem("wibble")).asJava
        )

        LspEither.forRight(completion)
      }
    }

    override def hover(params: HoverParams): CompletableFuture[Hover] = async {
      withDoc(params.getTextDocument.getUri()) { f =>

        // TODO convert position into offset
        val pos = (params.getPosition.getLine, params.getPosition.getCharacter)

        System.err.println(s"HOVER $f at ${pos}, active set is ${activeSet(f)}")

        val exe = ensimeExe(f)
        //s"$exe type $f "


        // FIXME implement as type at point (possibly symbol + type)
        val content = LspEither.forLeft[String, MarkedString]("hover info goes here")
        new Hover(content)
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

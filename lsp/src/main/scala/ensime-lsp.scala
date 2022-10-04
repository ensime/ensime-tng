package ensime

import java.util.concurrent.CompletableFuture

import scala.concurrent.Future
import scala.jdk.FutureConverters._
import scala.jdk.CollectionConverters._

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
    val tracer = new java.io.PrintWriter(System.err, true)
    val launcher = LSPLauncher.createServerLauncher(server, System.in, System.out, true, tracer)
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

  @volatile private var activeUris: Set[String] = Set()

  override def getTextDocumentService(): TextDocumentService = new TextDocumentService {
    // we only care about monitoring the active set
    override def didClose(p: DidCloseTextDocumentParams): Unit = {
      activeUris -= p.getTextDocument().getUri()
    }
    override def didOpen(p: DidOpenTextDocumentParams): Unit = {
      activeUris += p.getTextDocument().getUri()
    }

    override def didChange(p: DidChangeTextDocumentParams): Unit = ()
    override def didSave(p: DidSaveTextDocumentParams): Unit = ()

    override def completion(pos: CompletionParams): CompletableFuture[LspEither[JList[CompletionItem], CompletionList]] = async {
      System.err.println(s"REQUESTED TO COMPLETE $pos, active set is $activeUris")

      // FIXME implement completion
      // TODO insert/replace stuff
      // TODO label should be the full signature, insertText should be the name only
      // TODO port over special cases from Emacs (e.g. removing the dot for symbols)
      // TODO how to do parameter templates
      val completion = new CompletionList(
        List(new CompletionItem("wibble")).asJava
      )

      LspEither.forRight(completion)
    }

    override def hover(params: HoverParams): CompletableFuture[Hover] = async {
      System.err.println(s"REQUESTED TO HOVER $params, active set is $activeUris")

      // FIXME implement as type at point (possibly symbol + type)
      val content = LspEither.forLeft[String, MarkedString]("hover info goes here")
      new Hover(content)
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

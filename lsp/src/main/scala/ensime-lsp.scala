package ensime

import java.util.concurrent.CompletableFuture

import scala.concurrent.Future
import scala.jdk.FutureConverters._
import scala.jdk.CollectionConverters._

import org.eclipse.lsp4j._
import org.eclipse.lsp4j.services._
import org.eclipse.lsp4j.jsonrpc.messages.{ Either => LspEither }

import java.util.{ List => JList }

class EnsimeLsp extends LanguageServer {
  private def async[A](f: => A): CompletableFuture[A] = {
    import scala.concurrent.ExecutionContext.Implicits.global

    Future(f).asJava.toCompletableFuture
  }

  def initialize(params: InitializeParams): CompletableFuture[InitializeResult] = async {
    val res = new InitializeResult

    val capabilities = new ServerCapabilities

    // TODO populate with capabilities for these features, and implement below.
    //
    // - dot completion (TextDocumentCompletion)
    // - infer type (TextDocumentHover)
    // - import / search for class
    // - jump to source (DeclarationProvider)

    val completer = new CompletionOptions
    completer.setTriggerCharacters(List(".").asJava)
    capabilities.setCompletionProvider(completer)

    val serverinfo = new ServerInfo
    serverinfo.setName("ENSIME")
    serverinfo.setVersion("TNG")

    res.setCapabilities(capabilities)
    res.setServerInfo(serverinfo)
    res
  }

  @volatile private var activeUris: Set[String] = Set()

  def getTextDocumentService(): TextDocumentService = new TextDocumentService {
    // we only care about monitoring the active set
    def didClose(p: DidCloseTextDocumentParams): Unit = {
      activeUris -= p.getTextDocument().getUri()
    }
    def didOpen(p: DidOpenTextDocumentParams): Unit = {
      activeUris += p.getTextDocument().getUri()
    }

    def didChange(p: DidChangeTextDocumentParams): Unit = ()
    def didSave(p: DidSaveTextDocumentParams): Unit = ()

    override def completion(pos: CompletionParams): CompletableFuture[LspEither[JList[CompletionItem], CompletionList]] = async {
      println(s"REQUESTED TO COMPLETE $pos, active set is $activeUris")

      // FIXME implement completion

      ???
    }
  }

  def getWorkspaceService(): WorkspaceService = new WorkspaceService {
    // ignore client notifications, ensime does it's own monitoring
    def didChangeConfiguration(p: DidChangeConfigurationParams): Unit = ()
    def didChangeWatchedFiles(p: DidChangeWatchedFilesParams): Unit = ()
  }

  def shutdown(): CompletableFuture[Object] = async { new Object }
  def exit(): Unit = sys.exit(0)
}

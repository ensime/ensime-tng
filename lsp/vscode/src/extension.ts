import * as fs from 'fs';
import * as os from 'os';
import * as vscode from 'vscode';
import {
	ExecuteCommandRequest,
	LanguageClient,
	LanguageClientOptions,
	RevealOutputChannelOn,
	ServerOptions,
	TransportKind
} from 'vscode-languageclient';

let client: LanguageClient;

export function activate(context: vscode.ExtensionContext) {

	const ensime_java = vscode.workspace.getConfiguration().get<string>('ensime.java') || "java";
	const ensime_javaargs = vscode.workspace.getConfiguration().get<string>('ensime.javaargs') || "";
	const ensime_lspjar = vscode.workspace.getConfiguration().get<string>('ensime.lspjar')?.replace("~", os.homedir) || "";
	const runArgs: string[] = ensime_javaargs.split(" ").concat(["-jar", ensime_lspjar]);

	if (!fs.existsSync(ensime_lspjar)) {
		vscode.window.showErrorMessage(`ENSIME: not available (${ensime_lspjar}). Visit https://ensime.github.io/ to download and install.`);
	}

	if (!fs.existsSync("~/.cache/ensime/~".replace("~", os.homedir).replace("~", os.homedir))) {
		vscode.window.showInformationMessage("Welcome to ENSIME! Reload your projects after installing the ENSIME plugins, and compile at least once.");
	}

	// would be good to remind the user to add a key binding for Import Symbol if none is set.

	const serverOptions: ServerOptions = {
		run: { command: ensime_java, transport: TransportKind.stdio, args: runArgs },
		// debug is hacked, we don't actually do anything differently...
		debug: { command: ensime_java, transport: TransportKind.stdio, args: runArgs },
	};

	const clientOptions: LanguageClientOptions = {
		documentSelector: [
			{ scheme: 'file', language: 'scala' },
		],
	};

	client = new LanguageClient(
		'ensime-lsp',
		'ENSIME Language Server',
		serverOptions,
		clientOptions
	);

	vscode.commands.registerTextEditorCommand(
		'ensime.import',
		(editor: vscode.TextEditor, edit: vscode.TextEditorEdit, args: any[]) => {
			client.sendRequest(ExecuteCommandRequest.type, {
				command: 'ensime.import',
				arguments: [editor.document.uri.toString(), editor.selection.active.line, editor.selection.active.character]
			});
		}
	);

	client.start();
}

export function deactivate(): Thenable<void> | undefined {
	if (!client) {
		return undefined;
	}
	return client.stop();
}

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
        const os = require("os");
	const runArgs: string[] = ["-jar", "~/.cache/ensime/lib/ensime-lsp.jar".replace("~", os.homedir)];
	const debugArgs: string[] = runArgs;
	const javaPath = "java" ;

        // TODO allow the user to specify the java command
        // TODO some UX to tell the user if jars are missing
        // TODO graphical icon
        // TODO publish to the extension store

	const serverOptions: ServerOptions = {
		run: { command: javaPath, transport: TransportKind.stdio, args: runArgs },
		debug: { command: javaPath, transport: TransportKind.stdio, args: debugArgs }
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
          `ensime.import`,
          (textEditor: vscode.TextEditor, edit: vscode.TextEditorEdit, args: any[]) => {
            client.sendRequest(ExecuteCommandRequest.type, {
              command: `ensime.import`
            });
          }
        );

// TODO do we need to send the uri and position or is it included already?
//  const pos = editor.selection.active;
//  return {
//    textDocument: { uri: editor.document.uri.toString() },
//    position: { line: pos.line, character: pos.character },
//  };

	client.start();
}

export function deactivate(): Thenable<void> | undefined {
	if (!client) {
		return undefined;
	}
	return client.stop();
}

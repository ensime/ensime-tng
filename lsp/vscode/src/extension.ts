import * as vscode from 'vscode';
import {
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

        // TODO custom uri support for jar/zip definitions
        // TODO allow the user to specify the java command
        // TODO allow the user to specify the ensime jar
        // TODO some UX to tell the user if the jar (or compiler plugin dir) is missing
        // TODO bundle the ensime jar by default
        // TODO add an icon
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

	client.start();
}

export function deactivate(): Thenable<void> | undefined {
	if (!client) {
		return undefined;
	}
	return client.stop();
}

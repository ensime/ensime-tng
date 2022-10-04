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
	const runArgs: string[] = ["-jar", "~/.ensime/ensime-lsp.jar".replace("~", os.homedir)];
	const debugArgs: string[] = runArgs;
	const javaPath = "java" ;

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

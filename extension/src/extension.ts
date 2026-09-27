import * as vscode from 'vscode';
import { SCHEME } from './common';
import { Connection } from './connection';
import { Controls } from './controls';
import { StudioFileSystem } from './fileSystem';
import { LuaDiagnostics } from './lua/diagnostics';
import { registerLanguageFeatures } from './lua/providers';
import { registerSearch } from './search';
import { ComputerStatus } from './status';
import { ComputerTerminals } from './terminal/terminals';

export function activate(context: vscode.ExtensionContext): void {
  const connection = new Connection(socketUrl(context.extensionUri), sessionPageUrl(context.extensionUri));
  const status = new ComputerStatus(connection);
  const fileSystem = new StudioFileSystem(connection);
  const terminals = new ComputerTerminals(connection, status);
  const controls = new Controls(connection, status, terminals);

  context.subscriptions.push(
    connection,
    status,
    fileSystem,
    terminals,
    controls,
    new LuaDiagnostics(),
    vscode.workspace.registerFileSystemProvider(SCHEME, fileSystem, { isCaseSensitive: true }),
    ...registerSearch(fileSystem),
    ...registerLanguageFeatures(),
    vscode.workspace.onDidOpenTextDocument(ensureLuaLanguage)
  );

  vscode.workspace.textDocuments.forEach(ensureLuaLanguage);
  connection.connect();

  if (vscode.workspace.getConfiguration('ccstudio.terminal').get<boolean>('showOnStartup', true)) {
    const subscription = connection.onDidChangeState(state => {
      if (state !== 'open') return;
      subscription.dispose();
      terminals.show(true);
    });
    context.subscriptions.push(subscription);
  }
}

export function deactivate(): void {}

function socketUrl(extensionUri: vscode.Uri): string {
  const scheme = extensionUri.scheme === 'https' ? 'wss' : 'ws';
  const path = extensionUri.path.replace(/\/extension\/?$/, '/ws');
  return `${scheme}://${extensionUri.authority}${path}`;
}

function sessionPageUrl(extensionUri: vscode.Uri): string {
  return `${extensionUri.scheme}://${extensionUri.authority}${extensionUri.path.replace(/\/extension\/?$/, '/')}`;
}

function ensureLuaLanguage(document: vscode.TextDocument): void {
  if (document.uri.scheme !== SCHEME || document.languageId !== 'plaintext') return;
  if (!vscode.workspace.getConfiguration('ccstudio.lua').get<boolean>('extensionlessFilesAsLua', true)) return;
  const name = document.uri.path.slice(document.uri.path.lastIndexOf('/') + 1);
  if (!name.includes('.')) vscode.languages.setTextDocumentLanguage(document, 'lua');
}

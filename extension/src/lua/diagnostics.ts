import * as vscode from 'vscode';
import { parse } from 'luaparse';

const DELAY = 350;

interface LuaSyntaxError extends Error {
  line?: number;
  column?: number;
  index?: number;
}

export class LuaDiagnostics implements vscode.Disposable {
  private readonly collection = vscode.languages.createDiagnosticCollection('lua');
  private readonly timers = new Map<string, ReturnType<typeof setTimeout>>();
  private readonly subscriptions: vscode.Disposable[];

  constructor() {
    this.subscriptions = [
      this.collection,
      vscode.workspace.onDidOpenTextDocument(document => this.schedule(document, 0)),
      vscode.workspace.onDidChangeTextDocument(event => this.schedule(event.document, DELAY)),
      vscode.workspace.onDidCloseTextDocument(document => this.clear(document)),
      vscode.workspace.onDidChangeConfiguration(event => {
        if (event.affectsConfiguration('ccstudio.lua.diagnostics')) this.refreshAll();
      })
    ];
    this.refreshAll();
  }

  dispose(): void {
    for (const timer of this.timers.values()) clearTimeout(timer);
    for (const subscription of this.subscriptions) subscription.dispose();
  }

  private refreshAll(): void {
    for (const document of vscode.workspace.textDocuments) this.schedule(document, 0);
  }

  private schedule(document: vscode.TextDocument, delay: number): void {
    const key = document.uri.toString();
    clearTimeout(this.timers.get(key));
    this.timers.set(key, setTimeout(() => {
      this.timers.delete(key);
      this.check(document);
    }, delay));
  }

  private clear(document: vscode.TextDocument): void {
    const key = document.uri.toString();
    clearTimeout(this.timers.get(key));
    this.timers.delete(key);
    this.collection.delete(document.uri);
  }

  private check(document: vscode.TextDocument): void {
    if (document.isClosed) return;
    if (document.languageId !== 'lua' || !vscode.workspace.getConfiguration('ccstudio.lua').get<boolean>('diagnostics', true)) {
      this.collection.delete(document.uri);
      return;
    }
    try {
      parse(document.getText(), { luaVersion: '5.2', comments: false, scope: false, locations: false, ranges: false });
      this.collection.delete(document.uri);
    } catch (error) {
      const syntaxError = error as LuaSyntaxError;
      if (typeof syntaxError.index !== 'number') {
        this.collection.delete(document.uri);
        return;
      }
      const position = document.positionAt(syntaxError.index);
      const range = document.getWordRangeAtPosition(position) ?? new vscode.Range(position, position.translate(0, 1));
      const message = syntaxError.message.replace(/^\[\d+:\d+\]\s*/, '');
      const diagnostic = new vscode.Diagnostic(range, message, vscode.DiagnosticSeverity.Error);
      diagnostic.source = 'lua';
      this.collection.set(document.uri, [diagnostic]);
    }
  }
}

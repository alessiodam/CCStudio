import * as vscode from 'vscode';
import { api, documentation, Entry, formatParam, formatSignature, membersOf, signatures } from './api';
import { aliasesOf, callContext, EXPRESSION, resolveExpression, scanLine } from './analysis';

const SELECTOR: vscode.DocumentSelector = [{ language: 'lua' }];

const KEYWORDS = [
  'and', 'break', 'do', 'else', 'elseif', 'end', 'false', 'for', 'function', 'goto', 'if', 'in',
  'local', 'nil', 'not', 'or', 'repeat', 'return', 'then', 'true', 'until', 'while'
];

export function registerLanguageFeatures(): vscode.Disposable[] {
  return [
    vscode.languages.registerCompletionItemProvider(SELECTOR, { provideCompletionItems }, '.', ':', '"', "'"),
    vscode.languages.registerHoverProvider(SELECTOR, { provideHover }),
    vscode.languages.registerSignatureHelpProvider(SELECTOR, { provideSignatureHelp }, { triggerCharacters: ['(', ','], retriggerCharacters: [','] }),
    vscode.languages.registerDocumentSymbolProvider(SELECTOR, { provideDocumentSymbols }),
    vscode.languages.registerDefinitionProvider(SELECTOR, { provideDefinition })
  ];
}

function provideCompletionItems(document: vscode.TextDocument, position: vscode.Position): vscode.CompletionItem[] | undefined {
  const line = document.lineAt(position.line).text.slice(0, position.character);
  const state = scanLine(line);
  if (state.comment) return undefined;

  if (state.quote) {
    const before = line.slice(0, state.stringStart);
    const range = new vscode.Range(position.line, state.stringStart + 1, position.line, position.character);
    if (/os\s*\.\s*pullEvent(?:Raw)?\s*\(\s*$/.test(before)) return eventItems(range);
    if (/require\s*\(?\s*$/.test(before)) return namedItems(api.modules, vscode.CompletionItemKind.Module, range);
    if (/peripheral\s*\.\s*(?:find|hasType)\s*\((?:[^,]*,)?\s*$/.test(before)) return namedItems(api.peripherals, vscode.CompletionItemKind.Interface, range);
    return undefined;
  }

  const aliases = aliasesOf(document);
  const member = /([A-Za-z_]\w*(?:\s*[.:]\s*[A-Za-z_]\w*)*)\s*([.:])\s*(\w*)$/.exec(line);
  if (member) {
    const members = membersOf(resolveExpression(member[1], aliases));
    if (!members) return undefined;
    return Object.entries(members).map(([name, entry]) => completionItem(name, entry, `${member[1]}${member[2]}${name}`));
  }

  if (!/(?:^|[^\w.:])\w*$/.test(line)) return undefined;
  const items: vscode.CompletionItem[] = [];
  for (const [name, entry] of Object.entries(api.globals)) items.push(completionItem(name, entry, name));
  for (const [name, entry] of aliases) {
    const item = completionItem(name, entry, name);
    item.detail = `local ${name}: ${entry.kind === 'module' ? 'module' : 'object'}`;
    items.push(item);
  }
  for (const keyword of KEYWORDS) {
    const item = new vscode.CompletionItem(keyword, vscode.CompletionItemKind.Keyword);
    item.sortText = '~' + keyword;
    items.push(item);
  }
  return items;
}

function completionItem(name: string, entry: Entry, qualified: string): vscode.CompletionItem {
  const item = new vscode.CompletionItem(name, completionKind(entry));
  item.detail = formatSignature(qualified, entry);
  const docs = documentation(entry);
  if (docs) item.documentation = new vscode.MarkdownString(docs);
  if (entry.deprecated) item.tags = [vscode.CompletionItemTag.Deprecated];
  if (entry.kind === 'function') {
    item.command = { command: 'editor.action.triggerParameterHints', title: 'Signature help' };
  }
  return item;
}

function completionKind(entry: Entry): vscode.CompletionItemKind {
  switch (entry.kind) {
    case 'function':
      return entry.method ? vscode.CompletionItemKind.Method : vscode.CompletionItemKind.Function;
    case 'module':
      return vscode.CompletionItemKind.Module;
    case 'type':
      return vscode.CompletionItemKind.Class;
    default:
      return entry.value !== undefined ? vscode.CompletionItemKind.Constant : vscode.CompletionItemKind.Field;
  }
}

function eventItems(range: vscode.Range): vscode.CompletionItem[] {
  return Object.entries(api.events).map(([name, event]) => {
    const item = new vscode.CompletionItem(name, vscode.CompletionItemKind.Event);
    item.range = range;
    item.detail = `event ${name}(${event.params.map(formatParam).join(', ')})`;
    item.documentation = new vscode.MarkdownString(event.doc);
    return item;
  });
}

function namedItems(entries: Record<string, Entry>, kind: vscode.CompletionItemKind, range: vscode.Range): vscode.CompletionItem[] {
  return Object.entries(entries).map(([name, entry]) => {
    const item = new vscode.CompletionItem(name, kind);
    item.range = range;
    if (entry.doc) item.documentation = new vscode.MarkdownString(entry.doc);
    return item;
  });
}

function provideHover(document: vscode.TextDocument, position: vscode.Position): vscode.Hover | undefined {
  const line = document.lineAt(position.line).text;
  const state = scanLine(line.slice(0, position.character));
  if (state.comment) return undefined;

  if (state.quote) {
    const word = document.getWordRangeAtPosition(position, /[\w.]+/);
    if (!word) return undefined;
    const name = document.getText(word);
    const event = api.events[name];
    if (event && /pullEvent|queueEvent/.test(line)) {
      const markdown = new vscode.MarkdownString();
      markdown.appendCodeblock(`event ${name}(${event.params.map(formatParam).join(', ')})`, 'lua');
      markdown.appendMarkdown(event.doc);
      return new vscode.Hover(markdown, word);
    }
    const module = api.modules[name];
    return module ? hover(name, module, word) : undefined;
  }

  const range = document.getWordRangeAtPosition(position, EXPRESSION);
  if (!range) return undefined;
  const text = document.getText(range);
  const offset = document.offsetAt(position) - document.offsetAt(range.start);
  const end = offset + (/^\w*/.exec(text.slice(offset))?.[0].length ?? 0);
  const expression = text.slice(0, end);
  const entry = resolveExpression(expression, aliasesOf(document));
  if (!entry) return undefined;
  return hover(expression.replace(/\s+/g, ''), entry, new vscode.Range(range.start, document.positionAt(document.offsetAt(range.start) + end)));
}

function hover(name: string, entry: Entry, range: vscode.Range): vscode.Hover {
  const markdown = new vscode.MarkdownString();
  for (const overload of entry.kind === 'function' ? signatures(entry) : [undefined]) {
    markdown.appendCodeblock(formatSignature(name, entry, overload), 'lua');
  }
  const docs = documentation(entry);
  if (docs) markdown.appendMarkdown(docs);
  return new vscode.Hover(markdown, range);
}

function provideSignatureHelp(document: vscode.TextDocument, position: vscode.Position): vscode.SignatureHelp | undefined {
  const context = callContext(document, position);
  if (!context) return undefined;
  const entry = resolveExpression(context.callee, aliasesOf(document));
  if (!entry || entry.kind !== 'function') return undefined;

  const name = context.callee.replace(/\s+/g, '');
  const help = new vscode.SignatureHelp();
  help.signatures = signatures(entry).map(overload => {
    const signature = new vscode.SignatureInformation(formatSignature(name, entry, overload), entry.doc ? new vscode.MarkdownString(entry.doc) : undefined);
    signature.parameters = overload.params.map(param => new vscode.ParameterInformation(formatParam(param), param.doc ? new vscode.MarkdownString(param.doc) : undefined));
    return signature;
  });
  help.activeSignature = Math.max(0, help.signatures.findIndex(signature => signature.parameters.length > context.argument));
  const params = help.signatures[help.activeSignature].parameters;
  const variadic = params.length > 0 && String(params[params.length - 1].label).startsWith('...');
  help.activeParameter = variadic ? Math.min(context.argument, params.length - 1) : context.argument;
  return help;
}

function provideDocumentSymbols(document: vscode.TextDocument): vscode.DocumentSymbol[] {
  const symbols: vscode.DocumentSymbol[] = [];
  const patterns: [RegExp, vscode.SymbolKind][] = [
    [/^\s*(?:local\s+)?function\s+([A-Za-z_][\w.:]*)\s*\(/, vscode.SymbolKind.Function],
    [/^\s*(?:local\s+)?([A-Za-z_][\w.]*)\s*=\s*function\s*\(/, vscode.SymbolKind.Function],
    [/^local\s+([A-Za-z_]\w*)\s*=/, vscode.SymbolKind.Variable]
  ];
  for (let line = 0; line < document.lineCount; line++) {
    const text = document.lineAt(line).text;
    for (const [pattern, kind] of patterns) {
      const match = pattern.exec(text);
      if (!match) continue;
      const start = text.indexOf(match[1], match.index);
      const name = match[1];
      const symbolKind = kind === vscode.SymbolKind.Function && name.includes(':') ? vscode.SymbolKind.Method : kind;
      symbols.push(new vscode.DocumentSymbol(name, '', symbolKind, document.lineAt(line).range, new vscode.Range(line, start, line, start + name.length)));
      break;
    }
  }
  return symbols;
}

function provideDefinition(document: vscode.TextDocument, position: vscode.Position): vscode.Location | undefined {
  const range = document.getWordRangeAtPosition(position, /[A-Za-z_]\w*/);
  if (!range) return undefined;
  const name = document.getText(range);
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const pattern = new RegExp(`(?:\\bfunction\\s+(?:[\\w.]+[.:])?|\\blocal\\s+(?:function\\s+)?(?:[\\w\\s,]*,\\s*)?)(${escaped})\\b`);
  let best: vscode.Location | undefined;
  for (let line = 0; line < document.lineCount; line++) {
    const text = document.lineAt(line).text;
    const match = pattern.exec(text);
    if (!match) continue;
    const start = match.index + match[0].length - name.length;
    const location = new vscode.Location(document.uri, new vscode.Range(line, start, line, start + name.length));
    if (line > position.line) return best ?? location;
    best = location;
  }
  return best;
}

import * as vscode from 'vscode';
import { api, Entry, membersOf, typeEntry } from './api';

export const EXPRESSION = /[A-Za-z_]\w*(?:\s*[.:]\s*[A-Za-z_]\w*)*/;

const ALIAS_PATTERNS: [RegExp, (match: RegExpExecArray, aliases: Map<string, Entry>) => Entry | undefined][] = [
  [
    /(?:^|[\s;])(?:local\s+)?([A-Za-z_]\w*)\s*=\s*([A-Za-z_]\w*(?:\s*[.:]\s*[A-Za-z_]\w*)*)\s*(\(|\{|"|')?/gm,
    (match, aliases) => {
      const target = resolveExpression(match[2], aliases);
      if (!target) return undefined;
      if (match[3]) return target.kind === 'function' ? typeEntry(target.returnType) : undefined;
      return target;
    }
  ],
  [
    /(?:^|[\s;])(?:local\s+)?([A-Za-z_]\w*)\s*=\s*require\s*\(?\s*["']([\w.]+)["']/gm,
    match => api.modules[match[2]]
  ],
  [
    /(?:^|[\s;])(?:local\s+)?([A-Za-z_]\w*)\s*=\s*peripheral\s*\.\s*find\s*\(\s*["']([\w:]+)["']/gm,
    match => api.peripherals[match[2].replace(/^\w+:/, '')]
  ]
];

const cache = new WeakMap<vscode.TextDocument, { version: number; aliases: Map<string, Entry> }>();

export function aliasesOf(document: vscode.TextDocument): Map<string, Entry> {
  const cached = cache.get(document);
  if (cached && cached.version === document.version) return cached.aliases;

  const text = document.getText();
  const aliases = new Map<string, Entry>();
  for (const [pattern, resolve] of ALIAS_PATTERNS) {
    pattern.lastIndex = 0;
    let match: RegExpExecArray | null;
    while ((match = pattern.exec(text)) !== null) {
      const entry = resolve(match, aliases);
      if (entry && !api.globals[match[1]]) aliases.set(match[1], entry);
    }
  }
  cache.set(document, { version: document.version, aliases });
  return aliases;
}

export function resolveExpression(expression: string, aliases: Map<string, Entry>): Entry | undefined {
  const parts = expression.split(/\s*[.:]\s*/);
  let entry: Entry | undefined = aliases.get(parts[0]) ?? api.globals[parts[0]];
  for (let i = 1; i < parts.length && entry; i++) {
    entry = membersOf(entry)?.[parts[i]];
  }
  return entry;
}

export interface LineState {
  comment: boolean;
  quote: string | undefined;
  stringStart: number;
}

export function scanLine(text: string): LineState {
  let quote: string | undefined;
  let stringStart = -1;
  for (let i = 0; i < text.length; i++) {
    const char = text[i];
    if (quote) {
      if (char === '\\') {
        i++;
      } else if (char === quote) {
        quote = undefined;
      }
    } else if (char === '"' || char === "'") {
      quote = char;
      stringStart = i;
    } else if (char === '-' && text[i + 1] === '-') {
      return { comment: true, quote: undefined, stringStart: -1 };
    }
  }
  return { comment: false, quote, stringStart };
}

export interface CallContext {
  callee: string;
  argument: number;
}

export function callContext(document: vscode.TextDocument, position: vscode.Position): CallContext | undefined {
  const end = document.offsetAt(position);
  const start = Math.max(0, end - 4000);
  const text = document.getText(new vscode.Range(document.positionAt(start), position));

  let depth = 0;
  let argument = 0;
  let quote: string | undefined;
  for (let i = text.length - 1; i >= 0; i--) {
    const char = text[i];
    if (quote) {
      if (char === quote && text[i - 1] !== '\\') quote = undefined;
      continue;
    }
    if (char === '"' || char === "'") {
      quote = char;
    } else if (char === ')' || char === '}' || char === ']') {
      depth++;
    } else if (char === '{' || char === '[') {
      if (depth === 0) return undefined;
      depth--;
    } else if (char === '(') {
      if (depth > 0) {
        depth--;
        continue;
      }
      const callee = /([A-Za-z_]\w*(?:\s*[.:]\s*[A-Za-z_]\w*)*)\s*$/.exec(text.slice(0, i));
      return callee ? { callee: callee[1], argument } : undefined;
    } else if (char === ',' && depth === 0) {
      argument++;
    } else if (char === '\n' && /^\s*(local|function|end|if|for|while|return)\b/.test(text.slice(i + 1))) {
      return undefined;
    }
  }
  return undefined;
}

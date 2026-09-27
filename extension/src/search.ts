import * as vscode from 'vscode';
import { SCHEME, computerUri } from './common';
import { StudioFileSystem } from './fileSystem';

const DEFAULT_MAX_FILE_SIZE = 4 * 1024 * 1024;

export function registerSearch(fileSystem: StudioFileSystem): vscode.Disposable[] {
  const decoder = new TextDecoder();

  const fileSearch: vscode.FileSearchProvider = {
    async provideFileSearchResults(query, options, token) {
      const matches = globFilter(options);
      const results: vscode.Uri[] = [];
      for (const path of await fileSystem.listFiles()) {
        if (token.isCancellationRequested) break;
        if (!matches(path) || !fuzzyMatch(path, query.pattern)) continue;
        results.push(computerUri(path));
        if (options.maxResults && results.length >= options.maxResults) break;
      }
      return results;
    }
  };

  const textSearch: vscode.TextSearchProvider = {
    async provideTextSearchResults(query, options, progress, token) {
      const pattern = searchPattern(query);
      const matches = globFilter(options);
      const maxFileSize = options.maxFileSize ?? DEFAULT_MAX_FILE_SIZE;
      let count = 0;

      for (const path of await fileSystem.listFiles()) {
        if (token.isCancellationRequested) break;
        if (!matches(path)) continue;

        const uri = computerUri(path);
        let bytes: Uint8Array;
        try {
          bytes = await fileSystem.readFile(uri);
        } catch {
          continue;
        }
        if (bytes.length > maxFileSize || isBinary(bytes)) continue;

        const lines = decoder.decode(bytes).split(/\r?\n/);
        for (let line = 0; line < lines.length; line++) {
          const text = lines[line];
          const ranges: vscode.Range[] = [];
          const previews: vscode.Range[] = [];
          pattern.lastIndex = 0;
          let match: RegExpExecArray | null;
          while ((match = pattern.exec(text)) !== null) {
            if (match[0].length === 0) {
              pattern.lastIndex++;
              continue;
            }
            ranges.push(new vscode.Range(line, match.index, line, match.index + match[0].length));
            previews.push(new vscode.Range(0, match.index, 0, match.index + match[0].length));
          }
          if (ranges.length === 0) continue;

          progress.report({ uri, ranges, preview: { text, matches: previews } });
          count += ranges.length;
          if (count >= options.maxResults) return { limitHit: true };
        }
      }
      return { limitHit: false };
    }
  };

  return [
    vscode.workspace.registerFileSearchProvider(SCHEME, fileSearch),
    vscode.workspace.registerTextSearchProvider(SCHEME, textSearch)
  ];
}

function searchPattern(query: vscode.TextSearchQuery): RegExp {
  let source = query.isRegExp ? query.pattern : query.pattern.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  if (query.isWordMatch) source = `\\b${source}\\b`;
  return new RegExp(source, query.isCaseSensitive ? 'gu' : 'giu');
}

function fuzzyMatch(path: string, pattern: string): boolean {
  const needle = pattern.replace(/\s+/g, '').toLowerCase();
  if (needle.length === 0) return true;
  const haystack = path.toLowerCase();
  let index = 0;
  for (const char of haystack) {
    if (char === needle[index]) index++;
    if (index === needle.length) return true;
  }
  return false;
}

function isBinary(bytes: Uint8Array): boolean {
  const length = Math.min(bytes.length, 1024);
  for (let i = 0; i < length; i++) {
    if (bytes[i] === 0) return true;
  }
  return false;
}

function globFilter(options: vscode.SearchOptions): (path: string) => boolean {
  const includes = options.includes.map(globToRegExp);
  const excludes = options.excludes.map(globToRegExp);
  return absolute => {
    const path = absolute.replace(/^\/+/, '');
    const candidates = ancestors(path);
    if (excludes.some(exclude => candidates.some(candidate => exclude.test(candidate)))) return false;
    return includes.length === 0 || includes.some(include => candidates.some(candidate => include.test(candidate)));
  };
}

function ancestors(path: string): string[] {
  const parts = path.split('/');
  const result: string[] = [];
  for (let i = 1; i <= parts.length; i++) result.push(parts.slice(0, i).join('/'));
  return result;
}

function globToRegExp(glob: string): RegExp {
  let source = '';
  let inGroup = 0;
  for (let i = 0; i < glob.length; i++) {
    const char = glob[i];
    if (char === '*') {
      if (glob[i + 1] === '*') {
        const slash = glob[i + 2] === '/';
        source += slash ? '(?:.*/)?' : '.*';
        i += slash ? 2 : 1;
      } else {
        source += '[^/]*';
      }
    } else if (char === '?') {
      source += '[^/]';
    } else if (char === '{') {
      inGroup++;
      source += '(?:';
    } else if (char === '}' && inGroup > 0) {
      inGroup--;
      source += ')';
    } else if (char === ',' && inGroup > 0) {
      source += '|';
    } else if (char === '[') {
      const end = glob.indexOf(']', i + 1);
      if (end > i) {
        source += '[' + glob.slice(i + 1, end).replace(/^!/, '^').replace(/\\/g, '\\\\') + ']';
        i = end;
      } else {
        source += '\\[';
      }
    } else {
      source += char.replace(/[.+^$()|\\/]/g, '\\$&');
    }
  }
  return new RegExp(`^${source.replace(/^\\\//, '')}$`);
}

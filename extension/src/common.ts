import * as vscode from 'vscode';

export const SCHEME = 'ccstudio';

export function workspaceRoot(): vscode.Uri | undefined {
  return vscode.workspace.workspaceFolders?.find(item => item.uri.scheme === SCHEME)?.uri;
}

export function computerPath(uri: vscode.Uri): string {
  const root = workspaceRoot();
  if (!root || root.path === '/' || root.authority !== uri.authority) return uri.path;
  if (uri.path === root.path) return '/';
  if (uri.path.startsWith(root.path + '/')) return uri.path.slice(root.path.length);
  return uri.path;
}

export function computerUri(path: string): vscode.Uri {
  const normalized = path.startsWith('/') ? path : '/' + path;
  const root = workspaceRoot();
  if (!root) return vscode.Uri.from({ scheme: SCHEME, path: normalized });
  const base = root.path === '/' ? '' : root.path;
  return root.with({ path: normalized === '/' ? base || '/' : base + normalized });
}

export function isRomPath(path: string): boolean {
  return path === '/rom' || path.startsWith('/rom/');
}

export function encodeBase64(bytes: Uint8Array): string {
  let binary = '';
  for (let i = 0; i < bytes.length; i += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  }
  return btoa(binary);
}

export function decodeBase64(text: string): Uint8Array {
  const binary = atob(text);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

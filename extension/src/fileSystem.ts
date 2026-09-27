import * as vscode from 'vscode';
import { computerPath, computerUri, decodeBase64, encodeBase64 } from './common';
import { Connection, RemoteError } from './connection';

interface RemoteStat {
  type: vscode.FileType;
  size: number;
  mtime: number;
  ctime: number;
  readonly: boolean;
}

interface RemoteChange {
  type: vscode.FileChangeType;
  path: string;
}

export class StudioFileSystem implements vscode.FileSystemProvider, vscode.Disposable {
  private readonly changeEmitter = new vscode.EventEmitter<vscode.FileChangeEvent[]>();
  private readonly subscriptions: vscode.Disposable[] = [];
  readonly onDidChangeFile = this.changeEmitter.event;

  constructor(private readonly connection: Connection) {
    this.subscriptions.push(connection.onEvent(event => {
      if (event.event !== 'changes' || !Array.isArray(event.changes)) return;
      const changes = (event.changes as RemoteChange[]).map(change => ({ type: change.type, uri: computerUri(change.path) }));
      if (changes.length > 0) this.changeEmitter.fire(changes);
    }));
  }

  watch(): vscode.Disposable {
    return new vscode.Disposable(() => undefined);
  }

  async stat(uri: vscode.Uri): Promise<vscode.FileStat> {
    const stat = await this.call<RemoteStat>(uri, 'stat', { path: computerPath(uri) });
    return {
      type: stat.type,
      size: stat.size,
      mtime: stat.mtime,
      ctime: stat.ctime,
      permissions: stat.readonly ? vscode.FilePermission.Readonly : undefined
    };
  }

  readDirectory(uri: vscode.Uri): Promise<[string, vscode.FileType][]> {
    return this.call(uri, 'readDirectory', { path: computerPath(uri) });
  }

  async readFile(uri: vscode.Uri): Promise<Uint8Array> {
    const result = await this.call<{ data: string }>(uri, 'readFile', { path: computerPath(uri) });
    return decodeBase64(result.data);
  }

  async writeFile(uri: vscode.Uri, content: Uint8Array, options: { create: boolean; overwrite: boolean }): Promise<void> {
    await this.call(uri, 'writeFile', { path: computerPath(uri), data: encodeBase64(content), create: options.create, overwrite: options.overwrite });
  }

  async createDirectory(uri: vscode.Uri): Promise<void> {
    await this.call(uri, 'createDirectory', { path: computerPath(uri) });
  }

  async delete(uri: vscode.Uri, options: { recursive: boolean }): Promise<void> {
    await this.call(uri, 'delete', { path: computerPath(uri), recursive: options.recursive });
  }

  async rename(oldUri: vscode.Uri, newUri: vscode.Uri, options: { overwrite: boolean }): Promise<void> {
    await this.call(oldUri, 'rename', { from: computerPath(oldUri), to: computerPath(newUri), overwrite: options.overwrite });
  }

  listFiles(): Promise<string[]> {
    return this.connection.request<string[]>('listFiles');
  }

  dispose(): void {
    for (const subscription of this.subscriptions) subscription.dispose();
    this.changeEmitter.dispose();
  }

  private async call<T>(uri: vscode.Uri, op: string, params: Record<string, unknown>): Promise<T> {
    try {
      return await this.connection.request<T>(op, params);
    } catch (error) {
      throw toFileSystemError(error, uri);
    }
  }
}

function toFileSystemError(error: unknown, uri: vscode.Uri): Error {
  if (!(error instanceof RemoteError)) return vscode.FileSystemError.Unavailable(uri);
  switch (error.code) {
    case 'FileNotFound':
      return vscode.FileSystemError.FileNotFound(uri);
    case 'FileExists':
      return vscode.FileSystemError.FileExists(uri);
    case 'FileNotADirectory':
      return vscode.FileSystemError.FileNotADirectory(uri);
    case 'FileIsADirectory':
      return vscode.FileSystemError.FileIsADirectory(uri);
    case 'NoPermissions':
      return vscode.FileSystemError.NoPermissions(error.message);
    default:
      return vscode.FileSystemError.Unavailable(error.message);
  }
}

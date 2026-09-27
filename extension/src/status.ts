import * as vscode from 'vscode';
import { Connection } from './connection';

export type PowerState = 'on' | 'off' | 'unloaded' | 'unknown';

export interface ComputerInfo {
  id: number;
  label: string | undefined;
  state: PowerState;
  family: string | undefined;
  version: string | undefined;
}

export class ComputerStatus implements vscode.Disposable {
  private current: ComputerInfo = { id: -1, label: undefined, state: 'unknown', family: undefined, version: undefined };
  private readonly changeEmitter = new vscode.EventEmitter<ComputerInfo>();
  private readonly subscription: vscode.Disposable;
  readonly onDidChange = this.changeEmitter.event;

  constructor(connection: Connection) {
    this.subscription = connection.onEvent(event => {
      if (event.event === 'hello') {
        this.update({
          id: typeof event.computer === 'number' ? event.computer : this.current.id,
          version: typeof event.version === 'string' ? event.version : undefined
        });
      } else if (event.event === 'state') {
        this.update({
          id: typeof event.computer === 'number' ? event.computer : this.current.id,
          label: typeof event.label === 'string' ? event.label : undefined,
          state: isPowerState(event.state) ? event.state : 'unknown',
          family: typeof event.family === 'string' ? event.family : undefined
        });
      }
    });
  }

  get info(): ComputerInfo {
    return this.current;
  }

  get title(): string {
    if (this.current.id < 0) return 'Computer';
    return this.current.label ? `Computer #${this.current.id} (${this.current.label})` : `Computer #${this.current.id}`;
  }

  dispose(): void {
    this.subscription.dispose();
    this.changeEmitter.dispose();
  }

  private update(changes: Partial<ComputerInfo>): void {
    this.current = { ...this.current, ...changes };
    this.changeEmitter.fire(this.current);
  }
}

function isPowerState(value: unknown): value is PowerState {
  return value === 'on' || value === 'off' || value === 'unloaded';
}

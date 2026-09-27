import * as vscode from 'vscode';
import { errorMessage } from '../common';
import { Connection, ServerEvent } from '../connection';
import { ComputerStatus } from '../status';
import { ctrlPress, parseInput } from './input';
import { mouseMode, renderFrame, renderMessage, TerminalFrame, viewportOffset } from './render';

type Screen = { frame: TerminalFrame } | { message: string };

export class ComputerTerminals implements vscode.Disposable {
  private readonly ptys = new Set<ComputerPty>();
  private readonly subscriptions: vscode.Disposable[] = [];
  private frame: TerminalFrame | undefined;
  private unloaded = false;

  constructor(private readonly connection: Connection, private readonly status: ComputerStatus) {
    this.subscriptions.push(
      connection.onEvent(event => this.handle(event)),
      connection.onDidChangeState(state => {
        if (state === 'open') {
          this.setSubscription(this.ptys.size > 0);
        } else {
          this.frame = undefined;
        }
        this.redraw();
      }),
      status.onDidChange(() => {
        for (const pty of this.ptys) pty.rename(this.status.title);
        this.redraw();
      }),
      vscode.window.registerTerminalProfileProvider('ccstudio.terminal', {
        provideTerminalProfile: () => new vscode.TerminalProfile(this.options())
      }),
      vscode.workspace.onDidChangeConfiguration(event => {
        if (event.affectsConfiguration('ccstudio.terminal.mouse')) this.redraw();
      })
    );
  }

  show(preserveFocus = false): void {
    const existing = vscode.window.terminals.find(terminal => {
      const options = terminal.creationOptions as vscode.ExtensionTerminalOptions;
      return options.pty instanceof ComputerPty;
    });
    (existing ?? vscode.window.createTerminal(this.options())).show(preserveFocus);
  }

  sendCtrl(): void {
    this.connection.notify('input', { events: ctrlPress() });
  }

  attach(pty: ComputerPty): void {
    this.ptys.add(pty);
    pty.rename(this.status.title);
    if (this.ptys.size === 1) this.setSubscription(true);
    pty.draw(this.output(), this.mouseEnabled());
  }

  detach(pty: ComputerPty): void {
    this.ptys.delete(pty);
    if (this.ptys.size === 0) this.setSubscription(false);
  }

  input(data: string, rowOffset: number): void {
    if (this.connection.state !== 'open') return;
    if (this.status.info.state === 'off') {
      if (data.includes('\r')) this.control('turnOn');
      return;
    }
    const parsed = parseInput(data, this.mouseEnabled(), rowOffset);
    if (parsed.terminate) this.control('terminate');
    if (parsed.events.length > 0) this.connection.notify('input', { events: parsed.events });
  }

  dispose(): void {
    for (const subscription of this.subscriptions) subscription.dispose();
  }

  private control(action: string): void {
    this.connection.request('control', { action }).catch(error => vscode.window.showErrorMessage(errorMessage(error)));
  }

  private handle(event: ServerEvent): void {
    if (event.event !== 'terminal') return;
    if (event.unloaded) {
      this.unloaded = true;
      this.frame = undefined;
    } else {
      this.unloaded = false;
      this.frame = event as unknown as TerminalFrame;
    }
    this.redraw();
  }

  private options(): vscode.ExtensionTerminalOptions {
    return {
      name: this.status.title,
      pty: new ComputerPty(this),
      iconPath: new vscode.ThemeIcon('vm'),
      isTransient: true
    };
  }

  private setSubscription(enabled: boolean): void {
    if (this.connection.state === 'open') this.connection.request('terminal', { enabled }).catch(() => undefined);
  }

  private mouseEnabled(): boolean {
    return !!this.frame?.colour && this.status.info.state === 'on'
      && vscode.workspace.getConfiguration('ccstudio.terminal').get<boolean>('mouse', true);
  }

  private output(): Screen {
    switch (this.connection.state) {
      case 'connecting':
        return { message: 'Connecting to the computer...' };
      case 'reconnecting':
        return { message: 'Lost connection to the Minecraft server, reconnecting...' };
      case 'ended':
        return { message: this.connection.endReason ?? 'The editor session has ended.' };
    }
    if (this.unloaded || this.status.info.state === 'unloaded') {
      return { message: 'The computer is not loaded. Make sure its chunk is loaded in the world.' };
    }
    if (this.status.info.state === 'off') {
      return { message: 'The computer is off. Press Enter or use "Turn On Computer" to start it.' };
    }
    return this.frame ? { frame: this.frame } : { message: 'Waiting for the screen...' };
  }

  private redraw(): void {
    if (this.ptys.size === 0) return;
    const output = this.output();
    const mouse = this.mouseEnabled();
    for (const pty of this.ptys) pty.draw(output, mouse);
  }
}

class ComputerPty implements vscode.Pseudoterminal {
  private readonly writeEmitter = new vscode.EventEmitter<string>();
  private readonly nameEmitter = new vscode.EventEmitter<string>();
  readonly onDidWrite = this.writeEmitter.event;
  readonly onDidChangeName = this.nameEmitter.event;
  private opened = false;
  private mouse = false;
  private rows: number | undefined;
  private rowOffset = 0;
  private screen: Screen | undefined;

  constructor(private readonly owner: ComputerTerminals) {}

  open(dimensions: vscode.TerminalDimensions | undefined): void {
    this.opened = true;
    this.rows = dimensions?.rows;
    this.owner.attach(this);
  }

  close(): void {
    this.opened = false;
    this.owner.detach(this);
    this.writeEmitter.dispose();
    this.nameEmitter.dispose();
  }

  setDimensions(dimensions: vscode.TerminalDimensions): void {
    if (dimensions.rows === this.rows) return;
    this.rows = dimensions.rows;
    if (this.screen) this.draw(this.screen, this.mouse);
  }

  handleInput(data: string): void {
    this.owner.input(data, this.rowOffset);
  }

  draw(screen: Screen, mouse: boolean): void {
    if (!this.opened) return;
    this.screen = screen;
    if (mouse !== this.mouse) {
      this.mouse = mouse;
      this.writeEmitter.fire(mouseMode(mouse));
    }
    if ('frame' in screen) {
      this.rowOffset = viewportOffset(screen.frame, this.rows);
      this.writeEmitter.fire(renderFrame(screen.frame, this.rows));
    } else {
      this.rowOffset = 0;
      this.writeEmitter.fire(renderMessage(screen.message));
    }
  }

  rename(name: string): void {
    if (this.opened) this.nameEmitter.fire(name);
  }
}

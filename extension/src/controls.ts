import * as vscode from 'vscode';
import { SCHEME, computerPath, errorMessage, isRomPath } from './common';
import { Connection } from './connection';
import { ComputerStatus } from './status';
import { ComputerTerminals } from './terminal/terminals';

interface Action extends vscode.QuickPickItem {
  command: string;
}

export class Controls implements vscode.Disposable {
  private readonly subscriptions: vscode.Disposable[] = [];
  private readonly computerItem = vscode.window.createStatusBarItem('ccstudio.computer', vscode.StatusBarAlignment.Left, 100);
  private readonly runItem = vscode.window.createStatusBarItem('ccstudio.run', vscode.StatusBarAlignment.Left, 99);
  private readonly terminateItem = vscode.window.createStatusBarItem('ccstudio.terminate', vscode.StatusBarAlignment.Left, 98);
  private readonly rebootItem = vscode.window.createStatusBarItem('ccstudio.reboot', vscode.StatusBarAlignment.Left, 97);
  private endedNotified = false;

  constructor(private readonly connection: Connection, private readonly status: ComputerStatus, private readonly terminals: ComputerTerminals) {
    this.computerItem.name = 'Computer';
    this.computerItem.command = 'ccstudio.actions';
    this.runItem.name = 'Run on Computer';
    this.runItem.text = '$(play) Run';
    this.runItem.command = 'ccstudio.run';
    this.terminateItem.name = 'Terminate Program';
    this.terminateItem.text = '$(debug-stop)';
    this.terminateItem.tooltip = 'Terminate the running program (Shift+F5)';
    this.terminateItem.command = 'ccstudio.terminate';
    this.rebootItem.name = 'Reboot Computer';
    this.rebootItem.text = '$(debug-restart)';
    this.rebootItem.tooltip = 'Reboot the computer (Ctrl+Shift+F5)';
    this.rebootItem.command = 'ccstudio.reboot';

    this.subscriptions.push(
      this.computerItem, this.runItem, this.terminateItem, this.rebootItem,
      vscode.commands.registerCommand('ccstudio.run', (uri?: vscode.Uri) => this.run(uri)),
      vscode.commands.registerCommand('ccstudio.runFile', (uri?: vscode.Uri) => this.run(uri)),
      vscode.commands.registerCommand('ccstudio.terminate', () => this.control('terminate')),
      vscode.commands.registerCommand('ccstudio.reboot', () => this.control('reboot')),
      vscode.commands.registerCommand('ccstudio.shutdown', () => this.control('shutdown')),
      vscode.commands.registerCommand('ccstudio.turnOn', () => this.control('turnOn')),
      vscode.commands.registerCommand('ccstudio.showTerminal', () => this.terminals.show()),
      vscode.commands.registerCommand('ccstudio.sendCtrl', () => this.terminals.sendCtrl()),
      vscode.commands.registerCommand('ccstudio.reconnect', () => this.connection.reconnect()),
      vscode.commands.registerCommand('ccstudio.actions', () => this.showActions()),
      connection.onDidChangeState(() => this.update()),
      status.onDidChange(() => this.update()),
      vscode.window.onDidChangeActiveTextEditor(() => this.update())
    );
    this.update();
  }

  dispose(): void {
    for (const subscription of this.subscriptions) subscription.dispose();
  }

  private async run(uri?: vscode.Uri): Promise<void> {
    const target = uri ?? vscode.window.activeTextEditor?.document.uri;
    if (!target || target.scheme !== SCHEME) {
      vscode.window.showWarningMessage('Open a file from the computer to run it.');
      return;
    }
    const path = computerPath(target);
    if (isRomPath(path)) {
      vscode.window.showWarningMessage('Programs in /rom are run by name, for example "edit" or "lua".');
      return;
    }

    const settings = vscode.workspace.getConfiguration('ccstudio.run');
    if (settings.get<boolean>('saveBeforeRun', true)) {
      const document = vscode.workspace.textDocuments.find(item => item.uri.toString() === target.toString());
      if (document?.isDirty && !(await document.save())) return;
    }
    if (await this.control('run', { path }) && settings.get<boolean>('showTerminal', true)) {
      this.terminals.show(true);
    }
  }

  private async control(action: string, params: Record<string, unknown> = {}): Promise<boolean> {
    try {
      await this.connection.request('control', { action, ...params });
      return true;
    } catch (error) {
      vscode.window.showErrorMessage(errorMessage(error));
      return false;
    }
  }

  private async showActions(): Promise<void> {
    const state = this.status.info.state;
    const actions: Action[] = [
      { label: '$(terminal) Show Terminal', command: 'ccstudio.showTerminal' }
    ];
    if (vscode.window.activeTextEditor?.document.uri.scheme === SCHEME) {
      actions.push({ label: '$(play) Run Current File', command: 'ccstudio.run' });
    }
    if (state === 'on') {
      actions.push(
        { label: '$(debug-stop) Terminate Program', description: 'Like holding Ctrl+T', command: 'ccstudio.terminate' },
        { label: '$(debug-restart) Reboot', description: 'Like holding Ctrl+R', command: 'ccstudio.reboot' },
        { label: '$(debug-disconnect) Shut Down', description: 'Like holding Ctrl+S', command: 'ccstudio.shutdown' },
        { label: '$(key) Press Ctrl', description: 'Opens the menu in edit and similar programs', command: 'ccstudio.sendCtrl' }
      );
    } else if (state === 'off') {
      actions.push({ label: '$(debug-start) Turn On', command: 'ccstudio.turnOn' });
    }
    if (this.connection.state === 'reconnecting') {
      actions.push({ label: '$(plug) Reconnect Now', command: 'ccstudio.reconnect' });
    }
    const picked = await vscode.window.showQuickPick(actions, { title: this.status.title, placeHolder: 'Choose an action' });
    if (picked) await vscode.commands.executeCommand(picked.command);
  }

  private update(): void {
    const connection = this.connection.state;
    const state = this.status.info.state;
    const title = this.status.title;

    this.computerItem.backgroundColor = undefined;
    if (connection === 'ended') {
      this.computerItem.text = '$(debug-disconnect) Session ended';
      this.computerItem.tooltip = this.connection.endReason;
      this.computerItem.backgroundColor = new vscode.ThemeColor('statusBarItem.errorBackground');
      if (!this.endedNotified) {
        this.endedNotified = true;
        vscode.window.showWarningMessage(`${this.connection.endReason ?? 'The editor session has ended.'} Run "code" on the computer to start a new session.`);
      }
    } else if (connection !== 'open') {
      this.computerItem.text = `$(sync~spin) ${connection === 'connecting' ? 'Connecting' : 'Reconnecting'}...`;
      this.computerItem.tooltip = 'Connecting to the Minecraft server';
      this.computerItem.backgroundColor = new vscode.ThemeColor('statusBarItem.warningBackground');
    } else if (state === 'unloaded') {
      this.computerItem.text = `$(vm-outline) ${title} (not loaded)`;
      this.computerItem.tooltip = 'The computer\'s chunk is not loaded. Files can still be edited.';
    } else {
      this.computerItem.text = `${state === 'on' ? '$(vm-running)' : '$(vm-outline)'} ${title}`;
      this.computerItem.tooltip = `${title} is ${state === 'on' ? 'on' : 'off'}. Click for actions.`;
    }
    this.computerItem.show();

    const running = connection === 'open' && state === 'on';
    const editor = vscode.window.activeTextEditor;
    const path = editor?.document.uri.scheme === SCHEME ? computerPath(editor.document.uri) : undefined;
    if (connection === 'open' && state !== 'unloaded' && path && !isRomPath(path)) {
      this.runItem.tooltip = `Run ${path} on the computer (F5)`;
      this.runItem.show();
    } else {
      this.runItem.hide();
    }
    if (running) {
      this.terminateItem.show();
      this.rebootItem.show();
    } else {
      this.terminateItem.hide();
      this.rebootItem.hide();
    }
  }
}

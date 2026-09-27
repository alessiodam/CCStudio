import * as vscode from 'vscode';

export type ConnectionState = 'connecting' | 'open' | 'reconnecting' | 'ended';

export interface ServerEvent {
  event: string;
  [key: string]: unknown;
}

export class RemoteError extends Error {
  constructor(readonly code: string, message: string) {
    super(message);
  }
}

interface PendingRequest {
  resolve(value: unknown): void;
  reject(error: Error): void;
  timer: ReturnType<typeof setTimeout>;
}

const REQUEST_TIMEOUT = 30_000;
const PING_INTERVAL = 30_000;
const MAX_RETRY_DELAY = 5_000;
const PROBE_AFTER_FAILURES = 2;
const EXPIRED_MESSAGE = 'This editor session has ended or expired. Run "code" on the computer to start a new one.';

export class Connection implements vscode.Disposable {
  private socket: WebSocket | undefined;
  private nextId = 1;
  private retries = 0;
  private failures = 0;
  private disposed = false;
  private readonly pending = new Map<number, PendingRequest>();
  private readonly queue: string[] = [];
  private retryTimer: ReturnType<typeof setTimeout> | undefined;
  private pingTimer: ReturnType<typeof setInterval> | undefined;
  private currentState: ConnectionState = 'connecting';
  private reason: string | undefined;

  private readonly eventEmitter = new vscode.EventEmitter<ServerEvent>();
  private readonly stateEmitter = new vscode.EventEmitter<ConnectionState>();
  readonly onEvent = this.eventEmitter.event;
  readonly onDidChangeState = this.stateEmitter.event;

  constructor(private readonly url: string, private readonly probeUrl: string) {}

  get state(): ConnectionState {
    return this.currentState;
  }

  get endReason(): string | undefined {
    return this.reason;
  }

  connect(): void {
    if (this.disposed || this.currentState === 'ended') return;
    clearTimeout(this.retryTimer);

    let socket: WebSocket;
    try {
      socket = new WebSocket(this.url);
    } catch {
      this.scheduleReconnect();
      return;
    }
    this.socket = socket;

    let opened = false;
    socket.onopen = () => {
      if (this.socket !== socket) return;
      opened = true;
      this.retries = 0;
      this.failures = 0;
      this.setState('open');
      for (const message of this.queue.splice(0)) socket.send(message);
      clearInterval(this.pingTimer);
      this.pingTimer = setInterval(() => this.notify('ping', {}), PING_INTERVAL);
    };
    socket.onmessage = message => {
      if (this.socket === socket && typeof message.data === 'string') this.receive(message.data);
    };
    socket.onclose = () => {
      if (this.socket !== socket) return;
      this.socket = undefined;
      clearInterval(this.pingTimer);
      this.failPending(new RemoteError('Unavailable', 'Lost connection to the computer'));
      if (this.currentState === 'ended') return;
      if (!opened && ++this.failures >= PROBE_AFTER_FAILURES) {
        this.probe();
      } else {
        this.scheduleReconnect();
      }
    };
    socket.onerror = () => undefined;
  }

  reconnect(): void {
    if (this.currentState === 'ended') return;
    this.retries = 0;
    if (this.socket) {
      this.socket.close();
    } else {
      this.connect();
    }
  }

  request<T>(op: string, params: Record<string, unknown> = {}): Promise<T> {
    if (this.currentState === 'ended') {
      return Promise.reject(new RemoteError('Unavailable', this.reason ?? 'The editor session has ended'));
    }
    const id = this.nextId++;
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new RemoteError('Unavailable', `The computer did not answer '${op}' in time`));
      }, REQUEST_TIMEOUT);
      this.pending.set(id, { resolve: value => resolve(value as T), reject, timer });
      this.send(JSON.stringify({ op, id, ...params }));
    });
  }

  notify(op: string, params: Record<string, unknown>): void {
    if (this.currentState !== 'open' || !this.socket) return;
    this.socket.send(JSON.stringify({ op, ...params }));
  }

  dispose(): void {
    this.disposed = true;
    clearTimeout(this.retryTimer);
    clearInterval(this.pingTimer);
    this.socket?.close();
    this.failPending(new RemoteError('Unavailable', 'The extension was disposed'));
    this.eventEmitter.dispose();
    this.stateEmitter.dispose();
  }

  private send(message: string): void {
    if (this.socket && this.socket.readyState === WebSocket.OPEN) {
      this.socket.send(message);
    } else {
      this.queue.push(message);
    }
  }

  private receive(data: string): void {
    let message: Record<string, unknown>;
    try {
      message = JSON.parse(data);
    } catch {
      return;
    }

    if (typeof message.event !== 'string') {
      if (typeof message.id !== 'number') return;
      const request = this.pending.get(message.id);
      if (!request) return;
      this.pending.delete(message.id);
      clearTimeout(request.timer);
      if (message.ok) {
        request.resolve(message.result);
      } else {
        const error = (message.error ?? {}) as { code?: string; message?: string };
        request.reject(new RemoteError(error.code ?? 'Unavailable', error.message ?? 'Request failed'));
      }
      return;
    }

    if (message.event === 'closed') {
      this.end(typeof message.reason === 'string' ? message.reason : 'The editor session has ended');
      return;
    }
    this.eventEmitter.fire(message as ServerEvent);
  }

  private probe(): void {
    fetch(this.probeUrl, { method: 'HEAD', cache: 'no-store', credentials: 'same-origin' })
      .then(response => {
        if (response.status === 404) {
          this.end(EXPIRED_MESSAGE);
        } else {
          this.scheduleReconnect();
        }
      })
      .catch(() => this.scheduleReconnect());
  }

  private end(reason: string): void {
    this.reason = reason;
    this.setState('ended');
    this.queue.length = 0;
    this.failPending(new RemoteError('Unavailable', reason));
    this.eventEmitter.fire({ event: 'closed', reason });
  }

  private scheduleReconnect(): void {
    if (this.disposed || this.currentState === 'ended') return;
    this.setState(this.retries === 0 && this.currentState === 'connecting' ? 'connecting' : 'reconnecting');
    const delay = Math.min(MAX_RETRY_DELAY, 250 * 2 ** this.retries++);
    this.retryTimer = setTimeout(() => this.connect(), delay);
  }

  private failPending(error: Error): void {
    for (const request of this.pending.values()) {
      clearTimeout(request.timer);
      request.reject(error);
    }
    this.pending.clear();
  }

  private setState(state: ConnectionState): void {
    if (this.currentState === state) return;
    this.currentState = state;
    this.stateEmitter.fire(state);
  }
}

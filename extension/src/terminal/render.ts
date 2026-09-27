import { toDisplay } from './charset';

export interface TerminalFrame {
  width: number;
  height: number;
  colour: boolean;
  cursor: { x: number; y: number; blink: boolean; colour: string };
  palette: string[];
  text: string[];
  fg: string[];
  bg: string[];
}

const ESC = '\x1b';

export function viewportOffset(frame: TerminalFrame, rows: number | undefined): number {
  if (!rows || rows >= frame.height) return 0;
  return Math.max(0, Math.min(frame.height - rows, frame.cursor.y - rows + 1));
}

export function renderFrame(frame: TerminalFrame, rows?: number): string {
  const offset = viewportOffset(frame, rows);
  const visible = rows ? Math.min(rows, frame.height) : frame.height;
  const rgb = frame.palette.map(hex => {
    const value = parseInt(hex, 16);
    return `${(value >> 16) & 255};${(value >> 8) & 255};${value & 255}`;
  });

  let out = `${ESC}[?25l${ESC}[?7l`;
  for (let row = 0; row < visible; row++) {
    const y = row + offset;
    const text = frame.text[y] ?? '';
    const fg = frame.fg[y] ?? '';
    const bg = frame.bg[y] ?? '';
    out += `${ESC}[${row + 1};1H`;
    let lastFg = '';
    let lastBg = '';
    for (let x = 0; x < frame.width; x++) {
      const foreground = fg[x] ?? '0';
      const background = bg[x] ?? 'f';
      if (foreground !== lastFg || background !== lastBg) {
        out += `${ESC}[38;2;${rgb[digit(foreground)]};48;2;${rgb[digit(background)]}m`;
        lastFg = foreground;
        lastBg = background;
      }
      out += toDisplay(text.charCodeAt(x) || 32);
    }
    out += `${ESC}[0m${ESC}[K`;
  }
  if (visible < (rows ?? visible + 1)) out += `${ESC}[${visible + 1};1H${ESC}[0m${ESC}[J`;

  const { x, blink, colour } = frame.cursor;
  const y = frame.cursor.y - offset;
  if (blink && x >= 0 && x < frame.width && y >= 0 && y < visible) {
    out += `${ESC}]12;#${frame.palette[digit(colour)]}\x07${ESC}[${y + 1};${x + 1}H${ESC}[?25h`;
  } else {
    out += `${ESC}[${Math.min(visible, Math.max(1, y + 1))};1H`;
  }
  return out;
}

export function renderMessage(message: string): string {
  return `${ESC}[?25l${ESC}[?7h${ESC}[H${ESC}[0m${ESC}[2J${ESC}[90m${message}${ESC}[0m`;
}

export function mouseMode(enabled: boolean): string {
  return enabled ? `${ESC}[?1000h${ESC}[?1002h${ESC}[?1006h` : `${ESC}[?1006l${ESC}[?1002l${ESC}[?1000l`;
}

function digit(value: string): number {
  const parsed = parseInt(value, 16);
  return Number.isNaN(parsed) ? 0 : parsed;
}

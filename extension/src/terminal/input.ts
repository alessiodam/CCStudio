export type InputEvent = (string | number | boolean)[];

export interface ParsedInput {
  events: InputEvent[];
  terminate: boolean;
}

const KEY = {
  space: 32,
  enter: 257,
  tab: 258,
  backspace: 259,
  insert: 260,
  delete: 261,
  right: 262,
  left: 263,
  down: 264,
  up: 265,
  pageUp: 266,
  pageDown: 267,
  home: 268,
  end: 269,
  escape: 256,
  f1: 290,
  leftShift: 340,
  leftCtrl: 341,
  leftAlt: 342
} as const;

const SHIFTED_SYMBOLS: Record<string, string> = {
  '!': '1', '@': '2', '#': '3', '$': '4', '%': '5', '^': '6', '&': '7', '*': '8', '(': '9', ')': '0',
  '_': '-', '+': '=', '{': '[', '}': ']', '|': '\\', ':': ';', '"': "'", '<': ',', '>': '.', '?': '/', '~': '`'
};

const SYMBOL_KEYS: Record<string, number> = {
  "'": 39, ',': 44, '-': 45, '.': 46, '/': 47, ';': 59, '=': 61, '[': 91, '\\': 92, ']': 93, '`': 96
};

const ESCAPE_SEQUENCES: Record<string, number> = {
  '[A': KEY.up, 'OA': KEY.up,
  '[B': KEY.down, 'OB': KEY.down,
  '[C': KEY.right, 'OC': KEY.right,
  '[D': KEY.left, 'OD': KEY.left,
  '[H': KEY.home, 'OH': KEY.home, '[1~': KEY.home, '[7~': KEY.home,
  '[F': KEY.end, 'OF': KEY.end, '[4~': KEY.end, '[8~': KEY.end,
  '[2~': KEY.insert,
  '[3~': KEY.delete,
  '[5~': KEY.pageUp,
  '[6~': KEY.pageDown,
  'OP': KEY.f1, 'OQ': KEY.f1 + 1, 'OR': KEY.f1 + 2, 'OS': KEY.f1 + 3,
  '[15~': KEY.f1 + 4, '[17~': KEY.f1 + 5, '[18~': KEY.f1 + 6, '[19~': KEY.f1 + 7,
  '[20~': KEY.f1 + 8, '[21~': KEY.f1 + 9, '[23~': KEY.f1 + 10, '[24~': KEY.f1 + 11
};

const MOUSE_SEQUENCE = /^\x1b\[<(\d+);(\d+);(\d+)([Mm])/;
const CSI_SEQUENCE = /^\x1b(\[[0-9;]*[A-Za-z~]|O[A-Za-z])/;

export function parseInput(data: string, mouse: boolean, rowOffset = 0): ParsedInput {
  const result: ParsedInput = { events: [], terminate: false };

  if (data.length > 1 && !/[\x00-\x1f\x7f]/.test(data)) {
    result.events.push(['paste', data]);
    return result;
  }

  let rest = data;
  while (rest.length > 0) {
    const mouseMatch = MOUSE_SEQUENCE.exec(rest);
    if (mouseMatch) {
      if (mouse) pushMouse(result.events, Number(mouseMatch[1]), Number(mouseMatch[2]), Number(mouseMatch[3]) + rowOffset, mouseMatch[4] === 'm');
      rest = rest.slice(mouseMatch[0].length);
      continue;
    }

    const csiMatch = CSI_SEQUENCE.exec(rest);
    if (csiMatch) {
      pushSequence(result.events, csiMatch[1]);
      rest = rest.slice(csiMatch[0].length);
      continue;
    }

    const char = String.fromCodePoint(rest.codePointAt(0)!);
    rest = rest.slice(char.length);

    if (char === '\x1b') {
      if (rest.length > 0 && rest[0] >= ' ' && rest[0] !== '\x7f') {
        const next = String.fromCodePoint(rest.codePointAt(0)!);
        rest = rest.slice(next.length);
        pushModified(result.events, KEY.leftAlt, next);
      } else {
        pushKey(result.events, KEY.escape);
      }
      continue;
    }
    pushChar(result, char);
  }
  return result;
}

function pushChar(result: ParsedInput, char: string): void {
  const code = char.charCodeAt(0);
  switch (char) {
    case '\r':
    case '\n':
      pushKey(result.events, KEY.enter);
      return;
    case '\t':
      pushKey(result.events, KEY.tab);
      return;
    case '\x7f':
    case '\b':
      pushKey(result.events, KEY.backspace);
      return;
    case '\x14':
      result.terminate = true;
      return;
  }
  if (code >= 1 && code <= 26) {
    pushModified(result.events, KEY.leftCtrl, String.fromCharCode(code + 96));
    return;
  }
  if (code < 32) return;

  const key = keyFor(char);
  if (key !== undefined) result.events.push(['key', key, false]);
  result.events.push(['char', char]);
  if (key !== undefined) result.events.push(['keyUp', key]);
}

function pushModified(events: InputEvent[], modifier: number, char: string): void {
  const key = keyFor(char);
  if (key === undefined) return;
  events.push(['key', modifier, false], ['key', key, false], ['keyUp', key], ['keyUp', modifier]);
}

function pushKey(events: InputEvent[], key: number): void {
  events.push(['key', key, false], ['keyUp', key]);
}

function pushSequence(events: InputEvent[], sequence: string): void {
  const direct = ESCAPE_SEQUENCES[sequence];
  if (direct !== undefined) {
    pushKey(events, direct);
    return;
  }

  const modified = /^\[1;(\d+)([A-DFH])$/.exec(sequence);
  if (!modified) return;
  const key = ESCAPE_SEQUENCES['[' + modified[2]];
  if (key === undefined) return;
  const modifiers = Number(modified[1]) - 1;
  const held: number[] = [];
  if (modifiers & 1) held.push(KEY.leftShift);
  if (modifiers & 2) held.push(KEY.leftAlt);
  if (modifiers & 4) held.push(KEY.leftCtrl);
  for (const modifier of held) events.push(['key', modifier, false]);
  pushKey(events, key);
  for (const modifier of held.reverse()) events.push(['keyUp', modifier]);
}

function pushMouse(events: InputEvent[], code: number, x: number, y: number, release: boolean): void {
  if (code & 64) {
    events.push(['mouseScroll', code & 1 ? 1 : -1, x, y]);
    return;
  }
  const button = [1, 3, 2][code & 3];
  if (button === undefined) return;
  if (release) {
    events.push(['mouseUp', button, x, y]);
  } else if (code & 32) {
    events.push(['mouseDrag', button, x, y]);
  } else {
    events.push(['mouseClick', button, x, y]);
  }
}

function keyFor(char: string): number | undefined {
  const base = SHIFTED_SYMBOLS[char] ?? char;
  if (base === ' ') return KEY.space;
  if (/^[a-z]$/i.test(base)) return base.toUpperCase().charCodeAt(0);
  if (/^[0-9]$/.test(base)) return base.charCodeAt(0);
  return SYMBOL_KEYS[base];
}

export function ctrlPress(): InputEvent[] {
  return [['key', KEY.leftCtrl, false], ['keyUp', KEY.leftCtrl]];
}

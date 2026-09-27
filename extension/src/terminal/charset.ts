const CONTROL_GLYPHS = ' ☺☻♥♦♣♠•◘○◙♂♀♪♫☼►◄↕‼¶§▬↨↑↓→←∟↔▲▼';

const GLYPHS: string[] = Array.from({ length: 256 }, (_, code) => glyph(code));

function glyph(code: number): string {
  if (code < 32) return CONTROL_GLYPHS[code];
  if (code === 127) return '▒';
  if (code >= 128 && code < 160) return sextant(code - 128);
  if (code === 160) return ' ';
  return String.fromCharCode(code);
}

function sextant(pattern: number): string {
  if (pattern === 0) return ' ';
  if (pattern === 21) return '▌';
  return String.fromCodePoint(0x1fb00 + pattern - 1 - (pattern > 21 ? 1 : 0));
}

export function toDisplay(code: number): string {
  return GLYPHS[code & 0xff];
}

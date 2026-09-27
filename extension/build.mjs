import { build } from 'esbuild';
import { copyFile, mkdir, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.dirname(fileURLToPath(import.meta.url));
const outArgument = process.argv.find(arg => arg.startsWith('--out='));
const outRoot = path.resolve(outArgument ? outArgument.slice('--out='.length) : path.join(root, '..', 'build', 'generated', 'extension'));
const target = path.join(outRoot, 'ccstudio', 'web', 'extension');

await rm(target, { recursive: true, force: true });
await mkdir(path.join(target, 'dist'), { recursive: true });

await build({
  entryPoints: [path.join(root, 'src', 'extension.ts')],
  outfile: path.join(target, 'dist', 'extension.js'),
  bundle: true,
  format: 'cjs',
  platform: 'browser',
  target: 'es2022',
  external: ['vscode'],
  minify: true,
  sourcemap: false,
  legalComments: 'none',
  logLevel: 'info'
});

await copyFile(path.join(root, 'package.json'), path.join(target, 'package.json'));
await writeFile(path.join(target, 'package.nls.json'), '{}\n');

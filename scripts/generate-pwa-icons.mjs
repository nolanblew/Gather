// Requires Sharp for this one-time asset task, not for serving/building Gather.
// An optional absolute module path can use an existing workspace Sharp install.
import { createRequire } from 'node:module';
import { mkdir, readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
const require = createRequire(import.meta.url);
const sharp = require(process.argv[2] || 'sharp');
const output = new URL('../public/icons/', import.meta.url);
const source = await readFile(new URL('../public/gather.svg', import.meta.url));
await mkdir(output, { recursive: true });
for (const size of [192, 512]) await sharp(source).resize(size, size).png().toFile(fileURLToPath(new URL(`gather-${size}.png`, output)));
await sharp(source).resize(180, 180).flatten({ background: '#276653' }).png().toFile(fileURLToPath(new URL('apple-touch-icon.png', output)));
// Full-bleed opaque background; the mark stays inside the maskable safe circle.
const inset = await sharp(source).resize(410, 410).png().toBuffer();
await sharp({ create: { width: 512, height: 512, channels: 4, background: '#276653' } }).composite([{ input: inset, left: 51, top: 51 }]).png().toFile(fileURLToPath(new URL('gather-maskable-512.png', output)));

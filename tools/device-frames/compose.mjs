import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const sharp = createRequire(import.meta.url)('sharp');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const frame = await fs.readFile(path.join(root,'dist/device-frames/zflip5-cover-overlay.svg'),'utf8');
const [x,y,w,h] = frame.match(/data-screen="([^"]+)"/)[1].split(' ').map(Number);
const [width,height] = frame.match(/viewBox="0 0 ([\d.]+) ([\d.]+)"/).slice(1).map(Number);
const opening = frame.match(/<path transform="translate\([^)]+\)" d="([^"]+)" fill="black"/)[1];
const escape = value => value.replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&apos;'}[c]));

export async function compose(raw, title='外屏 UI · 校准机型框') {
  // Decode/re-encode to make the SVG self-contained regardless of input format.
  const png = await sharp(raw).png().toBuffer();
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}"><title>${escape(title)}</title><desc>原始UI等比居中，按校准开孔裁剪；外框在最上层叠加一次。机型外观示意不能代替真机安全区验证。</desc><defs><clipPath id="ui-opening"><path transform="translate(${x} ${y})" d="${opening}"/></clipPath></defs><g clip-path="url(#ui-opening)"><rect x="${x}" y="${y}" width="${w}" height="${h}" fill="#000"/><image x="${x}" y="${y}" width="${w}" height="${h}" preserveAspectRatio="xMidYMid meet" href="data:image/png;base64,${png.toString('base64')}"/></g><image width="${width}" height="${height}" href="data:image/svg+xml;base64,${Buffer.from(frame).toString('base64')}"/></svg>`;
  return {svg,png:await sharp(Buffer.from(svg)).png().toBuffer()};
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [, , input, output] = process.argv;
  if (!input || !output) throw Error('Usage: node tools/device-frames/compose.mjs raw.png output-framed.png|svg');
  const result = await compose(await fs.readFile(input));
  await fs.writeFile(output,output.endsWith('.svg') ? result.svg : result.png);
}

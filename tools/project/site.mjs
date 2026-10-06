import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { projectRoot } from './project.mjs';

export const siteDirectory = path.join(projectRoot, 'dist/html');
const assetExtensions = new Set(['.html', '.css', '.js', '.svg', '.png', '.webp']);

export function checkSite(directory = siteDirectory) {
  const entries = fs.readdirSync(directory, { withFileTypes: true });
  const names = new Set(entries.map(entry => entry.name));
  const errors = [];
  let scripts = 0;
  let references = 0;
  for (const entry of entries) {
    if (!entry.isFile() || !assetExtensions.has(path.extname(entry.name))) {
      errors.push(`Unsupported website entry: ${entry.name}`);
      continue;
    }
    if (!['.html', '.css', '.js'].includes(path.extname(entry.name))) continue;
    const content = fs.readFileSync(path.join(directory, entry.name), 'utf8');
    if (entry.name.endsWith('.js')) {
      try { new vm.Script(content, { filename: entry.name }); scripts++; }
      catch (error) { errors.push(`${entry.name}: classic script syntax error (modules need a browser check): ${error.message}`); }
      continue;
    }
    for (const match of content.matchAll(/\b(href|src)=["']([^"']+)["']|url\(["']?([^\s)'";]+)["']?\)/g)) {
      const reference = match[2] || match[3];
      if (reference.startsWith('#') || reference.startsWith('data:')) continue;
      if (/^(?:[a-z]+:|\/\/)/i.test(reference)) {
        const tag = content.slice(content.lastIndexOf('<', match.index), match.index).match(/^<([a-z]+)\b/i)?.[1].toLowerCase();
        if (match[1] !== 'href' || tag !== 'a') errors.push(`${entry.name}: external resource ${reference}`);
        continue;
      }
      const target = decodeURIComponent(reference.split(/[?#]/)[0]);
      if (!target) continue;
      references++;
      if (target.includes('/') || target.includes('\\') || !names.has(target)) errors.push(`${entry.name}: missing or non-flat resource ${reference}`);
    }
    for (const match of content.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)) {
      if (/type=["']module["']/.test(match[1])) throw new Error(`${entry.name}: module scripts need a browser syntax check`);
      if (!match[2].trim() || /type=["']application\/(?:ld\+)?json["']/.test(match[1])) continue;
      try { new vm.Script(match[2], { filename: entry.name }); scripts++; }
      catch (error) { errors.push(`${entry.name}: ${error.message}`); }
    }
  }
  for (const name of ['index.html', 'flipcover-tutorial.html', 'flipcover-prototype.html', 'device-frame-preview.html']) {
    if (!names.has(name)) errors.push(`Missing website entry point: ${name}`);
  }
  if (names.has('flipcover-tutorial.html') && names.has('flipcover-prototype.html') && !fs.readFileSync(path.join(directory, 'flipcover-tutorial.html')).equals(fs.readFileSync(path.join(directory, 'flipcover-prototype.html')))) errors.push('Tutorial compatibility copy differs');
  if (errors.length) throw new Error(errors.join('\n'));
  return { pages: entries.filter(entry => entry.name.endsWith('.html')).length, scripts, references };
}

export function assembleSite(destination, source = siteDirectory) {
  checkSite(source);
  const output = path.resolve(destination);
  const cache = path.join(projectRoot, 'Cache/build-output.nosync');
  const relative = path.relative(cache, output);
  if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) throw new Error('Website output must be a child of Cache/build-output.nosync');
  fs.mkdirSync(cache, { recursive: true });
  // Lexical containment is not sufficient when any output ancestor is a symlink.
  let ancestor = output;
  while (!fs.existsSync(ancestor)) ancestor = path.dirname(ancestor);
  const actualCache = fs.realpathSync(cache);
  const actualAncestor = fs.realpathSync(ancestor);
  const actualRelative = path.relative(actualCache, actualAncestor);
  if (actualRelative.startsWith('..') || path.isAbsolute(actualRelative)) throw new Error('Website output symlink escapes Cache/build-output.nosync');
  if (fs.existsSync(output)) throw new Error('Website output already exists; choose a fresh output directory');
  fs.mkdirSync(output, { recursive: true });
  for (const name of fs.readdirSync(source)) fs.copyFileSync(path.join(source, name), path.join(output, name));
  const legacy = path.join(output, 'device-frames');
  fs.mkdirSync(legacy);
  for (const name of ['zflip5-cover-overlay.svg', 'zflip5-cover-overlay.png', 'zflip5-cover-overlay@2x.png']) fs.copyFileSync(path.join(source, name), path.join(legacy, name));
  fs.writeFileSync(path.join(legacy, 'preview.html'), '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta http-equiv="refresh" content="0;url=../device-frame-preview.html"><title>设备外框预览</title><a href="../device-frame-preview.html">打开设备外框预览</a></html>\n');
  return output;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const command = process.argv[2] || 'check';
    if (command === 'check') console.log(JSON.stringify(checkSite()));
    else if (command === 'assemble' && process.argv[3]) console.log(assembleSite(path.resolve(projectRoot, process.argv[3])));
    else throw new Error('Usage: node tools/project/site.mjs check | assemble Cache/build-output.nosync/<directory>');
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}

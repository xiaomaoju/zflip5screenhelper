import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const sharp = require('sharp');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const frames = path.join(root, 'dist/device-frames');
const svg = await fs.readFile(path.join(frames, 'zflip5-cover-overlay.svg'), 'utf8');
const [, width, height] = svg.match(/viewBox="0 0 ([\d.]+) ([\d.]+)"/).map(Number);
const [sx, sy, sw, sh] = svg.match(/data-screen="([^"]+)"/)[1].split(' ').map(Number);
const opening = svg.match(/<path transform="translate\([^)]+\)" d="([^"]+)" fill="black"/)[1];
const n = value => Number(value.toFixed(8));
const b64 = Buffer.from(svg).toString('base64');
const image = `<img src="data:image/svg+xml;base64,${b64}" alt="" draggable="false">`;
const matrices = [[1/sw,0,0,1/sh,0,0],[0,1/sw,-1/sh,0,1,0],[-1/sw,0,0,-1/sh,1,1],[0,-1/sw,1/sh,0,0,1]];
const clips = '<svg xmlns="http://www.w3.org/2000/svg" width="0" height="0" aria-hidden="true" class="device-frame-clips" style="position:absolute;pointer-events:none"><defs>' + matrices.map((m,i) => `<clipPath id="cover-screen-${i*90}" clipPathUnits="objectBoundingBox"><path d="${opening}" transform="matrix(${m.map(n).join(' ')})"/></clipPath>`).join('') + '</defs></svg>';
const scale = 440/width, bodyH = n(height*scale);
const rects = [[sx,sy,sw,sh],[height-sy-sh,sx,sh,sw],[width-sx-sw,height-sy-sh,sw,sh],[sy,width-sx-sw,sh,sw]];
const rectCss = (r, factor=scale) => `left:${n(r[0]*factor)}px;top:${n(r[1]*factor)}px;width:${n(r[2]*factor)}px;height:${n(r[3]*factor)}px;`;
function clipDefs(html) {
  html = html.replace(/<svg\b[^>]*class="device-frame-clips"[\s\S]*?<\/svg>/g, '');
  return html.replace(/<body([^>]*)>/, `<body$1>${clips}`);
}
function rule(html, selector, update) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const re = new RegExp(`(^|})${escaped}\\{([^{}]*)\\}`);
  if (!re.test(html)) throw Error(`Missing CSS rule: ${selector}`);
  return html.replace(re, (_, prefix, body) => `${prefix}${selector}{${update(body)}}`);
}
function properties(body, names, appended) {
  for (const name of names) body = body.replace(new RegExp(`(^|;)${name}:[^;]*`, 'g'), '$1');
  return body.replace(/;+/g,';').replace(/^;|;$/g,'') + ';' + appended;
}
async function update(file, transform) {
  const full = path.join(root,file), original = await fs.readFile(full,'utf8');
  const next = transform(original);
  if (next !== original) await fs.writeFile(full,next);
}

await sharp(Buffer.from(svg)).png().toFile(path.join(frames,'zflip5-cover-overlay.png'));
await sharp(Buffer.from(svg),{density:144}).png().toFile(path.join(frames,'zflip5-cover-overlay@2x.png'));
// Compatibility outputs for already-installed cover launchers; never edit them.
await fs.writeFile(path.join(frames,'zflip5-cover-calibrated.svg'),svg);
await fs.copyFile(path.join(frames,'zflip5-cover-overlay.png'),path.join(frames,'zflip5-cover-calibrated.png'));
await update('dist/flipcover-prototype.html', html => {
  html = clipDefs(html).replace(/(<img class="frame" src=")[^"]+/, `$1data:image/svg+xml;base64,${b64}`);
  html = rule(html,'.stage',body => properties(body,['height'],`height:${bodyH}px;`));
  html = rule(html,'.device',body => properties(body,['height'],`height:${bodyH}px;`));
  html = rule(html,'.glass',body => properties(body,['inset','left','top','width','height','border-radius','clip-path'],rectCss(rects[0])+'border-radius:0;clip-path:url(#cover-screen-0);'));
  html = rule(html,'.frame',body => properties(body,['height'],`height:${bodyH}px;`));
  html = rule(html,".device[data-angle='90'],.device[data-angle='270']",() => `width:${bodyH}px;height:440px`);
  for (let i=1;i<4;i++) html = rule(html,`.device[data-angle='${i*90}'] .glass`,() => rectCss(rects[i])+`clip-path:url(#cover-screen-${i*90})`);
  html = html.replace(/const width=isVertical\(\)\?[\d.]+:440,height=isVertical\(\)\?440:[\d.]+,scale=/,`const width=isVertical()?${bodyH}:440,height=isVertical()?440:${bodyH},scale=`);
  return html.replaceAll('720 × 748 · 已选择','748 × 720 · 已选择');
});
await fs.copyFile(path.join(root,'dist/flipcover-prototype.html'),path.join(root,'dist/flipcover-tutorial.html'));
await update('dist/settings-oneui-prototype.html', html => {
  html = clipDefs(html);
  html = rule(html,'.device',body => properties(body,['aspect-ratio'],`aspect-ratio:${width}/${height};`));
  html = rule(html,'.screen',body => properties(body,['left','top','width','height','border-radius','clip-path'],`left:${n(sx/width*100)}%;top:${n(sy/height*100)}%;width:${n(sw/width*100)}%;height:${n(sh/height*100)}%;border-radius:0;clip-path:url(#cover-screen-0);`));
  return html.replaceAll('720 × 748 · 非真实设备','748 × 720 · 非真实设备');
});
await update('dist/nfc-hotspot-prototype.html', html => {
  html = clipDefs(html).replace(/<template id="frame-template">[\s\S]*?<\/template>/,`<template id="frame-template">${image}</template>`);
  html = rule(html,'.device-wrap',body=>properties(body,['aspect-ratio'],`aspect-ratio:${width}/${height};`));
  html = rule(html,'.device',body=>properties(body,['height'],`height:${bodyH}px;`));
  html = rule(html,'.glass',body=>properties(body,['left','top','width','height','border-radius','clip-path'],rectCss(rects[0])+'border-radius:0;clip-path:url(#cover-screen-0);'));
  html = html.replace('.device-frame>svg{','.device-frame>img{');
  html = html.replace(/document\.getElementById\('frame-template'\)\.innerHTML(?:\.replaceAll\([^;]*?\))(?=\})/,"document.getElementById('frame-template').innerHTML");
  return html;
});
await update('dist/device-frames/preview.html', html => html.replace(/<div class="stage">[\s\S]*?<\/svg>/,`<div class="stage">${svg.trim()}`).replace(/(?:720 × 748|836 × 992) · 背景/,`${width} × ${height} · 背景`).replace('基于项目 HTML 原型的外观示意；真机圆角、缺口和窗口路由需另行验证。','依据真机像素与参考照片绘制；机身外观为示意，窗口安全边距需真机验证。'));
console.log(`Updated canonical ${width}×${height} frame, PNGs, compatibility assets and 5 HTML consumers.`);

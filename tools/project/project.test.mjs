import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { projectRoot } from './project.mjs';
import { checkSite, assembleSite, siteDirectory } from './site.mjs';
import { sha256, validateCatalog, publishDirectory } from './release.mjs';

const cache = path.join(projectRoot, 'Cache/build-output.nosync/project/tests');
await fs.mkdir(cache, { recursive: true });

async function fixture(run) {
  const directory = await fs.mkdtemp(path.join(cache, 'case-'));
  try { await run(directory); }
  finally { await fs.rm(directory, { recursive: true }); }
}

test('website rejects missing resources and script syntax errors', () => fixture(async directory => {
  await fs.cp(siteDirectory, directory, { recursive: true });
  await fs.rm(path.join(directory, 'zflip5-cover-overlay.svg'));
  assert.throws(() => checkSite(directory), /missing or non-flat/);
  await fs.copyFile(path.join(siteDirectory, 'zflip5-cover-overlay.svg'), path.join(directory, 'zflip5-cover-overlay.svg'));
  await fs.appendFile(path.join(directory, 'index.html'), '<script>const broken = ;</script>');
  assert.throws(() => checkSite(directory), /Unexpected token/);
}));

test('website rejects files outside the portable asset contract', () => fixture(async directory => {
  await fs.cp(siteDirectory, directory, { recursive: true });
  await fs.writeFile(path.join(directory, 'keystore.properties'), 'fixture');
  assert.throws(() => checkSite(directory), /Unsupported website entry/);
}));

test('website checks external classic JS and rejects hosted stylesheets', () => fixture(async directory => {
  await fs.cp(siteDirectory, directory, { recursive: true });
  await fs.writeFile(path.join(directory, 'extra.js'), 'const broken = ;');
  assert.throws(() => checkSite(directory), /classic script syntax error/);
  await fs.rm(path.join(directory, 'extra.js'));
  await fs.appendFile(path.join(directory, 'index.html'), '<link rel="stylesheet" href="https://example.com/style.css">');
  assert.throws(() => checkSite(directory), /external resource/);
}));

test('website output cannot escape through a directory symlink', () => fixture(async directory => {
  const outside = path.join(projectRoot, 'Cache/tests');
  await fs.mkdir(outside, { recursive: true });
  const link = path.join(directory, 'link');
  await fs.symlink(outside, link, process.platform === 'win32' ? 'junction' : 'dir');
  assert.throws(() => assembleSite(path.join(link, 'probe')), /symlink escapes/);
  assert.throws(() => assembleSite(path.join(projectRoot, 'dist/probe')), /must be a child/);
}));

test('module scripts require their explicit browser validation route', () => fixture(async directory => {
  await fs.cp(siteDirectory, directory, { recursive: true });
  await fs.writeFile(path.join(directory, 'module.js'), 'const value = 1;');
  await fs.appendFile(path.join(directory, 'index.html'), '<script type="module" src="module.js"></script>');
  assert.throws(() => checkSite(directory), /module scripts need a browser syntax check/);
}));

test('catalog rejects traversal, wrong version and malformed checksums', () => {
  const catalog = { schemaVersion: 1, packageName: 'io.github.flipcover.controls', versionCode: 999, versionName: '1.0.0', minSdk: 30, changelog: 'fixture', apkPath: 'releases/999/flipcover-1.0.0.apk', apkSize: 10, sha256: 'a'.repeat(64) };
  assert.equal(validateCatalog(catalog), 'flipcover-1.0.0.apk');
  for (const apkPath of ['../flipcover-1.0.0.apk', 'releases/998/flipcover-1.0.0.apk', 'releases/00999/flipcover-1.0.0.apk', 'releases/999/flipcover-2.0.0.apk']) assert.throws(() => validateCatalog({ ...catalog, apkPath }), /APK path/);
  assert.throws(() => validateCatalog({ ...catalog, sha256: 'bad' }), /Invalid release catalog/);
});

test('version publication never replaces an existing directory', () => fixture(async directory => {
  const stage = path.join(directory, 'stage');
  const destination = path.join(directory, '999');
  await fs.mkdir(stage);
  await fs.writeFile(path.join(stage, 'catalog.json'), 'new');
  await fs.mkdir(destination);
  await fs.writeFile(path.join(destination, 'catalog.json'), 'stable');
  await assert.rejects(publishDirectory(stage, destination), /already exists/);
  assert.equal(await fs.readFile(path.join(destination, 'catalog.json'), 'utf8'), 'stable');
  await fs.rm(destination, { recursive: true });
  await publishDirectory(stage, destination);
  assert.equal(await fs.readFile(path.join(destination, 'catalog.json'), 'utf8'), 'new');
}));

test('streaming checksum matches the known SHA-256 vector', () => fixture(async directory => {
  const file = path.join(directory, 'bytes');
  await fs.writeFile(file, 'abc');
  assert.equal(await sha256(file), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
}));

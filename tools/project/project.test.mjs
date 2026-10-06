import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { projectRoot, sdkPackages } from './project.mjs';
import { checkSite, assembleSite, siteDirectory } from './site.mjs';
import { sha256, validateCatalog, publishDirectory } from './release.mjs';
import { createSigning, removeSigning, propertyValue } from './signing.mjs';
import { validateReleaseTag, validateCloudManifest, releaseByTag } from './cloud-release.mjs';

const cache = path.join(projectRoot, 'Cache/build-output.nosync/project/tests');
await fs.mkdir(cache, { recursive: true });

test('SDK packages resolve both legacy and major/minor repository names', () => {
  const repository = '<remotePackage path="platforms;android-36"><remotePackage path="platforms;android-37.0"><remotePackage path="platforms;android-37.2"><remotePackage path="build-tools;36.0.0">';
  assert.deepEqual(sdkPackages(repository, { compileSdk: 36, buildTools: '36.0.0' }), ['platforms;android-36', 'build-tools;36.0.0']);
  assert.deepEqual(sdkPackages(repository, { compileSdk: 37, buildTools: '36.0.0' }), ['platforms;android-37.0', 'build-tools;36.0.0']);
});

test('SDK resolution refuses absent releases and preview-only packages', () => {
  for (const repository of ['<remotePackage path="platforms;android-37.0">', '<remotePackage path="platforms;android-37.2-beta1"><remotePackage path="build-tools;36.0.0">']) assert.throws(() => sdkPackages(repository, { compileSdk: 37, buildTools: '36.0.0' }), /does not contain/);
});

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

test('cloud publication rejects stale numbers and tags that differ from Gradle', () => {
  const identity = { versionCode: 73, versionName: '0.18.15' };
  assert.doesNotThrow(() => validateReleaseTag('v0.18.15', identity, [69, 72]));
  for (const tag of ['v0.18.14', 'main', 'v0.18.15/extra']) assert.throws(() => validateReleaseTag(tag, identity, [72]), /tag/);
  assert.throws(() => validateReleaseTag('v0.18.15', identity, [73]), /versionCode/);
});

test('cloud artifact must belong to the exact tagged source and run', () => {
  const identity = { packageName: 'fixture', versionName: '1.0.0', versionCode: 1, minSdk: 30 };
  const manifest = { ...identity, sourceCommit: 'abc', sourceDirty: false, provenance: 'github-actions', build: { repository: 'owner/repo', runId: '123' } };
  assert.doesNotThrow(() => validateCloudManifest(manifest, identity, 'abc', 'owner/repo', '123'));
  for (const patch of [{ sourceCommit: 'old' }, { sourceDirty: true }, { versionCode: 2 }, { provenance: 'local-build' }, { build: { repository: 'other/repo', runId: '123' } }, { build: { repository: 'owner/repo', runId: '124' } }]) assert.throws(() => validateCloudManifest({ ...manifest, ...patch }, identity, 'abc', 'owner/repo', '123'), /Artifact/);
});

test('release lookup finds pending drafts and fails closed on API errors', () => {
  const draft = { id: 123, draft: true, assets: [] };
  const command = args => {
    if (args[1] === 'graphql') return JSON.stringify({ data: { repository: { release: { databaseId: 123 } } } });
    if (args[1] === 'repos/owner/repo/releases/123') return JSON.stringify(draft);
    throw new Error('A tag-only REST route cannot find this pending draft');
  };
  assert.deepEqual(releaseByTag('owner/repo', 'v1.0.0', command), draft);
  assert.equal(releaseByTag('owner/repo', 'v1.0.0', () => JSON.stringify({ data: { repository: { release: null } } })), null);
  assert.throws(() => releaseByTag('owner/repo', 'v1.0.0', () => JSON.stringify({ errors: [{ message: 'denied' }] })), /could not query/);
  assert.throws(() => releaseByTag('owner/repo', 'v1.0.0', () => { throw new Error('Network unavailable'); }), /Network unavailable/);
});

test('Java signing properties escape whitespace, line breaks and Unicode', () => {
  assert.equal(propertyValue(' a:b=c\\d\n中'), '\\ a\\:b\\=c\\\\d\\n\\u4e2d');
});

test('cloud signing requires complete secrets and retains existing local credentials', () => fixture(async directory => {
  const environment = { SIGNING_KEYSTORE_BASE64: Buffer.from('fixture').toString('base64'), SIGNING_STORE_PASSWORD: ' secret\n中', SIGNING_KEY_ALIAS: 'alias', SIGNING_KEY_PASSWORD: 'secret' };
  await assert.rejects(createSigning(directory, {}), /four signing secrets/);
  await assert.rejects(createSigning(directory, { ...environment, SIGNING_KEYSTORE_BASE64: 'bad base64' }), /base64/);
  await createSigning(directory, environment);
  assert.equal(await fs.readFile(path.join(directory, 'Cache/build-output.nosync/project/cloud-signing/release.keystore'), 'utf8'), 'fixture');
  assert.match(await fs.readFile(path.join(directory, 'keystore.properties'), 'utf8'), /storePassword=\\ secret\\n\\u4e2d/);
  await assert.rejects(createSigning(directory, environment), /replace/);
  await removeSigning(directory);
  await assert.rejects(fs.access(path.join(directory, 'keystore.properties')));
  await fs.writeFile(path.join(directory, 'keystore.properties'), 'storeFile=local.keystore\n');
  await assert.rejects(createSigning(directory, environment), /replace/);
  await removeSigning(directory);
  assert.equal(await fs.readFile(path.join(directory, 'keystore.properties'), 'utf8'), 'storeFile=local.keystore\n');
}));

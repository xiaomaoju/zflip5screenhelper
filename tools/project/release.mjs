import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { projectRoot, androidIdentity } from './project.mjs';

export async function sha256(file) {
  const digest = crypto.createHash('sha256');
  for await (const chunk of fs.createReadStream(file)) digest.update(chunk);
  return digest.digest('hex');
}

export function validateCatalog(catalog) {
  if (catalog.schemaVersion !== 1 || typeof catalog.packageName !== 'string' || !Number.isSafeInteger(catalog.versionCode) || catalog.versionCode <= 0 || !Number.isSafeInteger(catalog.minSdk) || catalog.minSdk <= 0 || !Number.isSafeInteger(catalog.apkSize) || catalog.apkSize <= 0 || typeof catalog.changelog !== 'string' || typeof catalog.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(catalog.sha256)) throw new Error('Invalid release catalog');
  if (typeof catalog.versionName !== 'string' || !/^[A-Za-z0-9._-]+$/.test(catalog.versionName)) throw new Error('Unsafe release version name');
  const match = typeof catalog.apkPath === 'string' && catalog.apkPath.match(/^releases\/(\d+)\/(flipcover-[A-Za-z0-9._-]+\.apk)$/);
  if (!match || match[1] !== String(catalog.versionCode) || ![ `flipcover-${catalog.versionName}.apk`, `flipcover-${catalog.versionName}-debug.apk` ].includes(match[2])) throw new Error('APK path does not match the release identity');
  return match[2];
}

export function inspectApk(apk) {
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  if (!sdk) throw new Error('Set ANDROID_HOME to an official Android SDK');
  const tools = path.join(sdk, 'build-tools', androidIdentity().buildTools);
  const aapt = path.join(tools, process.platform === 'win32' ? 'aapt.exe' : 'aapt');
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
  const badging = execFileSync(aapt, ['dump', 'badging', apk], { encoding: 'utf8' });
  const match = badging.match(/package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'/);
  const minSdk = badging.match(/^sdkVersion:'(\d+)'/m);
  if (!match || !minSdk) throw new Error('Cannot read the APK manifest');
  const signature = execFileSync(java, ['-jar', path.join(tools, 'lib/apksigner.jar'), 'verify', '--print-certs', apk], { encoding: 'utf8' });
  const certificates = [...signature.matchAll(/^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})$/gm)];
  if (certificates.length !== 1) throw new Error('This project requires one official APK signer');
  return { packageName: match[1], versionCode: Number(match[2]), versionName: match[3], minSdk: Number(minSdk[1]), certificateSha256: certificates[0][1] };
}

function officialCertificate() {
  const certificate = fs.readFileSync(path.join(projectRoot, 'tools/project/release-certificate.sha256'), 'utf8').trim();
  if (!/^[a-f0-9]{64}$/.test(certificate)) throw new Error('A recorded official signing certificate is required');
  return certificate;
}

export async function verifyRelease(directory) {
  const catalog = JSON.parse(await fsp.readFile(path.join(directory, 'catalog.json'), 'utf8'));
  const name = validateCatalog(catalog);
  if (path.basename(directory) !== String(catalog.versionCode)) throw new Error('Directory does not match versionCode');
  const apk = path.join(directory, name);
  if ((await fsp.stat(apk)).size !== catalog.apkSize || await sha256(apk) !== catalog.sha256) throw new Error('APK size or SHA-256 does not match catalog');
  const identity = inspectApk(apk);
  for (const field of ['packageName', 'versionCode', 'versionName', 'minSdk']) if (identity[field] !== catalog[field]) throw new Error(`APK ${field} does not match catalog`);
  if (identity.packageName !== androidIdentity().packageName || identity.certificateSha256 !== officialCertificate()) throw new Error('APK does not use the project identity and official signature');
  const manifestFile = path.join(directory, 'release-manifest.json');
  if (fs.existsSync(manifestFile)) {
    const manifest = JSON.parse(await fsp.readFile(manifestFile, 'utf8'));
    for (const field of ['packageName', 'versionCode', 'versionName', 'minSdk', 'apkSize', 'sha256']) if (manifest[field] !== catalog[field]) throw new Error(`Version manifest ${field} does not match catalog`);
    if (manifest.certificateSha256 !== identity.certificateSha256) throw new Error('Version manifest certificate does not match APK');
  }
  return { ...identity, apkSize: catalog.apkSize, sha256: catalog.sha256, status: 'passed' };
}

export async function publishDirectory(stage, destination) {
  // Callers serialize preparation with a project-local lock. Never replace an existing version.
  if (fs.existsSync(destination)) throw new Error(`Version directory already exists: ${path.basename(destination)}; use verify instead`);
  await fsp.mkdir(path.dirname(destination), { recursive: true });
  await fsp.rename(stage, destination);
}

export async function prepareRelease() {
  if (process.env.PROJECT_RELEASE_CHECKED !== '1') throw new Error('Use the Gradle prepareUpdateRelease task so build, tests and lint precede preparation');
  const git = args => execFileSync('git', args, { cwd: projectRoot, encoding: 'utf8' }).trim();
  if (git(['status', '--porcelain'])) throw new Error('Release preparation requires a clean worktree; commit and check the source first');
  const output = path.join(projectRoot, 'Cache/build-output.nosync/app/outputs/apk/release');
  const metadata = JSON.parse(fs.readFileSync(path.join(output, 'output-metadata.json'), 'utf8'));
  if (!Array.isArray(metadata.elements) || metadata.elements.length !== 1) throw new Error('Release preparation requires one complete APK');
  const outputFile = metadata.elements[0].outputFile;
  if (typeof outputFile !== 'string' || path.basename(outputFile) !== outputFile || !outputFile.endsWith('.apk')) throw new Error('Unsafe APK output path');
  const apk = path.join(output, outputFile);
  const identity = inspectApk(apk);
  const configured = androidIdentity();
  for (const field of ['packageName', 'versionCode', 'versionName', 'minSdk']) if (identity[field] !== configured[field]) throw new Error(`Built APK ${field} does not match Gradle`);
  if (identity.certificateSha256 !== officialCertificate()) throw new Error('Built APK does not use the recorded official certificate');
  const lines = fs.readFileSync(path.join(projectRoot, 'app/src/main/assets/changelog.txt'), 'utf8').split(/\r?\n/);
  const start = lines.findIndex(line => line.startsWith(identity.versionName + ' · '));
  if (start < 0) throw new Error('Changelog does not match the APK version');
  const notes = [];
  for (const line of lines.slice(start + 1)) { if (!line.trim()) break; notes.push(line); }
  if (!notes.length) throw new Error('Release changelog is empty');
  const name = `flipcover-${identity.versionName}.apk`;
  const catalog = { schemaVersion: 1, packageName: identity.packageName, versionCode: identity.versionCode, versionName: identity.versionName, minSdk: identity.minSdk, changelog: notes.join('\n'), apkPath: `releases/${identity.versionCode}/${name}`, apkSize: fs.statSync(apk).size, sha256: await sha256(apk) };
  validateCatalog(catalog);
  const destination = path.join(projectRoot, 'dist/update-release', String(identity.versionCode));
  const cache = path.join(projectRoot, 'Cache/build-output.nosync/project');
  await fsp.mkdir(cache, { recursive: true });
  const lock = path.join(cache, 'release.lock');
  await fsp.mkdir(lock).catch(() => { throw new Error('Release preparation is locked; check for another running preparation or an interrupted release'); });
  let stage;
  try {
    if (fs.existsSync(destination)) throw new Error('Version directory already exists; published version numbers cannot be reused');
    stage = await fsp.mkdtemp(path.join(cache, 'release-stage-'));
    await fsp.copyFile(apk, path.join(stage, name));
    if (await sha256(path.join(stage, name)) !== catalog.sha256) throw new Error('APK changed during preparation');
    const stagedIdentity = inspectApk(path.join(stage, name));
    for (const field of Object.keys(identity)) if (stagedIdentity[field] !== identity[field]) throw new Error('Staged APK identity or signature changed');
    const manifest = { schemaVersion: 1, ...identity, apkSize: catalog.apkSize, sha256: catalog.sha256, sourceCommit: git(['rev-parse', 'HEAD']), sourceDirty: false, provenance: process.env.GITHUB_ACTIONS === 'true' ? 'github-actions' : 'local-build', preparedAt: new Date().toISOString(), tools: { node: process.versions.node, java: process.env.PROJECT_RELEASE_JAVA, gradle: process.env.PROJECT_RELEASE_GRADLE, androidGradlePlugin: process.env.PROJECT_RELEASE_AGP, compileSdk: configured.compileSdk, buildTools: configured.buildTools }, validation: { apkSignature: 'passed', catalog: 'passed', emulator: 'not-recorded', physicalDevice: 'not-recorded' } };
    if (manifest.provenance === 'github-actions') manifest.build = { repository: process.env.GITHUB_REPOSITORY, runId: process.env.GITHUB_RUN_ID, runAttempt: process.env.GITHUB_RUN_ATTEMPT, sourceTag: process.env.GITHUB_REF_NAME };
    await fsp.writeFile(path.join(stage, 'catalog.json'), JSON.stringify(catalog, null, 2) + '\n');
    await fsp.writeFile(path.join(stage, 'release-manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
    const acceptance = manifest.provenance === 'github-actions' ? 'Cloud build intended for prerelease distribution. Emulator and Samsung physical-device acceptance have not been performed. The existing in-app update server has not been changed.' : 'Device acceptance must be recorded before stable publication.';
    await fsp.writeFile(path.join(stage, 'RELEASE.md'), `# ${identity.versionName}\n\n${catalog.changelog}\n\nSource: \`${manifest.sourceCommit}\`\n\nAPK signature, identity, size and SHA-256 verified. ${acceptance}\n`);
    if (git(['status', '--porcelain']) || git(['rev-parse', 'HEAD']) !== manifest.sourceCommit) throw new Error('Source changed during release preparation');
    await publishDirectory(stage, destination);
    console.log(`Prepared dist/update-release/${identity.versionCode}/; publication is a separate step`);
  } finally {
    if (stage && fs.existsSync(stage)) await fsp.rm(stage, { recursive: true });
    await fsp.rmdir(lock);
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    if (process.argv[2] === 'prepare') await prepareRelease();
    else if (process.argv[2] === 'verify' && process.argv[3]) console.log(JSON.stringify(await verifyRelease(path.resolve(projectRoot, process.argv[3])), null, 2));
    else throw new Error('Usage: node tools/project/release.mjs prepare | verify dist/update-release/<versionCode>');
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}

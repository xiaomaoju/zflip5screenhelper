import fs from 'node:fs/promises';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { projectRoot, androidIdentity } from './project.mjs';
import { verifyRelease, validateCatalog, sha256 } from './release.mjs';

const git = args => execFileSync('git', args, { cwd: projectRoot, encoding: 'utf8' }).trim();
const gh = args => execFileSync('gh', args, { cwd: projectRoot, encoding: 'utf8' }).trim();

export function validateReleaseTag(tag, identity, previousCodes) {
  if (!/^v\d+\.\d+\.\d+$/.test(tag) || tag !== `v${identity.versionName}`) throw new Error('Release tag must match the Gradle versionName');
  if (previousCodes.some(code => code >= identity.versionCode)) throw new Error('Release versionCode must exceed every recorded version');
}

export function previousVersionCodes(currentTag, command = git) {
  const tags = command(['tag', '--list', 'v*']).split('\n').filter(tag => /^v\d+\.\d+\.\d+$/.test(tag) && tag !== currentTag);
  const history = command(['log', '--all', '--format=', '--name-only', '--', 'dist/update-release']);
  const recorded = [...history.matchAll(/^dist\/update-release\/(\d+)\/catalog\.json$/gm)].map(match => Number(match[1]));
  return [...recorded, ...tags.map(ref => {
    const match = command(['show', `${ref}:app/build.gradle`]).match(/versionCode\s+(\d+)/);
    if (!match) throw new Error(`Cannot read versionCode from ${ref}`);
    return Number(match[1]);
  })];
}

export function validateCloudManifest(manifest, identity, commit, repository, runId) {
  if (manifest.sourceCommit !== commit || manifest.sourceDirty !== false || manifest.provenance !== 'github-actions' || manifest.build?.repository !== repository || manifest.build?.runId !== runId) throw new Error('Artifact is not from this tagged source and workflow run');
  for (const field of ['packageName', 'versionName', 'versionCode', 'minSdk']) if (manifest[field] !== identity[field]) throw new Error('Artifact does not match the tagged Gradle identity');
}

export function releaseByTag(repository, tag, command = gh) {
  const [owner, name] = repository.split('/');
  // REST releases/tags cannot find a pending draft. Match the GitHub CLI's
  // GraphQL lookup, then read the full release and asset list by database ID.
  const query = 'query($owner: String!, $name: String!, $tag: String!) { repository(owner: $owner, name: $name) { release(tagName: $tag) { databaseId } } }';
  const response = JSON.parse(command(['api', 'graphql', '-f', `query=${query}`, '-f', `owner=${owner}`, '-f', `name=${name}`, '-f', `tag=${tag}`]));
  if (response.errors || !response.data?.repository) throw new Error('GitHub could not query the release repository');
  const release = response.data.repository.release;
  return release ? JSON.parse(command(['api', `repos/${repository}/releases/${release.databaseId}`])) : null;
}

async function preflight() {
  const identity = androidIdentity();
  const tag = process.env.GITHUB_REF_NAME;
  if (process.env.GITHUB_REF_TYPE !== 'tag') throw new Error('Cloud packaging requires a version tag');
  validateReleaseTag(tag, identity, previousVersionCodes(tag));
  execFileSync('git', ['merge-base', '--is-ancestor', 'HEAD', 'origin/main'], { cwd: projectRoot });
  if (git(['status', '--porcelain'])) throw new Error('Cloud packaging requires a clean tagged checkout');
  const existing = releaseByTag(process.env.GITHUB_REPOSITORY, tag);
  if (existing && !existing.draft) throw new Error('This version is already published; do not rebuild or replace its assets');
  if (process.env.GITHUB_OUTPUT) await fs.appendFile(process.env.GITHUB_OUTPUT, `versionCode=${identity.versionCode}\nversionName=${identity.versionName}\n`);
  console.log(JSON.stringify({ tag, ...identity, status: 'passed' }));
}

async function publish() {
  if (process.env.GITHUB_REF_TYPE !== 'tag') throw new Error('Publication requires a version tag');
  const identity = androidIdentity();
  const tag = process.env.GITHUB_REF_NAME;
  validateReleaseTag(tag, identity, []);
  const repository = process.env.GITHUB_REPOSITORY;
  const directory = path.join(projectRoot, 'dist/update-release', String(identity.versionCode));
  const catalog = JSON.parse(await fs.readFile(path.join(directory, 'catalog.json'), 'utf8'));
  const manifest = JSON.parse(await fs.readFile(path.join(directory, 'release-manifest.json'), 'utf8'));
  validateCloudManifest(manifest, identity, git(['rev-parse', 'HEAD']), repository, process.env.GITHUB_RUN_ID);
  const files = [validateCatalog(catalog), 'catalog.json', 'release-manifest.json', 'RELEASE.md'];
  const entries = await fs.readdir(directory, { withFileTypes: true });
  if (entries.length !== files.length || entries.some(entry => !entry.isFile() || !files.includes(entry.name))) throw new Error('Release artifact must contain exactly the four public delivery files');
  await verifyRelease(directory);
  let release = releaseByTag(repository, tag);
  if (release && !release.draft) throw new Error('Published release assets cannot be replaced');
  if (!release) {
    gh(['release', 'create', tag, '--repo', repository, '--verify-tag', '--draft', '--prerelease', '--latest=false', '--title', `${identity.versionName} · 云端构建`, '--notes-file', path.join(directory, 'RELEASE.md')]);
    release = releaseByTag(repository, tag);
  }
  // A failed upload leaves a draft. Resume only when every existing asset is an
  // exact match; never delete or clobber an asset, including within a draft.
  const cache = path.join(projectRoot, 'Cache/build-output.nosync/project');
  await fs.mkdir(cache, { recursive: true });
  const comparison = await fs.mkdtemp(path.join(cache, 'release-download-'));
  try {
    for (const asset of release.assets) {
      if (!files.includes(asset.name)) throw new Error('Draft contains an unexpected asset');
      gh(['release', 'download', tag, '--repo', repository, '--pattern', asset.name, '--dir', comparison]);
      if (await sha256(path.join(comparison, asset.name)) !== await sha256(path.join(directory, asset.name))) throw new Error('Draft asset differs; retain it and investigate before publication');
    }
    for (const file of files) if (!release.assets.some(asset => asset.name === file)) gh(['release', 'upload', tag, path.join(directory, file), '--repo', repository]);
    for (const file of files) {
      await fs.rm(path.join(comparison, file), { force: true });
      gh(['release', 'download', tag, '--repo', repository, '--pattern', file, '--dir', comparison]);
      if (await sha256(path.join(comparison, file)) !== await sha256(path.join(directory, file))) throw new Error('Uploaded asset differs; draft retained');
    }
    gh(['release', 'edit', tag, '--repo', repository, '--draft=false', '--prerelease', '--latest=false']);
    release = releaseByTag(repository, tag);
    if (!release || release.draft || !release.prerelease || release.assets.length !== files.length) throw new Error('GitHub did not confirm the complete prerelease');
    if (process.env.GITHUB_STEP_SUMMARY) await fs.appendFile(process.env.GITHUB_STEP_SUMMARY, `Published [${tag}](${release.html_url}) as a prerelease.\n\nAPK SHA-256: \`${catalog.sha256}\`\n\nSamsung device acceptance and existing update-server publication have not been performed.\n`);
    console.log(JSON.stringify({ tag, url: release.html_url, status: 'published', prerelease: true }));
  } finally { await fs.rm(comparison, { recursive: true, force: true }); }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    if (process.argv[2] === 'preflight') await preflight();
    else if (process.argv[2] === 'publish') await publish();
    else throw new Error('Usage: node tools/project/cloud-release.mjs preflight | publish');
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}

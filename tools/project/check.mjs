import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { projectRoot, androidIdentity, sdkPackages, sdkRepositoryUrl } from './project.mjs';
import { checkSite } from './site.mjs';

try {
  if (process.argv[2] === 'sdk-packages') {
    const response = await fetch(sdkRepositoryUrl, { signal: AbortSignal.timeout(30000) });
    if (!response.ok) throw new Error(`Official SDK repository returned HTTP ${response.status}`);
    console.log(sdkPackages(await response.text()).join('\n'));
  } else {
    const git = args => execFileSync('git', args, { cwd: projectRoot, encoding: 'utf8' });
    const tracked = git(['ls-files', '-z']).split('\0').filter(Boolean);
    const files = new Set([...tracked, ...git(['ls-files', '--others', '--exclude-standard', '-z']).split('\0').filter(Boolean)]);
    const errors = [];
    const unnormalized = git(['ls-files', '--eol']).split('\n').filter(line => /^i\/(?:crlf|mixed)\s/.test(line) && /attr\/text/.test(line));
    if (unnormalized.length) errors.push(`Text index conflicts with newline attributes; normalize only the listed files with git add --renormalize:\n${unnormalized.join('\n')}`);
    const ignored = git(['ls-files', '-ci', '--exclude-standard']).trim();
    if (ignored) errors.push(`Tracked files conflict with .gitignore:\n${ignored}`);
    for (const file of files) {
      if (/(?:^|\/)(?:Cache|node_modules|build|\.gradle)\//.test(file) || /(?:^|\/)(?:keystore|key|local)\.properties$|\.(?:apk|aab|apks|jks|keystore|pem|key)$|(?:^|\/)\.env(?:\.(?!example$).*)?$/.test(file)) errors.push(`Local, generated or sensitive file in source: ${file}`);
      const full = path.join(projectRoot, file);
      if (!fs.existsSync(full)) continue;
      if (fs.statSync(full).size > 10 * 1024 * 1024) errors.push(`Source file exceeds 10 MiB: ${file}`);
      if (!/\.(?:md|mjs|json|yml|gradle|java|kt|properties)$/.test(file)) continue;
      const content = fs.readFileSync(full, 'utf8');
      if (/-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|gh[pousr]_[A-Za-z0-9_]{30,}/.test(content)) errors.push(`Credential pattern in ${file}`);
      // Historical evidence may refer to local captures; current documentation must work in a clone.
      if (!(file === 'README.md' || file === 'AGENTS.md' || (file.startsWith('docs/') && file !== 'docs/project-history.md'))) continue;
      for (const match of content.matchAll(/\]\(([^\s)]+)\)|(?:src|href)=["']([^"']+)["']/g)) {
        const reference = match[1] || match[2];
        if (/^(?:[a-z]+:|#|\/)/i.test(reference)) continue;
        const target = path.posix.normalize(path.posix.join(path.posix.dirname(file), decodeURIComponent(reference.split('#')[0])));
        if (!files.has(target)) errors.push(`${file}: untracked or missing link ${reference}`);
      }
    }
    const samples = ['Cache/probe.txt', 'local.properties', 'keystore.properties', 'dist/html/leaked.keystore', 'dist/update-release/999/probe.apk'];
    const excluded = new Set(execFileSync('git', ['check-ignore', '--no-index', '--stdin'], { cwd: projectRoot, input: samples.join('\n') + '\n', encoding: 'utf8' }).trim().split('\n'));
    for (const sample of samples) if (!excluded.has(sample)) errors.push(`Ignore rule missing: ${sample}`);
    for (const name of ['zflip5-cover-overlay.svg', 'zflip5-cover-overlay.png', 'zflip5-cover-overlay@2x.png']) {
      if (!fs.readFileSync(path.join(projectRoot, 'dist/html', name)).equals(fs.readFileSync(path.join(projectRoot, 'dist/device-frames', name)))) errors.push(`Website frame copy differs: ${name}`);
    }
    for (const file of files) {
      if (!/^dist\/update-(?:release|debug)\/\d+\/catalog\.json$/.test(file)) continue;
      const catalog = JSON.parse(fs.readFileSync(path.join(projectRoot, file), 'utf8'));
      if (catalog.schemaVersion !== 1 || catalog.packageName !== androidIdentity().packageName || catalog.versionCode !== Number(file.split('/')[2]) || !Number.isInteger(catalog.apkSize) || catalog.apkSize <= 0 || !/^[a-f0-9]{64}$/.test(catalog.sha256)) errors.push(`Invalid version record: ${file}`);
    }
    if (errors.length) throw new Error(errors.join('\n'));
    console.log(JSON.stringify({ sourceFiles: files.size, site: checkSite(), status: 'passed' }));
  }
} catch (error) { console.error(error.message); process.exitCode = 1; }

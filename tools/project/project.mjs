import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
export const sdkRepositoryUrl = 'https://dl.google.com/android/repository/repository2-4.xml';

export function sdkPackages(repository, identity = androidIdentity()) {
  const available = new Set([...repository.matchAll(/<remotePackage path="([^"]+)"/g)].map(match => match[1]));
  // Repository schema 4 represents newer base platforms as major.0. Resolve the
  // published package name instead of guessing it from the Gradle API integer.
  const platform = [`platforms;android-${identity.compileSdk}`, `platforms;android-${identity.compileSdk}.0`].find(name => available.has(name));
  const buildTools = `build-tools;${identity.buildTools}`;
  if (!platform || !available.has(buildTools)) throw new Error('The official SDK repository does not contain the platform/build tools declared by Gradle');
  return [platform, buildTools];
}

export function androidIdentity(root = projectRoot) {
  const build = fs.readFileSync(path.join(root, 'app/build.gradle'), 'utf8');
  const value = pattern => {
    const match = build.match(pattern);
    if (!match) throw new Error(`Cannot read Android identity: ${pattern}`);
    return match[1];
  };
  return {
    packageName: value(/applicationId\s+'([^']+)'/),
    versionCode: Number(value(/versionCode\s+(\d+)/)),
    versionName: value(/versionName\s+'([^']+)'/),
    minSdk: Number(value(/minSdk\s+(\d+)/)),
    compileSdk: Number(value(/compileSdk\s+(\d+)/)),
    buildTools: value(/buildToolsVersion\s+'([^']+)'/),
  };
}

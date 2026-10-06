import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

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

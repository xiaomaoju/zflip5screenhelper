import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { projectRoot } from './project.mjs';

const keyDirectory = 'Cache/build-output.nosync/project/cloud-signing';
const keyFile = `${keyDirectory}/release.keystore`;

export function propertyValue(value) {
  return value.replace(/[\\\r\n\t\f :=#!]|[^\x20-\x7e]/g, character => {
    const escapes = { '\\': '\\\\', '\r': '\\r', '\n': '\\n', '\t': '\\t', '\f': '\\f' };
    return escapes[character] || (character.charCodeAt(0) > 126 || character.charCodeAt(0) < 32 ? `\\u${character.charCodeAt(0).toString(16).padStart(4, '0')}` : `\\${character}`);
  });
}

export async function createSigning(root = projectRoot, environment = process.env) {
  const fields = ['SIGNING_KEYSTORE_BASE64', 'SIGNING_STORE_PASSWORD', 'SIGNING_KEY_ALIAS', 'SIGNING_KEY_PASSWORD'];
  if (fields.some(field => !environment[field])) throw new Error('The android-release environment requires all four signing secrets');
  const encoded = environment.SIGNING_KEYSTORE_BASE64;
  if (!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(encoded)) throw new Error('Signing keystore secret is not canonical base64');
  const directory = path.join(root, keyDirectory);
  const properties = path.join(root, 'keystore.properties');
  try { await fs.access(properties); throw new Error('Refusing to replace an existing signing configuration'); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  await fs.mkdir(path.dirname(directory), { recursive: true });
  await fs.mkdir(directory, { mode: 0o700 });
  try {
    await fs.writeFile(path.join(root, keyFile), Buffer.from(encoded, 'base64'), { mode: 0o600, flag: 'wx' });
    const values = { storeFile: keyFile, storePassword: environment.SIGNING_STORE_PASSWORD, keyAlias: environment.SIGNING_KEY_ALIAS, keyPassword: environment.SIGNING_KEY_PASSWORD };
    await fs.writeFile(properties, Object.entries(values).map(([key, value]) => `${key}=${propertyValue(value)}\n`).join(''), { mode: 0o600, flag: 'wx' });
  } catch (error) { await fs.rm(directory, { recursive: true, force: true }); throw error; }
}

export async function removeSigning(root = projectRoot) {
  const properties = path.join(root, 'keystore.properties');
  const content = await fs.readFile(properties, 'utf8').catch(error => { if (error.code === 'ENOENT') return ''; throw error; });
  if (content.startsWith(`storeFile=${keyFile}\n`)) await fs.rm(properties);
  await fs.rm(path.join(root, keyDirectory), { recursive: true, force: true });
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    if (process.argv[2] === 'create') await createSigning();
    else if (process.argv[2] === 'remove') await removeSigning();
    else throw new Error('Usage: node tools/project/signing.mjs create | remove');
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}

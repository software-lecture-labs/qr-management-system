import fs from 'node:fs';
import path from 'node:path';
import { randomBytes, randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const KEY = 'QR_PAYLOAD_ENCRYPTION_KEY';
const MARKER = '# RESTOFY-ACTIVE-PROFILE=';
const USAGE = 'Usage: node ops/qr/qr-key.mjs init|check dev|prod';

class QrKeyError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

function fail(code, message) { throw new QrKeyError(code, message); }
function sameText(first, second) { return first.replace(/\r\n/g, '\n') === second.replace(/\r\n/g, '\n'); }

function readFile(root, name, optional = false) {
  const filename = path.join(root, name);
  let stat;
  try { stat = fs.lstatSync(filename); }
  catch (error) {
    if (error.code !== 'ENOENT') throw error;
    if (optional) return null;
    fail('QR_KEY_PROFILE_MISSING', `${name} is missing. Prepare the ignored profile file from .env.example first.`);
  }
  if (!stat.isFile() || stat.isSymbolicLink() || stat.nlink !== 1) {
    fail('QR_KEY_UNSAFE_FILE', `${name} must be a regular file, not a symbolic or hard link.`);
  }
  return fs.readFileSync(filename, 'utf8');
}

function setting(text, name) {
  const rows = text.split(/\r?\n/).map((line, index) => ({ line, index }))
    .filter(({ line }) => new RegExp(`^\\s*(?:export\\s+)?${name}\\s*=`).test(line));
  if (rows.length > 1) fail('QR_KEY_DUPLICATE', `${name} appears more than once. Resolve the duplicate without sharing its values.`);
  if (!rows.length) return { value: '', index: -1 };
  const raw = rows[0].line.slice(rows[0].line.indexOf('=') + 1).trim();
  const quoted = raw.match(/^(['"])(.*?)\1\s*(?:#.*)?$/);
  const value = quoted ? quoted[2] : raw.replace(/\s+#.*$/, '').trim();
  return { value, index: rows[0].index };
}

function validKey(value) {
  if (!/^[A-Za-z0-9+/]{43}=$/.test(value)) return false;
  const bytes = Buffer.from(value, 'base64');
  try { return bytes.length === 32 && bytes.toString('base64') === value; }
  finally { bytes.fill(0); }
}

function replaceKey(text, index, value) {
  const eol = text.includes('\r\n') ? '\r\n' : '\n';
  if (index < 0) return text + (text && !text.endsWith('\n') ? eol : '') + `${KEY}=${value}${eol}`;
  const lines = text.split(/\r?\n/);
  lines[index] = `${KEY}=${value}`;
  return lines.join(eol);
}

function writeFile(root, name, expected, content) {
  if (readFile(root, name, true) !== expected) {
    fail('QR_KEY_FILE_CHANGED', `${name} changed during the operation. No automatic overwrite was attempted.`);
  }
  const target = path.join(root, name);
  const temporary = path.join(root, `.env.qr-key-${randomUUID()}.tmp`);
  try {
    fs.writeFileSync(temporary, content, { flag: 'wx', mode: 0o600 });
    fs.renameSync(temporary, target);
  } finally {
    fs.rmSync(temporary, { force: true });
  }
}

function activeFile(root, profile, original) {
  const text = readFile(root, '.env', true);
  if (text === null) return { text, selected: false, state: 'missing' };
  const first = text.split(/\r?\n/, 1)[0];
  if (!new RegExp(`^${MARKER}(dev|prod)$`).test(first)) {
    fail('QR_KEY_ACTIVE_UNMANAGED', '.env has no recognized profile marker. Reconcile it with ops/env before initializing a key.');
  }
  if (first !== MARKER + profile) return { text, selected: false, state: 'other-profile' };
  const body = text.slice(text.indexOf('\n') + 1);
  if (!sameText(body, original)) {
    fail('QR_KEY_ENV_DIVERGED', '.env differs from the selected profile. Reconcile the files first; neither file was overwritten.');
  }
  return { text, selected: true, state: 'selected' };
}

function operate(root, command, profile, log) {
  const name = `.env.${profile}`;
  const original = readFile(root, name);
  if (setting(original, 'APP_ENV').value !== profile) {
    fail('QR_KEY_PROFILE_MISMATCH', `${name} must declare APP_ENV=${profile}.`);
  }
  const entry = setting(original, KEY);
  if (entry.value && !validKey(entry.value)) {
    fail('QR_KEY_INVALID', `${KEY} must be canonical Base64 for exactly 32 bytes. The existing value was not replaced.`);
  }
  if (command === 'check' && !entry.value) {
    fail('QR_KEY_MISSING', `${KEY} is missing or empty in ${name}. Use init only for first setup; restore a lost existing key from backup.`);
  }
  const active = activeFile(root, profile, original);
  if (command === 'check') {
    log(`[OK] ${name}: QR key format is valid; key not displayed. Active ENV: ${active.state}.`);
    return { generated: false, active: active.state };
  }
  const generated = !entry.value;
  const value = entry.value || randomBytes(32).toString('base64');
  const updated = generated ? replaceKey(original, entry.index, value) : original;
  if (generated) writeFile(root, name, original, updated);
  let state = active.state;
  if (active.selected || active.state === 'missing') {
    const eol = updated.includes('\r\n') ? '\r\n' : '\n';
    const copy = MARKER + profile + eol + updated;
    if (copy !== active.text) writeFile(root, '.env', active.text, copy);
    state = 'synchronized';
  }
  log(`[OK] ${name}: QR key ${generated ? 'created' : 'preserved'}; key not displayed. Active ENV: ${state}.`);
  if (state === 'other-profile') log(`[INFO] .env was not switched. Select ${profile} explicitly with ops/env when needed.`);
  return { generated, active: state };
}

export function runQrKey({ root = ROOT, args = process.argv.slice(2), environment = process.env, log = console.log } = {}) {
  let lock;
  let status = 0;
  const lockPath = path.join(root, '.env.qr-key.lock');
  try {
    if (args.length === 1 && ['--help', '-h'].includes(args[0])) {
      log(USAGE);
    } else {
      const [command, profile] = args;
      if (args.length !== 2 || !['init', 'check'].includes(command) || !['dev', 'prod'].includes(profile)) {
        fail('QR_KEY_USAGE', USAGE);
      }
      if (Object.hasOwn(environment, KEY)) {
        fail('QR_KEY_SHELL_OVERRIDE', `${KEY} is set in the shell. Remove the override before managing profile files.`);
      }
      if (command === 'init') {
        try { lock = fs.openSync(lockPath, 'wx', 0o600); }
        catch (error) {
          if (error.code !== 'EEXIST') throw error;
          fail('QR_KEY_BUSY', 'Another key initialization may be running. Inspect .env.qr-key.lock before retrying.');
        }
      }
      operate(root, command, profile, log);
    }
  } catch (error) {
    // Filesystem/parser messages may contain private paths or file content.
    log(error instanceof QrKeyError
      ? `[ERROR] ${error.code}: ${error.message}`
      : '[ERROR] QR_KEY_IO: Operation failed. Existing profile keys are never rotated automatically; check file access and ENV synchronization.');
    status = 1;
  } finally {
    if (lock !== undefined) {
      try {
        fs.closeSync(lock);
        fs.unlinkSync(lockPath);
      } catch {
        log('[ERROR] QR_KEY_LOCK_CLEANUP: Could not release the initialization lock. Inspect the lock before retrying.');
        status = 1;
      }
    }
  }
  return status;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exitCode = runQrKey();
}

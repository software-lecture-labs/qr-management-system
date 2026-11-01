import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { runQrKey } from './qr-key.mjs';

const KEY = 'QR_PAYLOAD_ENCRYPTION_KEY';
const VALID = Buffer.alloc(32, 7).toString('base64');
const MARKER = '# RESTOFY-ACTIVE-PROFILE=';

function fixture(t, text = `APP_ENV=dev\nPOSTGRES_PASSWORD=fixture-private-password\n${KEY}=\n`) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'restofy-qr-key-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const write = (name, value) => fs.writeFileSync(path.join(root, name), value);
  const read = name => fs.readFileSync(path.join(root, name), 'utf8');
  write('.env.dev', text);
  const output = [];
  const run = (args, environment = {}) => runQrKey({ root, args, environment, log: line => output.push(line) });
  return { root, write, read, output, run };
}

test('first setup creates a 32-byte Base64 key and active copy without exposing any secret', t => {
  const f = fixture(t);
  f.write('.env.example', `APP_ENV=dev\n${KEY}=\n`);
  assert.equal(f.run(['init', 'dev']), 0);
  const profile = f.read('.env.dev');
  const value = profile.match(new RegExp(`^${KEY}=(.+)$`, 'm'))[1];
  assert.equal(Buffer.from(value, 'base64').length, 32);
  assert.equal(Buffer.from(value, 'base64').toString('base64'), value);
  assert.equal(f.read('.env'), MARKER + 'dev\n' + profile);
  assert.ok(profile.includes('POSTGRES_PASSWORD=fixture-private-password\n'));
  assert.equal(f.read('.env.example'), `APP_ENV=dev\n${KEY}=\n`);
  assert.ok(!f.output.join('\n').includes(value));
  assert.ok(!f.output.join('\n').includes('fixture-private-password'));
  assert.deepEqual(fs.readdirSync(f.root).sort(), ['.env', '.env.dev', '.env.example']);
  if (process.platform !== 'win32') assert.equal(fs.statSync(path.join(f.root, '.env.dev')).mode & 0o777, 0o600);
});

test('repeat initialization preserves the existing key, ciphertext dependency and file contents', t => {
  const f = fixture(t, `APP_ENV=dev\n${KEY}=${VALID}\n`);
  assert.equal(f.run(['init', 'dev']), 0);
  const profile = f.read('.env.dev');
  const active = f.read('.env');
  const modified = fs.statSync(path.join(f.root, '.env.dev')).mtimeMs;
  assert.equal(f.run(['init', 'dev']), 0);
  assert.equal(f.read('.env.dev'), profile);
  assert.equal(f.read('.env'), active);
  assert.equal(fs.statSync(path.join(f.root, '.env.dev')).mtimeMs, modified);
  assert.match(f.output.join('\n'), /preserved/);
});

test('a selected synchronized profile is updated together with .env and keeps CRLF and unrelated values', t => {
  const original = `APP_ENV=dev\r\n# untouched\r\nAUTH_CSRF_SECRET=fixture-private-auth\r\n${KEY}=\r\n`;
  const f = fixture(t, original);
  f.write('.env', MARKER + 'dev\r\n' + original);
  assert.equal(f.run(['init', 'dev']), 0);
  const updated = f.read('.env.dev');
  assert.equal(f.read('.env'), MARKER + 'dev\r\n' + updated);
  assert.ok(updated.includes('# untouched\r\nAUTH_CSRF_SECRET=fixture-private-auth\r\n'));
  assert.ok(!updated.replaceAll('\r\n', '').includes('\n'));
  assert.equal(f.run(['check', 'dev']), 0);
});

test('a missing key line is appended without joining the previous line', t => {
  const f = fixture(t, 'APP_ENV=dev\nPOSTGRES_PASSWORD=fixture-private-password');
  assert.equal(f.run(['init', 'dev']), 0);
  assert.match(f.read('.env.dev'), /fixture-private-password\nQR_PAYLOAD_ENCRYPTION_KEY=.+\n$/);
});

test('a different active profile is never changed or used as a source key', t => {
  const f = fixture(t);
  const prod = `APP_ENV=prod\n${KEY}=${VALID}\n`;
  f.write('.env.prod', prod);
  f.write('.env', MARKER + 'prod\n' + prod);
  assert.equal(f.run(['init', 'dev']), 0);
  assert.equal(f.read('.env.prod'), prod);
  assert.equal(f.read('.env'), MARKER + 'prod\n' + prod);
  assert.ok(!f.read('.env.dev').includes(VALID));
  assert.match(f.output.join('\n'), /was not switched/);
});

test('explicit prod initialization stays separate from an active dev profile', t => {
  const f = fixture(t, `APP_ENV=dev\n${KEY}=${VALID}\n`);
  const dev = f.read('.env.dev');
  f.write('.env', MARKER + 'dev\n' + dev);
  f.write('.env.prod', `APP_ENV=prod\n${KEY}=\n`);
  assert.equal(f.run(['init', 'prod']), 0);
  assert.equal(f.read('.env.dev'), dev);
  assert.equal(f.read('.env'), MARKER + 'dev\n' + dev);
  assert.match(f.read('.env.prod'), /QR_PAYLOAD_ENCRYPTION_KEY=.+/);
  assert.ok(!f.read('.env.prod').includes(VALID));
});

for (const active of ['APP_ENV=dev\n', MARKER + 'dev\nAPP_ENV=dev\nPRIVATE_EDIT=keep\n']) {
  test('unmanaged or diverged .env blocks initialization before any file is changed', t => {
    const f = fixture(t);
    const original = f.read('.env.dev');
    f.write('.env', active);
    assert.equal(f.run(['init', 'dev']), 1);
    assert.equal(f.read('.env.dev'), original);
    assert.equal(f.read('.env'), active);
    assert.match(f.output.join('\n'), /QR_KEY_ACTIVE_UNMANAGED|QR_KEY_ENV_DIVERGED/);
    assert.ok(!fs.existsSync(path.join(f.root, '.env.qr-key.lock')));
  });
}

for (const value of ['private-invalid-value', Buffer.alloc(31).toString('base64'), VALID.replace('=', ''), ' '.repeat(2) + 'invalid']) {
  test('invalid non-empty keys are neither printed nor replaced', t => {
    const original = `APP_ENV=dev\n${KEY}=${value}\n`;
    const f = fixture(t, original);
    assert.equal(f.run(['init', 'dev']), 1);
    assert.equal(f.read('.env.dev'), original);
    assert.match(f.output.join('\n'), /QR_KEY_INVALID/);
    assert.ok(!f.output.join('\n').includes(value.trim()));
    assert.ok(!fs.existsSync(path.join(f.root, '.env')));
  });
}

test('duplicate key definitions are rejected without changing either value', t => {
  const original = `APP_ENV=dev\n${KEY}=\nexport ${KEY}=${VALID}\n`;
  const f = fixture(t, original);
  assert.equal(f.run(['init', 'dev']), 1);
  assert.equal(f.read('.env.dev'), original);
  assert.match(f.output.join('\n'), /QR_KEY_DUPLICATE/);
  assert.ok(!f.output.join('\n').includes(VALID));
});

test('quoted existing keys and inline comments are recognized without rewriting them', t => {
  const f = fixture(t, `APP_ENV='dev' # profile\nexport ${KEY}="${VALID}" # private\n`);
  const original = f.read('.env.dev');
  assert.equal(f.run(['init', 'dev']), 0);
  assert.equal(f.read('.env.dev'), original);
  assert.equal(f.run(['check', 'dev']), 0);
});

test('check is read-only and reports a missing key without generating one', t => {
  const f = fixture(t);
  const original = f.read('.env.dev');
  assert.equal(f.run(['check', 'dev']), 1);
  assert.equal(f.read('.env.dev'), original);
  assert.match(f.output.join('\n'), /QR_KEY_MISSING/);
  assert.deepEqual(fs.readdirSync(f.root), ['.env.dev']);
});

test('check of an existing key does not create .env or lock files', t => {
  const f = fixture(t, `APP_ENV=dev\n${KEY}=${VALID}\n`);
  assert.equal(f.run(['check', 'dev']), 0);
  assert.match(f.output.join('\n'), /Active ENV: missing/);
  assert.deepEqual(fs.readdirSync(f.root), ['.env.dev']);
});

test('shell overrides, missing profiles and invalid profile identity fail without writes', t => {
  const f = fixture(t);
  const original = f.read('.env.dev');
  assert.equal(f.run(['init', 'dev'], { [KEY]: 'private-shell-value' }), 1);
  assert.match(f.output.join('\n'), /QR_KEY_SHELL_OVERRIDE/);
  assert.ok(!f.output.join('\n').includes('private-shell-value'));
  assert.equal(f.run(['init', 'prod']), 1);
  assert.match(f.output.join('\n'), /QR_KEY_PROFILE_MISSING/);
  f.write('.env.prod', `APP_ENV=dev\n${KEY}=\n`);
  assert.equal(f.run(['init', 'prod']), 1);
  assert.match(f.output.join('\n'), /QR_KEY_PROFILE_MISMATCH/);
  assert.equal(f.read('.env.dev'), original);
});

test('an existing lock is preserved and prevents concurrent initialization', t => {
  const f = fixture(t);
  const original = f.read('.env.dev');
  f.write('.env.qr-key.lock', 'existing-lock');
  assert.equal(f.run(['init', 'dev']), 1);
  assert.match(f.output.join('\n'), /QR_KEY_BUSY/);
  assert.equal(f.read('.env.dev'), original);
  assert.equal(f.read('.env.qr-key.lock'), 'existing-lock');
});

test('hard-linked profile files are rejected without changing the other file', t => {
  const f = fixture(t);
  const original = f.read('.env.dev');
  fs.linkSync(path.join(f.root, '.env.dev'), path.join(f.root, '.env.linked'));
  assert.equal(f.run(['init', 'dev']), 1);
  assert.match(f.output.join('\n'), /QR_KEY_UNSAFE_FILE/);
  assert.equal(f.read('.env.linked'), original);
});

test('invalid commands and traversal-like profiles never touch ENV files', t => {
  const f = fixture(t);
  const original = f.read('.env.dev');
  for (const args of [[], ['init'], ['rotate', 'dev'], ['init', '../dev'], ['init', 'dev', '--force']]) {
    assert.equal(f.run(args), 1);
  }
  assert.equal(f.read('.env.dev'), original);
  assert.equal(f.run(['--help']), 0);
  assert.deepEqual(fs.readdirSync(f.root), ['.env.dev']);
});

test('the real CLI entry point rejects invalid arguments without reading real profile files', () => {
  const cli = fileURLToPath(new URL('./qr-key.mjs', import.meta.url));
  const result = spawnSync(process.execPath, [cli, 'invalid-command'], { encoding: 'utf8', env: {}, timeout: 5000 });
  assert.equal(result.status, 1);
  assert.match(result.stdout, /QR_KEY_USAGE/);
  assert.equal(result.stderr, '');
});

import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const compose = fileURLToPath(new URL('../../docker-compose.yml', import.meta.url));
const KEY = 'QR_PAYLOAD_ENCRYPTION_KEY';

function config(t, key) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'restofy-qr-compose-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const text = fs.readFileSync(compose, 'utf8');
  const required = [...new Set([...text.matchAll(/\$\{([A-Z0-9_]+):\?[^}]*\}/g)].map(match => match[1]))];
  const values = required.filter(name => name !== KEY).map((name, index) => {
    const value = name.endsWith('_PORT') ? String(5200 + index)
      : name.endsWith('_URL') || name === 'CORS_ALLOWED_ORIGINS' ? 'http://127.0.0.1:9'
      : name === 'SPRING_PROFILES_ACTIVE' ? 'test' : 'fixture';
    return `${name}=${value}`;
  });
  if (key !== undefined) values.push(`${KEY}=${key}`);
  const envFile = path.join(root, 'fixture.env');
  fs.writeFileSync(envFile, values.join('\n') + '\n');
  const environment = {
    PATH: process.env.PATH,
    DOCKER_CONFIG: root,
    COMPOSE_DISABLE_ENV_FILE: '1',
    ...(process.env.SystemRoot ? { SystemRoot: process.env.SystemRoot } : {}),
    ...(process.env.ProgramFiles ? { ProgramFiles: process.env.ProgramFiles } : {}),
    ...(process.env.ProgramData ? { ProgramData: process.env.ProgramData } : {}),
  };
  // Parse the real Compose file using only dummy fixture values. No daemon,
  // real ENV files, credentials, containers or application databases are used.
  const output = execFileSync('docker', ['compose', '--env-file', envFile, '-f', compose, 'config', '--format', 'json'], {
    cwd: root, env: environment, encoding: 'utf8', timeout: 20_000, stdio: ['ignore', 'pipe', 'pipe'],
  });
  return JSON.parse(output);
}

for (const key of [undefined, '']) {
  test(`Compose parses unrelated services with a ${key === undefined ? 'missing' : 'blank'} QR key`, t => {
    const model = config(t, key);
    assert.ok(model.services['order-service']);
    assert.ok(model.services.postgres);
    assert.equal(model.services['qr-service'].environment[KEY], '');
  });
}

test('Compose still passes an explicitly configured QR key to QR only', t => {
  const value = Buffer.alloc(32, 9).toString('base64');
  const model = config(t, value);
  assert.equal(model.services['qr-service'].environment[KEY], value);
  for (const [name, service] of Object.entries(model.services)) {
    if (name !== 'qr-service') assert.equal(service.environment?.[KEY], undefined);
  }
});

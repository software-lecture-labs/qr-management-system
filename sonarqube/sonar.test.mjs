import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { gatePassed, loadSonarEnv, overallTargets, runScan, runSonar, sonarRequest, sonarWayPolicy } from './sonar.mjs';
import { redact } from '../lib/quality-tools.mjs';

const modules = ['admin-service', 'api-gateway', 'auth-service', 'kitchen-service', 'mail-service', 'menu-service', 'notification-service', 'order-service', 'payment-service', 'qr-service', 'rating-service', 'timer-service', 'waiter-service'];
const conditions = [
  { metric: 'new_coverage', op: 'LT', error: '80' },
  { metric: 'new_duplicated_lines_density', op: 'GT', error: '3' },
  { metric: 'new_violations', op: 'GT', error: '0' },
];

async function fixture(options = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'restofy-quality-test-'));
  const write = (relative, text) => {
    const filename = path.join(root, relative);
    fs.mkdirSync(path.dirname(filename), { recursive: true });
    fs.writeFileSync(filename, text);
  };
  const lines = [];
  const calls = [];
  const requests = [];
  write('.env', 'SONAR_HOST_URL=http://localhost:5099\nSONAR_TOKEN=fixture-token-no-account\n');
  write('.env.dev', options.tokenMissing ? 'SONAR_TOKEN=\n' : '');
  write('backend/pom.xml', modules.map((name) => `<module>services/${name}</module>`).join('\n'));
  write(`backend/services/admin-service/${process.platform === 'win32' ? 'mvnw.cmd' : 'mvnw'}`, 'wrapper');
  write('frontend/node_modules/vitest/package.json', '{}');
  write('frontend/node_modules/@sonar/scan/package.json', '{}');
  const runCommand = async (command, args, settings) => {
    calls.push({ command, args, env: settings.env });
    if (args.includes('up') || args.includes('stop') || args.includes('ps')) return { code: 0, output: '' };
    if (options.missingTool && args.includes('-version')) return { code: 1, output: 'Maven unavailable' };
    if (args.includes('-version')) return { code: 0, output: 'Java version: 25.0.4' };
    if (args.includes('verify')) {
      for (const name of modules) {
        const base = `backend/services/${name}/target`;
        write(`${base}/surefire-reports/TEST-context.xml`, `<testsuite tests="${options.missingTests ? 0 : 1}" failures="0" errors="0" skipped="0"/>`);
        write(`${base}/site/jacoco/jacoco.xml`, `<report><counter type="LINE" missed="${options.lowCoverage ? 80 : 0}" covered="${options.lowCoverage ? 20 : 100}"/></report>`);
        if (options.staleCoverage) fs.utimesSync(path.join(root, `${base}/site/jacoco/jacoco.xml`), 0, 0);
        write(`${base}/site/jacoco/index.html`, 'report');
        write(`${base}/classes/App.class`, 'fixture');
      }
    }
    if (args[0] === 'run' && ['test', 'test:coverage'].includes(args[1])) {
      write('frontend/test-results/vitest.json', JSON.stringify({ numTotalTests: 2, numFailedTests: 0, numPendingTests: 0 }));
      if (args[1] === 'test:coverage') {
        write('frontend/coverage/coverage-summary.json', JSON.stringify({ total: Object.fromEntries(['lines', 'branches', 'functions', 'statements'].map((key) => [key, { total: 10, covered: 10, pct: 100 }])) }));
        write('frontend/coverage/lcov.info', 'SF:src/app/page.tsx\nLF:10\nLH:10\nend_of_record\n');
      }
    }
    if (args[0] === 'audit') {
      const high = options.auditHigh && args.includes('--omit=dev') ? 1 : 0;
      return { code: high ? 1 : 0, output: JSON.stringify({ metadata: { vulnerabilities: { high, critical: 0, total: high } }, vulnerabilities: {} }) };
    }
    if (args.includes('--services')) return { code: 0, output: ['postgres', ...modules].join('\n') };
    if (args.includes('sonar:sonar') || args[1] === 'sonar') {
      const relative = args.includes('sonar:sonar') ? 'backend/target/sonar/report-task.txt' : 'frontend/.scannerwork/report-task.txt';
      if (!options.missingMetadata) write(relative, 'ceTaskId=fixture-task\n');
      return { code: options.gateFail || options.scannerFail ? 1 : 0, output: '' };
    }
    return { code: 0, output: '' };
  };
  const request = async (_host, endpoint) => {
    requests.push(endpoint);
    if (options.serverDown) throw new Error('Connection refused');
    if (endpoint === '/api/system/status') return { status: options.starting ? 'STARTING' : 'UP' };
    if (endpoint === '/api/authentication/validate') return { valid: true };
    if (endpoint.startsWith('/api/qualitygates/show')) {
      if (options.policyForbidden && !endpoint.endsWith('Sonar%20way')) {
        throw Object.assign(new Error('SonarQube API returned HTTP 403'), {status:403});
      }
      return { conditions };
    }
    if (endpoint.startsWith('/api/ce/task')) return { task: { status: options.processingFail ? 'FAILED' : 'SUCCESS', analysisId: 'fixture-analysis' } };
    if (endpoint.startsWith('/api/qualitygates/project_status')) {
      if (options.gateForbidden) throw Object.assign(new Error('SonarQube API returned HTTP 403'), {status:403});
      return { projectStatus: { status: options.gateFail ? 'ERROR' : 'OK' } };
    }
    if (endpoint.startsWith('/api/qualitygates/get_by_project')) {
      if (options.assignedStatus) throw Object.assign(new Error('SonarQube API returned HTTP ' + options.assignedStatus), {status:options.assignedStatus});
      return { qualityGate: { name: options.customGate ? 'RESTOFY Quality' : 'Sonar way' } };
    }
    if (endpoint.startsWith('/api/metrics/search')) return { metrics: ['coverage', 'new_coverage', 'duplicated_lines_density', 'new_duplicated_lines_density', 'security_rating', 'reliability_rating', 'sqale_rating'].map(key => ({ key })) };
    if (endpoint.startsWith('/api/measures')) {
      if (options.measuresForbidden) throw Object.assign(new Error('SonarQube API returned HTTP 403'), {status:403});
      return { component: { measures: ['security_rating', 'reliability_rating', 'sqale_rating'].map(metric => ({metric, value: '1'})).concat([{metric:'coverage',value:options.lowCoverage?'20':'100'},{metric:'duplicated_lines_density',value:'0'},{metric:'new_coverage',period:{value:'100'}}]) } };
    }
    throw new Error('Unexpected mock API');
  };
  try {
    const settings = { root, ambient: {}, runCommand, request, log: line => lines.push(line) };
    const result = options.action ? await runSonar({...settings, action: options.action}) : await runScan(settings);
    return { ...result, lines, calls, requests };
  } finally {
    const scratchPrefix = path.join(os.tmpdir(), 'restofy-quality-test-');
    if (!path.resolve(root).startsWith(path.resolve(scratchPrefix))) throw new Error('Invalid scratch path');
    fs.rmSync(root, { recursive: true, force: true });
  }
}

test('scan passes only after fresh reports, processing, both gates and overall targets', async () => {
  const result = await fixture();
  assert.equal(result.exitCode, 0);
  assert.ok(Object.values(result.rows).every(status => status === 'PASS'));
  assert.ok(result.requests.filter(endpoint => endpoint.startsWith('/api/ce/task')).length === 2);
  assert.ok(result.calls.every(call => !call.args.some(arg => arg.includes('fixture-token-no-account'))));
  assert.ok(result.calls.every(call => !call.args.includes('up') && !call.args.includes('stop')));
  const mavenCalls = result.calls.filter(call => call.args.includes('-version') || call.args.includes('verify') || call.args.includes('sonar:sonar'));
  assert.equal(mavenCalls.length, 3);
  assert.ok(mavenCalls.every(call => path.basename(call.command) === (process.platform === 'win32' ? 'mvnw.cmd' : 'mvnw')));
});

test('a passing custom assigned gate is accepted and its actual conditions are reported', async () => {
  const result = await fixture({customGate:true});
  assert.equal(result.exitCode, 0);
  assert.equal(result.rows['Quality Gate Backend'], 'PASS');
  assert.equal(result.rows['Quality Gate Frontend'], 'PASS');
  assert.equal(result.details.gatePolicy_Backend.name, 'RESTOFY Quality');
  assert.ok(result.requests.includes('/api/qualitygates/show?name=RESTOFY%20Quality'));
});

for (const options of [{assignedStatus:403}, {customGate:true, policyForbidden:true}]) {
  test(`configuration HTTP 403 preserves verified gates and overall results: ${JSON.stringify(options)}`, async () => {
    const result = await fixture(options);
    assert.equal(result.exitCode, 0);
    for (const area of ['Backend', 'Frontend']) {
      assert.equal(result.rows[area + ' Analysis'], 'PASS');
      assert.equal(result.rows['Quality Gate ' + area], 'PASS');
      assert.equal(result.rows['Overall Targets ' + area], 'PASS');
      assert.equal(result.details['gatePolicy_' + area].status, 'NOT VERIFIED');
    }
    assert.ok(result.lines.some(line => line.includes('configuration NOT VERIFIED (HTTP 403)')));
    assert.ok(result.lines.includes('SONAR SCAN PASSED'));
  });
}

for (const [option, row] of [['gateFail','Quality Gate Backend'], ['lowCoverage','Overall Targets Backend'], ['scannerFail','Backend Analysis']]) {
  test(`configuration HTTP 403 never overrides ${option}`, async () => {
    const result = await fixture({assignedStatus:403, [option]:true});
    assert.equal(result.exitCode, 1);
    assert.equal(result.rows[row], 'FAIL');
    assert.equal(result.details.gatePolicy_Backend.status, 'NOT VERIFIED');
    assert.ok(result.lines.includes('SONAR SCAN FAILED'));
  });
}

for (const options of [{assignedStatus:401}, {assignedStatus:500}, {gateForbidden:true}, {measuresForbidden:true}]) {
  test(`errors outside configuration HTTP 403 still fail: ${JSON.stringify(options)}`, async () => {
    const result = await fixture(options);
    assert.equal(result.exitCode, 1);
    assert.equal(result.rows['Backend Analysis'], 'FAIL');
    assert.ok(result.lines.includes('SONAR SCAN FAILED'));
  });
}

test('HTTP status is available without leaking the request token or response body', async () => {
  const server = http.createServer((_request, response) => {
    response.writeHead(403, {'Content-Type':'application/json'});
    response.end('{"sensitive":"do-not-log"}');
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    await assert.rejects(sonarRequest('http://127.0.0.1:' + server.address().port, '/api/qualitygates/get_by_project', 'fixture-token'),
      error => error.status === 403 && error.message === 'SonarQube API returned HTTP 403');
  } finally {
    await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  }
});

for (const option of ['tokenMissing', 'serverDown', 'starting']) {
  test(`${option} prevents tests and scanners from starting`, async () => {
    const result = await fixture({ [option]: true });
    assert.equal(result.exitCode, 1);
    assert.equal(result.rows['SonarQube Access'], 'FAIL');
    assert.equal(result.calls.length, 0);
    assert.equal(result.rows['Backend Analysis'], 'SKIPPED');
  });
}

for (const [option, row] of [['gateFail','Quality Gate Backend'], ['missingTests','Backend Reports'], ['staleCoverage','Backend Reports'], ['missingTool','Backend Reports'], ['lowCoverage','Overall Targets Backend']]) {
  test(`${option} fails the independent scan`, async () => {
    const result = await fixture({ [option]: true });
    assert.equal(result.exitCode, 1);
    assert.equal(result.rows[row], 'FAIL');
    assert.ok(result.lines.some(line => line.includes('SONAR SCAN FAILED')));
    if (['missingTests', 'staleCoverage', 'missingTool'].includes(option)) {
      assert.ok(!result.calls.some(call => call.args.includes('sonar:sonar')));
    }
    if (option === 'lowCoverage') {
      assert.equal(result.rows['Backend Reports'], 'PASS');
      assert.equal(result.rows['Quality Gate Backend'], 'PASS');
    }
  });
}

for (const option of ['missingMetadata', 'processingFail', 'scannerFail']) {
  test(`${option} cannot mark an analysis successful`, async () => {
    const result = await fixture({[option]:true});
    assert.equal(result.exitCode, 1);
    assert.equal(result.rows['Backend Analysis'], 'FAIL');
    assert.equal(result.rows['Frontend Analysis'], 'FAIL');
    if (option === 'scannerFail') assert.equal(result.rows['Quality Gate Backend'], 'PASS');
    else assert.equal(result.rows['Quality Gate Backend'], 'SKIPPED');
  });
}

for (const action of ['up','status','down']) {
  test(`${action} does not require a token and uses only the separate Sonar project`, async () => {
    const result = await fixture({ action, tokenMissing:true });
    assert.equal(result.exitCode, 0);
    assert.equal(result.calls.length, 1);
    const args = result.calls[0].args;
    assert.deepEqual(args.slice(0,4), ['compose','-p','restofy-sonar','--env-file']);
    assert.ok(args.includes('docker-compose.sonar.yml'));
    assert.ok(!args.includes('docker-compose.yml'));
    assert.ok(!args.includes('-v') && !args.includes('rm') && !args.includes('down'));
    if (action === 'down') {
      assert.deepEqual(args.slice(-3), ['stop','sonarqube','sonar-db']);
      assert.equal(result.requests.length, 0);
    }
  });
}

test('status returns failure for a server still starting', async () => {
  assert.equal((await fixture({action:'status', starting:true})).exitCode, 1);
});

test('new-code gate and whole-code policy have distinct metrics', () => {
  assert.equal(sonarWayPolicy(conditions), true);
  assert.equal(sonarWayPolicy(conditions.map(item => ({...item, metric:item.metric.replace('new_','')}))), false);
  assert.equal(gatePassed({projectStatus:{status:'NONE'}}), false);
  assert.equal(gatePassed({projectStatus:{status:'ERROR'}}), false);
  assert.equal(gatePassed({projectStatus:{status:'OK'}}), true);
  assert.equal(overallTargets([]), false);
  const metrics = ['security','reliability','maintainability'].map(name => ({metric:`software_quality_${name}_rating`,value:'1'}));
  metrics.push({metric:'coverage',value:'80'},{metric:'duplicated_lines_density',value:'3'});
  assert.equal(overallTargets(metrics), true);
  assert.equal(overallTargets(metrics.map(item => ({...item, value:item.metric === 'coverage' ? '79.9' : item.value}))), false);
});

test('remote or credential-bearing hosts are rejected before sending a token', async () => {
  for (const host of ['https://example.com','http://admin:password@localhost:5099','http://localhost:5099/path']) {
    await assert.rejects(sonarRequest(host,'/api/system/status','fixture-token'), /local HTTP/);
  }
});

test('ENV parsing has shell precedence and does not evaluate input', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'restofy-quality-test-'));
  try {
    fs.writeFileSync(path.join(root,'.env'), 'SONAR_TOKEN="common"\nIGNORED=$(unsafe)\n');
    fs.writeFileSync(path.join(root,'.env.dev'), 'SONAR_TOKEN=profile\n');
    assert.equal(loadSonarEnv(root,'dev',{SONAR_TOKEN:'ambient'}).SONAR_TOKEN,'ambient');
    assert.equal(loadSonarEnv(root,'dev',{}).SONAR_TOKEN,'profile');
    assert.equal(loadSonarEnv(root,'dev',{}).IGNORED,undefined);
    assert.equal(redact('before fixture-token after',['fixture-token']),'before [REDACTED] after');
    const special = 'spaces ! % & $ ( ) # token';
    fs.writeFileSync(path.join(root,'.env.dev'), 'SONAR_TOKEN="' + special + '"\n');
    assert.equal(loadSonarEnv(root,'dev',{}).SONAR_TOKEN, special);
    assert.equal(redact(special, [special]), '[REDACTED]');
  } finally {
    if (!path.resolve(root).startsWith(path.resolve(path.join(os.tmpdir(),'restofy-quality-test-')))) throw new Error('Invalid scratch path');
    fs.rmSync(root,{recursive:true,force:true});
  }
});

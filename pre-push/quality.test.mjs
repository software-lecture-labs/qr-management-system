import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { isSupportedNodeVersion, runQualityChecks } from './quality.mjs';

const modules = ['admin-service', 'api-gateway', 'auth-service', 'kitchen-service', 'mail-service', 'menu-service', 'notification-service', 'order-service', 'payment-service', 'qr-service', 'rating-service', 'timer-service', 'waiter-service'];

test('Node version policy accepts supported release lines including Node 25', () => {
  for (const version of ['22.12.0', '22.99.0', '24.0.0', '25.0.0', '26.0.0']) {
    assert.equal(isSupportedNodeVersion(version), true, version);
  }
  for (const version of ['22.11.0', '23.9.0', '21.99.0']) {
    assert.equal(isSupportedNodeVersion(version), false, version);
  }
});

async function fixture(options = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'restofy-quality-test-'));
  const write = (relative, text) => {
    const filename = path.join(root, relative);
    fs.mkdirSync(path.dirname(filename), { recursive: true });
    fs.writeFileSync(filename, text);
  };
  const lines = [];
  const calls = [];
  write('backend/pom.xml', modules.map((name) => `<module>services/${name}</module>`).join('\n'));
  write(`backend/services/admin-service/${process.platform === 'win32' ? 'mvnw.cmd' : 'mvnw'}`, 'wrapper');
  write('frontend/node_modules/vitest/package.json', '{}');
  const runCommand = async (command, args, settings) => {
    calls.push({ command, args, env: settings.env });
    if (command === 'git') {
      if (options.gitUnavailable || (options.gitWhitespace && args.includes('--check'))) return {code: 1, output: ''};
      return {code: 0, output: options.trackedSecret && args[0] === 'ls-files' ? 'frontend/.env.local\0' : options.stagedSecret && args.includes('--name-only') ? '.env.prod\0' : ''};
    }
    if (options.missingDocker && command === 'docker') return {code:1,output:'Docker CLI missing'};
    if (args.some(arg => arg.includes('sonar')) || (command === 'docker' && args.some(arg => ['up','down','stop','info'].includes(arg)))) throw new Error('Forbidden local command');
    if (options.missingTool && args.includes('-version')) return { code: 1, output: 'Maven unavailable' };
    if (args.includes('-version')) return { code: 0, output: 'Java version: 25.0.4' };
    if (options.mavenFail && args.includes('verify')) return { code: 1, output: 'Maven tests failed' };
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
    if (args[0] === 'run' && args[1] === 'build' && options.frontendBuildFail) return {code:1,output:''};
    if (args[0] === 'run' && args[1] === 'test:e2e') {
      const test = (projectName, failed, skipped) => ({projectName,status:skipped?'skipped':failed?'unexpected':'expected',results:[{status:skipped?'skipped':failed?'failed':'passed'}]});
      write('frontend/test-results/playwright/results.json', JSON.stringify({errors:[],suites:[{specs:[{tests:[test('http',options.frontendHttpFail)]},{tests:options.emptyPlaywright?[]:[test('chromium',options.playwrightFail,options.skippedPlaywright)]}]}]}));
      if(options.stalePlaywright)fs.utimesSync(path.join(root,'frontend/test-results/playwright/results.json'),0,0);
      return {code:options.playwrightFail||options.frontendHttpFail?1:0,output:''};
    }
    if (args[0] === 'audit') {
      const production = args.includes('--omit=dev');
      const high = options.auditHigh && production ? 1 : 0;
      const moderate = options.auditModerate && production ? 1 : 0;
      return { code: high || moderate || (options.auditExitFailure && production) ? 1 : 0, output: JSON.stringify({ metadata: { vulnerabilities: { high, moderate, critical: 0, total: high + moderate } }, vulnerabilities: {} }) };
    }
    if (args.includes('--services')) return { code: 0, output: ['postgres', ...modules].join('\n') };
    if (command === 'docker' && args.includes('config') && args.includes('--format')) return {code:0,output:JSON.stringify({name:'fixture',services:Object.fromEntries(modules.map((service,index)=>[service,{ports:[{target:8080,published:String(5050+index)}],environment:{BACKEND_INTERNAL_PORT:'8080'}}]))})};
    if (command === 'docker' && args.includes('ps')) return {code:0,output:JSON.stringify(options.backendStopped?[]:modules.map(service=>({Service:service,State:'running',Project:'fixture'})))};
    return { code: 0, output: '' };
  };
  try {
    const result = await runQualityChecks({ root, profile: options.profile ?? 'dev', ambient: {SONAR_TOKEN:'must-not-reach-local-commands'}, healthProbe:async()=>true, runCommand, log: (line) => lines.push(line) });
    return { ...result, lines, calls };
  } finally {
    const scratchPrefix = path.join(os.tmpdir(), 'restofy-quality-test-');
    if (!path.resolve(root).startsWith(path.resolve(scratchPrefix))) throw new Error('Invalid scratch path');
    fs.rmSync(root, { recursive: true, force: true });
  }
}

test('SAFE requires only local checks, without ENV files or scanner installation', async () => {
  const result = await fixture();
  assert.equal(result.exitCode, 0);
  assert.ok(Object.values(result.rows).every((status) => status === 'PASS'));
  assert.ok(result.lines.includes('SAFE TO COMMIT') || result.lines.some((line) => line.includes('\nSAFE TO COMMIT\n')));
  assert.ok(result.calls.every((call) => call.env.SONAR_TOKEN === undefined));
});

for (const profile of ['dev', 'prod']) {
  test(`Docker validation uses the complete ${profile} profile independently of active .env`, async () => {
    const result = await fixture({profile});
    assert.equal(result.rows['Docker Configuration'], 'PASS');
    const configurationCalls = result.calls.filter(call => call.command === 'docker' && call.args.includes('config'));
    assert.equal(configurationCalls.length, 3);
    for (const call of configurationCalls) {
      assert.deepEqual(call.args.slice(0, 3), ['compose', '--env-file', `.env.${profile}`]);
      assert.ok(!call.args.includes('.env'));
    }
  });
}

for (const [name, option, row] of [
  ['no executed backend tests', 'missingTests', 'Backend Tests'],
  ['insufficient real coverage', 'lowCoverage', 'Backend Coverage'],
  ['stale coverage', 'staleCoverage', 'Backend Coverage'],
  ['production vulnerability', 'auditHigh', 'NPM Production Audit'],
  ['moderate production vulnerability', 'auditModerate', 'NPM Production Audit'],
  ['unsuccessful production audit exit', 'auditExitFailure', 'NPM Production Audit'],
  ['failed Maven command', 'mavenFail', 'Backend Build'],
  ['missing Maven', 'missingTool', 'Backend Tests'],
  ['missing Docker CLI', 'missingDocker', 'Docker Configuration'],
  ['tracked ENV secret', 'trackedSecret', 'Git / Secret Check'],
  ['staged ENV secret', 'stagedSecret', 'Git / Secret Check'],
  ['Git whitespace error', 'gitWhitespace', 'Git / Secret Check'],
  ['unavailable Git', 'gitUnavailable', 'Git / Secret Check'],
  ['failed browser test', 'playwrightFail', 'Frontend Playwright'],
  ['skipped browser test', 'skippedPlaywright', 'Frontend Playwright'],
  ['empty browser suite', 'emptyPlaywright', 'Frontend Playwright'],
  ['stale browser report', 'stalePlaywright', 'Frontend Playwright'],
  ['failed frontend HTTP test', 'frontendHttpFail', 'Frontend HTTP Health'],
  ['stopped backend', 'backendStopped', 'Backend HTTP Health'],
]) {
  test(`${name} fails closed with a nonzero exit code`, async () => {
    const result = await fixture({ [option]: true });
    assert.equal(result.exitCode, 1);
    assert.equal(result.rows[row], 'FAIL');
    assert.ok(result.lines.some((line) => line.includes('NOT SAFE TO COMMIT')));
    assert.ok(!result.lines.some((line) => /\nSAFE TO COMMIT\n/.test(line)));
  });
}

test('a missing Docker CLI does not block frontend or backend tests', async () => {
  const result=await fixture({missingDocker:true});
  assert.equal(result.rows['Backend Tests'],'PASS');
  assert.equal(result.rows['Frontend Tests'],'PASS');
  assert.equal(result.rows['Frontend Build'],'PASS');
  assert.equal(result.rows['Docker Configuration'],'FAIL');
});

test('secret failure still runs browser, audit, Docker and backend HTTP checks', async () => {
  const result = await fixture({trackedSecret:true});
  for(const row of ['Frontend Playwright','NPM Production Audit','Docker Configuration','Backend HTTP Health']) assert.equal(result.rows[row],'PASS');
});

test('failed frontend build skips dependent browser/HTTP tests and continues independent checks', async () => {
  const result = await fixture({frontendBuildFail:true});
  assert.equal(result.rows['Frontend Build'],'FAIL');
  assert.equal(result.rows['Frontend Playwright'],'SKIPPED');
  assert.equal(result.rows['Frontend HTTP Health'],'SKIPPED');
  assert.equal(result.rows['NPM Production Audit'],'PASS');
  assert.equal(result.rows['Backend HTTP Health'],'PASS');
  assert.ok(!result.calls.some(call=>call.args.includes('test:e2e')));
});

test('unavailable Maven leaves the unexecuted build SKIPPED', async () => {
  const result = await fixture({missingTool: true});
  assert.equal(result.rows['Backend Tests'], 'FAIL');
  assert.equal(result.rows['Backend Build'], 'SKIPPED');
  assert.equal(result.rows['Frontend Tests'], 'PASS');
  assert.equal(result.exitCode, 1);
});

import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { parseArguments, selectFromMenu, runPrePush } from './menu.mjs';
import { runBackendChecks } from './backend-check.mjs';

const modules = ['admin-service', 'api-gateway', 'auth-service', 'kitchen-service', 'mail-service', 'menu-service', 'notification-service', 'order-service', 'payment-service', 'qr-service', 'rating-service', 'timer-service', 'waiter-service'].map(name => 'services/' + name);

async function scratch(action) {
  const prefix = path.join(os.tmpdir(), 'restofy-menu-test-');
  const root = fs.mkdtempSync(prefix);
  const write = (relative, text) => {
    const filename = path.join(root, relative);
    fs.mkdirSync(path.dirname(filename), { recursive: true });
    fs.writeFileSync(filename, text);
  };
  write('backend/pom.xml', modules.map(module => `<module>${module}</module>`).join('\n'));
  try { await action(root, write); }
  finally {
    if (!path.resolve(root).startsWith(path.resolve(prefix))) throw new Error('Invalid scratch path');
    fs.rmSync(root, { recursive: true, force: true });
  }
}

test('CLI defaults to menu, supports direct service/full/backend, and keeps dev/prod compatibility', () => {
  assert.equal(parseArguments([], modules).mode, 'menu');
  assert.deepEqual(parseArguments(['auth-service'], modules), {mode:'backend', service:'auth-service', profile:'dev'});
  assert.deepEqual(parseArguments(['backend'], modules), {mode:'backend', profile:'dev'});
  assert.deepEqual(parseArguments(['full','prod'], modules), {mode:'full', profile:'prod'});
  assert.deepEqual(parseArguments(['dev'], modules), {mode:'full', profile:'dev'});
  assert.deepEqual(parseArguments(['prod'], modules), {mode:'full', profile:'prod'});
  for (const args of [['unknown-service'], ['auth-service;echo'], ['full','bad'], ['full','dev','extra']]) assert.throws(() => parseArguments(args,modules), /Usage/);
});

test('menu lists every service and retries invalid input without starting a test', async () => {
  const lines = [];
  const answers = ['99', 'invalid', '3'];
  const choice = await selectFromMenu(modules, async () => answers.shift(), line => lines.push(line));
  assert.deepEqual(choice, {mode:'backend',service:'auth-service'});
  assert.ok(modules.every(module => lines.some(line => line.endsWith(module.split('/')[1]))));
  assert.ok(lines.some(line => line.includes('Tüm servislerin testleri')));
  assert.ok(lines.some(line => line.includes('Tam kalite kontrolü')));
  assert.equal(lines.filter(line => line.startsWith('[ERROR]')).length, 2);
  assert.deepEqual(await selectFromMenu(modules, async () => 'auth-service', () => {}), choice);
  assert.deepEqual(await selectFromMenu(modules, async () => '14', () => {}), {mode:'backend'});
  assert.deepEqual(await selectFromMenu(modules, async () => '15', () => {}), {mode:'full'});
});

test('cancellation and a non-interactive empty command never start long-running checks', async () => {
  await scratch(async root => {
    let runs = 0;
    const run = async () => { runs++; return {exitCode:0}; };
    for (const answer of ['0', null]) {
      const result = await runPrePush({root,args:[],ask:async()=>answer,runBackend:run,runFull:run,log:()=>{}});
      assert.equal(result.exitCode,0);
    }
    assert.equal((await runPrePush({root,args:[],interactive:false,runBackend:run,runFull:run,log:()=>{}})).exitCode,1);
    assert.equal(runs,0);
  });
});

test('menu dispatches only the selected mode and preserves its failure exit code', async () => {
  await scratch(async root => {
    const calls = [];
    const settings = {root,log:()=>{},runBackend:async args=>{calls.push(['backend',args.service]);return {exitCode:1};},runFull:async args=>{calls.push(['full',args.profile]);return {exitCode:0};}};
    assert.equal((await runPrePush({...settings,args:[],ask:async()=>'3'})).exitCode,1);
    assert.equal((await runPrePush({...settings,args:['backend']})).exitCode,1);
    assert.equal((await runPrePush({...settings,args:['full','prod']})).exitCode,0);
    assert.deepEqual(calls,[['backend','auth-service'],['backend','all'],['full','prod']]);
    assert.ok(!fs.existsSync(path.join(root,'test-results/quality.lock')));
  });
});

test('an existing report lock prevents checks and preserves the other process lock', async () => {
  await scratch(async (root,write) => {
    write('test-results/quality.lock','other-process');
    let runs=0;
    const lines=[];
    const result=await runPrePush({root,args:['auth-service'],log:line=>lines.push(line),runBackend:async()=>{runs++;return {exitCode:0};}});
    assert.equal(result.exitCode,1);
    assert.equal(runs,0);
    assert.equal(fs.readFileSync(path.join(root,'test-results/quality.lock'),'utf8'),'other-process');
    assert.ok(lines.some(line=>line.includes('Başka bir test veya analiz')));
  });
});

async function backendFixture(options, action) {
  await scratch(async (root, write) => {
    write('frontend/coverage/keep.txt','frontend report');
    write('backend/services/mail-service/target/surefire-reports/keep.txt','other service report');
    write(`backend/services/admin-service/${process.platform === 'win32' ? 'mvnw.cmd' : 'mvnw'}`, 'wrapper');
    const lines = [], calls = [];
    const runCommand = async (command,args,settings) => {
      calls.push({command,args,env:settings.env});
      assert.equal(command, path.join(root, 'backend', 'services', 'admin-service', process.platform === 'win32' ? 'mvnw.cmd' : 'mvnw'));
      assert.equal(settings.env.SONAR_TOKEN,undefined);
      if (args.includes('-version')) return {code:options.missingMaven?1:0,output:'Java version: 25.0.4'};
      const selected = args.includes('-pl') ? [args[args.indexOf('-pl')+1]] : modules;
      for (const module of selected) {
        if (options.missingReports) continue;
        const base = `backend/${module}/target`;
        write(`${base}/surefire-reports/TEST-context.xml`, `<testsuite tests="${options.emptyTests?0:1}" failures="${options.failedTests?1:0}" errors="0" skipped="0"/>`);
        write(`${base}/site/jacoco/jacoco.xml`, `<report><counter type="LINE" missed="${options.lowCoverage?80:0}" covered="100"/></report>`);
        if (options.stale) fs.utimesSync(path.join(root,`${base}/site/jacoco/jacoco.xml`),0,0);
        write(`${base}/site/jacoco/index.html`,'report');
        write(`${base}/classes/App.class`,'fixture');
      }
      return {code:options.buildFail?1:0,output:''};
    };
    const result = await runBackendChecks({root,service:options.service??'auth-service',ambient:{SONAR_TOKEN:'do-not-forward'},runCommand,log:line=>lines.push(line)});
    await action({root,result,calls,lines});
  });
}

test('single service runs only its Maven project and preserves unrelated reports', async () => {
  await backendFixture({},async ({root,result,calls,lines})=>{
    assert.equal(result.exitCode,0);
    assert.deepEqual(Object.keys(result.services),['auth-service']);
    assert.ok(calls.some(call=>call.args.includes('-pl') && call.args.includes('services/auth-service')));
    assert.equal(fs.readFileSync(path.join(root,'frontend/coverage/keep.txt'),'utf8'),'frontend report');
    assert.equal(fs.readFileSync(path.join(root,'backend/services/mail-service/target/surefire-reports/keep.txt'),'utf8'),'other service report');
    assert.ok(!lines.some(line=>line.includes('SAFE TO COMMIT')));
  });
});

test('all backend services are reported individually without frontend or Docker commands', async () => {
  await backendFixture({service:'all'},async ({result,calls,lines})=>{
    assert.equal(result.exitCode,0);
    assert.equal(Object.keys(result.services).length,13);
    assert.ok(calls.every(call=>!call.args.includes('-pl')));
    assert.ok(calls.some(call=>call.args.includes('--fail-at-end')));
    assert.ok(Object.values(result.services).every(row=>row.tests==='PASS'&&row.coverage==='PASS'));
    assert.ok(!lines.some(line=>line.includes('SAFE TO COMMIT')));
  });
});

for (const option of ['missingMaven','missingReports','emptyTests','failedTests','lowCoverage','stale','buildFail']) {
  test(`backend-only ${option} returns failure without a false success message`, async () => {
    await backendFixture({[option]:true},async ({result,lines})=>{
      assert.equal(result.exitCode,1);
      assert.ok(!lines.includes('BACKEND TEST CHECK PASSED'));
    });
  });
}

test('unknown service cannot reach Maven or delete reports', async () => {
  await backendFixture({service:'unknown-service'},async ({root,result,calls})=>{
    assert.equal(result.exitCode,1);
    assert.equal(calls.length,0);
    assert.ok(fs.existsSync(path.join(root,'backend/services/mail-service/target/surefire-reports/keep.txt')));
  });
});

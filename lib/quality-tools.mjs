import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { randomUUID } from 'node:crypto';

export const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export function redact(value, secrets = []) {
  let result = String(value);
  for (const secret of secrets.filter(Boolean)) result = result.split(secret).join('[REDACTED]');
  return result;
}

export async function withRunLog(root, label, action, { log = console.log, secrets = [] } = {}) {
  if (!/^[a-z][a-z0-9-]*$/.test(label)) throw new Error('Invalid log label');
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const filename = generatedPath(root, `logs/${label}-${stamp}-${process.pid}-${randomUUID().slice(0, 8)}.log`);
  fs.mkdirSync(path.dirname(filename), { recursive: true });
  const fd = fs.openSync(filename, 'wx', 0o600);
  const say = value => {
    const text = redact(value, secrets);
    fs.writeSync(fd, text + '\n', null, 'utf8');
    log(text);
  };
  try {
    say('[LOG] ' + path.relative(root, filename));
    return await action(say);
  } catch (error) {
    say('[ERROR] ' + error.message);
    throw error;
  } finally {
    fs.closeSync(fd);
  }
}

export function execute(command, args, { cwd, env, capture = false, secrets = [], log = console.log }) {
  return new Promise((resolve) => {
    // npm/Maven launchers and the repository Maven Wrapper are Windows batch files.
    const windowsCommand = process.platform === 'win32' && /^(npm|mvn|mvnw)\.cmd$/.test(path.basename(command));
    const options = { cwd, env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] };
    if (windowsCommand && [command, ...args].some((arg) => /[&|<>^%!\r\n"]/.test(arg))) {
      resolve({ code: 1, output: 'Unsafe Windows command argument' });
      return;
    }
    // A bare launcher name (npm.cmd, mvn.cmd) must stay unquoted: cmd resolves a quoted bare name through PATH with a
    // wrong %~dp0 and npm.cmd then looks for its own files under the working directory. Only a path is quoted.
    const launcher = path.basename(command) === command ? command : `"${command}"`;
    const child = windowsCommand
      ? spawn(env.ComSpec || 'cmd.exe', ['/d', '/s', '/c', `"${launcher} ${args.map(arg => `"${arg}"`).join(' ')}"`], { ...options, windowsVerbatimArguments: true })
      : spawn(command, args, options);
    let output = '';
    const buffers = new Map();
    for (const stream of [child.stdout, child.stderr]) {
      buffers.set(stream, '');
      stream.on('data', (chunk) => {
        const text = chunk.toString();
        output += text;
        if (!capture) {
          const lines = (buffers.get(stream) + text).split(/\r?\n/);
          buffers.set(stream, lines.pop());
          for (const line of lines) log(redact(line, secrets));
        }
      });
    }
    child.on('error', (error) => resolve({ code: 1, output: error.message }));
    child.on('close', (code) => {
      if (!capture) for (const text of buffers.values()) if (text) log(redact(text, secrets));
      resolve({ code: code ?? 1, output });
    });
  });
}

export function generatedPath(root, relative) {
  const target = path.resolve(root, relative);
  if (!target.startsWith(path.resolve(root) + path.sep)) throw new Error('Generated path escapes repository');
  let current = path.resolve(root);
  for (const part of path.relative(root, target).split(path.sep)) {
    current = path.join(current, part);
    if (fs.existsSync(current) && fs.lstatSync(current).isSymbolicLink()) throw new Error('Refusing generated output through a symbolic link');
  }
  return target;
}

export function cleanGenerated(root, relative) {
  fs.rmSync(generatedPath(root, relative), { force: true, recursive: true });
}

export function freshFile(root, relative, started) {
  const filename = generatedPath(root, relative);
  if (!fs.existsSync(filename) || fs.statSync(filename).mtimeMs < started - 2000) throw new Error(`Missing fresh report: ${relative}`);
  return fs.readFileSync(filename, 'utf8').replace(/^\uFEFF/, '');
}

export function backendTests(root, modules, started) {
  let tests = 0;
  for (const module of modules) {
    const base = `backend/${module}/target`;
    const reportDir = generatedPath(root, `${base}/surefire-reports`);
    const reports = fs.readdirSync(reportDir).filter((file) => /^TEST-.*\.xml$/.test(file));
    let moduleTests = 0;
    for (const file of reports) {
      const xml = freshFile(root, `${base}/surefire-reports/${file}`, started);
      const suite = xml.match(/<testsuite\b[^>]*>/)?.[0] ?? '';
      const attr = (name) => Number(suite.match(new RegExp(`\\b${name}="([0-9]+)"`))?.[1] ?? 0);
      if (attr('failures') || attr('errors') || attr('skipped')) throw new Error(`Failed/skipped tests: ${module}`);
      moduleTests += attr('tests');
    }
    if (moduleTests === 0) throw new Error(`No executed tests: ${module}`);
    tests += moduleTests;
  }
  return tests;
}

export function backendResults(root, modules, started) {
  const tests = backendTests(root, modules, started);
  let covered = 0, missed = 0;
  for (const module of modules) {
    const base = `backend/${module}/target`;
    const xml = freshFile(root, `${base}/site/jacoco/jacoco.xml`, started);
    const counters = [...xml.matchAll(/<counter\s+type="LINE"\s+missed="(\d+)"\s+covered="(\d+)"\s*\/>/g)];
    const total = counters.at(-1);
    if (!total) throw new Error(`No measurable JaCoCo line counter: ${module}`);
    missed += Number(total[1]); covered += Number(total[2]);
    freshFile(root, `${base}/site/jacoco/index.html`, started);
    if (!fs.readdirSync(generatedPath(root, `${base}/classes`), { recursive: true }).some((file) => file.endsWith('.class'))) throw new Error(`Missing bytecode: ${module}`);
  }
  if (covered + missed === 0) throw new Error('Backend coverage is not measurable');
  return { tests, covered, missed, lineCoverage: 100 * covered / (covered + missed) };
}

export function readModules(root) {
  const modules = [...fs.readFileSync(path.join(root, 'backend/pom.xml'), 'utf8').matchAll(/<module>(.*?)<\/module>/g)].map((item) => item[1]);
  if (modules.length !== 13 || modules.some((item) => !/^services\/[a-z][a-z0-9-]*$/.test(item))) throw new Error('Expected 13 backend service modules');
  return modules;
}

export function clearBackendReports(root, modules) {
  for (const module of modules) for (const output of ['jacoco.exec', 'site/jacoco', 'surefire-reports']) cleanGenerated(root, `backend/${module}/target/${output}`);
}

export function clearReports(root, modules) {
  clearBackendReports(root, modules);
  cleanGenerated(root, 'frontend/coverage');
  cleanGenerated(root, 'frontend/test-results');
}

export function frontendResults(root, started) {
  const summary = JSON.parse(freshFile(root, 'frontend/coverage/coverage-summary.json', started)).total;
  const tests = JSON.parse(freshFile(root, 'frontend/test-results/vitest.json', started));
  const lcov = freshFile(root, 'frontend/coverage/lcov.info', started);
  if (!(tests.numTotalTests > 0) || tests.numFailedTests !== 0 || tests.numPendingTests !== 0 || !/^SF:/m.test(lcov) || !(summary.lines.total > 0)) throw new Error('Missing measurable coverage or successful frontend tests');
  return { tests, summary };
}

export async function withReportLock(root, action) {
  const lock = generatedPath(root, 'test-results/quality.lock');
  fs.mkdirSync(path.dirname(lock), {recursive:true});
  let acquired = false;
  try {
    const fd = fs.openSync(lock, 'wx');
    acquired = true;
    try { fs.writeFileSync(fd, String(process.pid)); } finally { fs.closeSync(fd); }
    return await action();
  } finally { if (acquired) fs.unlinkSync(lock); }
}

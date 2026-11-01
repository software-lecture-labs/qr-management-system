import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { repositoryRoot, execute, generatedPath, freshFile, backendTests, backendResults, readModules, clearReports, withReportLock, withRunLog } from '../lib/quality-tools.mjs';
import { requireMaven25, resolveMavenCommand } from './backend-check.mjs';
import { checkGitSecrets, checkBackendHealth, playwrightResults } from './runtime-checks.mjs';

export const requiredRows = ['Git / Secret Check', 'Backend Tests', 'Backend Build', 'Backend Coverage', 'Frontend ESLint', 'Frontend TypeScript', 'Frontend Tests', 'Frontend Coverage', 'Frontend Build', 'Frontend HTTP Health', 'Frontend Playwright', 'NPM Production Audit', 'Docker Configuration', 'Backend HTTP Health'];

export function isSupportedNodeVersion(version = process.versions.node) {
  const [major, minor] = String(version).split('.').map(Number);
  return (major === 22 && minor >= 12) || major >= 24;
}

export function formatQualityStatus(value, stream = process.stdout) {
  const text = String(value);
  if (!stream.isTTY || Object.hasOwn(process.env, 'NO_COLOR') || process.env.TERM === 'dumb') return text;
  return text.replace(/^(NOT SAFE TO COMMIT|SAFE TO COMMIT)$/gm, status =>
    `\u001b[${status === 'SAFE TO COMMIT' ? 32 : 31}m${status}\u001b[0m`);
}

export async function runQualityChecks({ root = repositoryRoot, profile = 'dev', ambient = process.env, runCommand = execute, healthProbe, log = console.log } = {}) {
  const rows = Object.fromEntries(requiredRows.map((name) => [name, 'SKIPPED']));
  const details = {};
  let env = process.env;
  const say = log;
  const run = (command, args, cwd = root, capture = false) => runCommand(command, args, { cwd, env, capture, log: say });
  const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm';
  const mvn = resolveMavenCommand(root);
  const front = path.join(root, 'frontend');
  const started = Date.now();
  async function step(name, action) {
    say(`\n[CHECK] ${name}`);
    try { rows[name] = await action() ? 'PASS' : 'FAIL'; }
    catch (error) { rows[name] = 'FAIL'; details[name] = error.message; say(`[ERROR] ${details[name]}`); }
  }
  try {
    if (!['dev', 'prod'].includes(profile)) throw new Error('Usage: pre-push.cmd [dev|prod]');
    env = Object.fromEntries(Object.entries(ambient).filter(([name]) => !name.startsWith('SONAR')));
    const modules = readModules(root);
    if (!isSupportedNodeVersion()) throw new Error('Node.js 22.12+ or 24+ is required');
    clearReports(root, modules);

    await step('Git / Secret Check', () => checkGitSecrets(run, say));

    await step('Backend Tests', async () => {
      await requireMaven25(run, mvn, root);
      const result = await run(mvn, ['-B', '-ntp', '-f', 'backend/pom.xml', 'clean', 'verify']);
      rows['Backend Build'] = result.code === 0 ? 'PASS' : 'FAIL';
      return result.code === 0 && backendTests(root, modules, started) > 0;
    });
    await step('Backend Coverage', async () => {
      if (rows['Backend Tests'] !== 'PASS') throw new Error('Backend build/tests failed; coverage cannot be accepted');
      const result = backendResults(root, modules, started);
      details.backend = result;
      say(`Backend: ${result.tests} tests; overall line coverage ${result.lineCoverage.toFixed(2)}% (target 80%)`);
      return result.lineCoverage >= 80;
    });
    for (const [label, script] of [['Frontend ESLint', 'lint'], ['Frontend TypeScript', 'typecheck']]) await step(label, async () => (await run(npm, ['run', script], front)).code === 0);
    await step('Frontend Tests', async () => {
      const result = await run(npm, ['run', 'test'], front);
      const report = JSON.parse(freshFile(root, 'frontend/test-results/vitest.json', started));
      details.frontendTests = report.numTotalTests;
      return result.code === 0 && report.numTotalTests > 0 && report.numFailedTests === 0 && report.numPendingTests === 0;
    });
    await step('Frontend Coverage', async () => {
      const result = await run(npm, ['run', 'test:coverage'], front);
      const summary = JSON.parse(freshFile(root, 'frontend/coverage/coverage-summary.json', started)).total;
      const report = JSON.parse(freshFile(root, 'frontend/test-results/vitest.json', started));
      const lcov = freshFile(root, 'frontend/coverage/lcov.info', started);
      const frontendReports = report.numTotalTests > 0 && report.numFailedTests === 0 && report.numPendingTests === 0 && /^SF:/m.test(lcov) && summary.lines.total > 0;
      details.frontendCoverage = summary;
      say(`Frontend overall line coverage: ${summary.lines.pct}% (target 80%)`);
      return result.code === 0 && frontendReports && ['lines', 'branches', 'functions', 'statements'].every((metric) => summary[metric].pct >= 80);
    });
    await step('Frontend Build', async () => (await run(npm, ['run', 'build'], front)).code === 0);
    if (rows['Frontend Build'] === 'PASS') {
      await step('Frontend Playwright', async () => {
        rows['Frontend HTTP Health'] = 'FAIL';
        const result = await run(npm, ['run', 'test:e2e'], front);
        const report = playwrightResults(JSON.parse(freshFile(root, 'frontend/test-results/playwright/results.json', started)));
        details.playwright = report;
        rows['Frontend HTTP Health'] = report.http.passed && report.errors === 0 ? 'PASS' : 'FAIL';
        say(`Playwright: ${report.browser.tests} browser tests; ${report.http.tests} frontend HTTP tests.`);
        return result.code === 0 && report.browser.passed && report.errors === 0;
      });
    } else {
      say('[SKIPPED] Frontend HTTP/Playwright require a successful fresh frontend build.');
    }
    await step('NPM Production Audit', async () => {
      const production = await run(npm, ['audit', '--omit=dev', '--json'], front, true);
      const all = await run(npm, ['audit', '--json'], front, true);
      for (const [name, result, filename] of [['production', production, 'audit-production.json'], ['all', all, 'audit-all.json']]) {
        const report = JSON.parse(result.output);
        if (!report.metadata?.vulnerabilities || report.error || ![0, 1].includes(result.code)) throw new Error(`NPM ${name} audit unavailable`);
        fs.writeFileSync(generatedPath(root, `frontend/coverage/${filename}`), JSON.stringify(report, null, 2));
        details[`audit_${name}`] = report.metadata.vulnerabilities;
        say(`NPM ${name} audit: ${JSON.stringify(report.metadata.vulnerabilities)}`);
        for (const [pkg, issue] of Object.entries(report.vulnerabilities ?? {})) say(`  ${pkg}: ${issue.severity}`);
      }
      say('Policy: any failed production audit blocks; development-only findings are reported as warnings. No automatic fixes.');
      return production.code === 0 && details.audit_production.total === 0;
    });
    await step('Docker Configuration', async () => {
      const version = await run('docker', ['compose', 'version'], root, true);
      if (version.code !== 0) throw new Error('Docker CLI/Compose unavailable; other local checks were still executed');
      const common = ['compose', '--env-file', `.env.${profile}`];
      const main = await run('docker', [...common, '-f', 'docker-compose.yml', 'config', '--quiet']);
      const services = await run('docker', [...common, '-f', 'docker-compose.yml', 'config', '--services'], root, true);
      const expected = ['postgres', ...modules.map((module) => module.split('/')[1])].sort();
      const actual = services.output.trim().split(/\r?\n/).sort();
      return main.code === 0 && services.code === 0 && JSON.stringify(expected) === JSON.stringify(actual);
    });
    await step('Backend HTTP Health', async () => {
      const report = await checkBackendHealth({ run, profile, modules, probe: healthProbe, log: say });
      details.backendHttp = report;
      return report.passed;
    });

  } catch (error) { say(`[ERROR] ${error.message}`); }
  const safe = requiredRows.every((name) => rows[name] === 'PASS');
  say('\n========================================\nRESTOFY LOCAL QUALITY CHECK\n========================================');
  for (const name of requiredRows) say(`${name.padEnd(24)} ${rows[name]}`);
  say(`\n========================================\n${safe ? 'SAFE TO COMMIT' : 'NOT SAFE TO COMMIT'}\n========================================`);
  return { exitCode: safe ? 0 : 1, rows, details };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = (await withRunLog(repositoryRoot, 'pre-push', async log => {
      try {
        return await withReportLock(repositoryRoot, () => runQualityChecks({profile:process.argv[2] ?? 'dev', log}));
      } catch (error) {
        log('Local quality runner could not start: '+error.message+'\nNOT SAFE TO COMMIT');
        return {exitCode:1};
      }
    }, {log: value => console.log(formatQualityStatus(value))})).exitCode;
  } catch(error) {
    console.error(formatQualityStatus('Local quality runner could not start: '+error.message+'\nNOT SAFE TO COMMIT', process.stderr));
    process.exitCode=1;
  }
}

import fs from 'node:fs';
import path from 'node:path';
import { repositoryRoot, execute, backendTests, backendResults, readModules, clearBackendReports } from '../lib/quality-tools.mjs';

export function resolveMavenCommand(root, platform = process.platform) {
	const wrapper = path.join(root, 'backend', 'services', 'admin-service', platform === 'win32' ? 'mvnw.cmd' : 'mvnw');
	return fs.existsSync(wrapper) ? wrapper : platform === 'win32' ? 'mvn.cmd' : 'mvn';
}

export async function requireMaven25(run, mvn, root) {
  const version = await run(mvn, ['-version'], root, true);
  if (version.code !== 0) throw new Error('Maven/Java unavailable: ' + version.output);
  if (!/Java version:\s*25[.,]/.test(version.output)) throw new Error('Maven must use JDK 25; check JAVA_HOME');
}

export async function runBackendChecks({ root = repositoryRoot, service = 'all', ambient = process.env, runCommand = execute, log = console.log } = {}) {
  const services = {};
  const started = Date.now();
  let buildPassed = false;
  try {
    const available = readModules(root);
    const selected = service === 'all' ? available : available.filter(module => module === `services/${service}`);
    if (!selected.length) throw new Error('Unknown backend service; use a service listed in the menu');
    for (const module of selected) services[module.split('/')[1]] = { tests: 'NOT RUN', coverage: 'NOT RUN', testCount: 0, lineCoverage: null };
    log('[SCOPE] ' + (service === 'all' ? 'Tüm backend servisleri' : service));
    const env = Object.fromEntries(Object.entries(ambient).filter(([name]) => !name.startsWith('SONAR')));
    const run = (command, args, cwd = root, capture = false) => runCommand(command, args, { cwd, env, capture, log });
		const mvn = resolveMavenCommand(root);
    await requireMaven25(run, mvn, root);
    clearBackendReports(root, selected);
    const args = ['-B', '-ntp', '-f', 'backend/pom.xml', '--fail-at-end'];
    if (service !== 'all') args.push('-pl', selected[0]);
    args.push('clean', 'verify');
    const result = await run(mvn, args);
    buildPassed = result.code === 0;
    if (!buildPassed) log('[ERROR] Maven verify failed with exit code ' + result.code);
    for (const module of selected) {
      const row = services[module.split('/')[1]];
      try {
        row.testCount = backendTests(root, [module], started);
        row.tests = 'PASS';
      } catch (error) {
        row.tests = 'FAIL';
        row.error = error.message;
        log('[ERROR] ' + module + ': ' + error.message);
        continue;
      }
      try {
        const report = backendResults(root, [module], started);
        row.lineCoverage = report.lineCoverage;
        row.coverage = report.lineCoverage >= 80 ? 'PASS' : 'FAIL';
      } catch (error) {
        row.coverage = 'FAIL';
        row.error = error.message;
        log('[ERROR] ' + module + ': ' + error.message);
      }
    }
  } catch (error) {
    log('[ERROR] ' + error.message);
  }
  const passed = buildPassed && Object.keys(services).length > 0 && Object.values(services).every(row => row.tests === 'PASS' && row.coverage === 'PASS');
  log('\n========================================\nRESTOFY BACKEND TEST CHECK\n========================================');
  log('Service'.padEnd(24) + 'Tests       Coverage    Count    Lines (target 80%)');
  for (const [name, row] of Object.entries(services)) {
    log(name.padEnd(24) + row.tests.padEnd(12) + row.coverage.padEnd(12) + String(row.testCount).padEnd(9) + (row.lineCoverage === null ? 'N/A' : row.lineCoverage.toFixed(2) + '%'));
  }
  log('Maven Verify             ' + (buildPassed ? 'PASS' : 'FAIL'));
  log(passed ? 'BACKEND TEST CHECK PASSED' : 'BACKEND TEST CHECK FAILED');
  log("Bu sonuç seçilen backend kapsamına aittir. Tam kalite kontrolü için 'full' kullanın.");
  return { exitCode: passed ? 0 : 1, services, buildPassed };
}

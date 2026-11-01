import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { repositoryRoot, execute, redact, cleanGenerated, freshFile, backendResults, frontendResults, readModules, clearReports, withReportLock, withRunLog } from '../lib/quality-tools.mjs';
import { requireMaven25, resolveMavenCommand } from '../pre-push/backend-check.mjs';

// Read data, never execute/source an ENV file. Ambient variables take precedence.
export function loadSonarEnv(root, profile, ambient = process.env) {
  const allowed = new Set(['SONAR_HOST_URL', 'SONAR_TOKEN', 'SONAR_BACKEND_PROJECT_KEY', 'SONAR_FRONTEND_PROJECT_KEY']);
  const values = {};
  for (const name of ['.env', `.env.${profile}`]) {
    const filename = path.join(root, name);
    if (!fs.existsSync(filename)) throw new Error(`Missing ${name}`);
    for (const line of fs.readFileSync(filename, 'utf8').replace(/^\uFEFF/, '').split(/\r?\n/)) {
      const match = line.match(/^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*?)\s*$/);
      if (!match || !allowed.has(match[1])) continue;
      let value = match[2];
      if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) value = value.slice(1, -1);
      else value = value.replace(/\s+#.*$/, '').trim();
      values[match[1]] = value;
    }
  }
  for (const key of allowed) if (ambient[key] !== undefined) values[key] = ambient[key];
  return { ...ambient, ...values };
}

export function gatePassed(data) {
  return data?.projectStatus?.status === 'OK';
}

export function sonarWayPolicy(conditions = []) {
  const condition = (metric, op, threshold) => conditions.some((item) => item.metric === metric && item.op === op && Number(item.error) === threshold);
  const ratings = ['new_security_rating', 'new_reliability_rating', 'new_maintainability_rating'].every((metric) => condition(metric, 'GT', 1));
  return condition('new_coverage', 'LT', 80) && condition('new_duplicated_lines_density', 'GT', 3) && (condition('new_violations', 'GT', 0) || ratings);
}

export async function sonarRequest(host, endpoint, token) {
  const url = new URL(host);
  if (!['http:', 'https:'].includes(url.protocol) || !['localhost', '127.0.0.1', '[::1]'].includes(url.hostname) || url.username || url.password || url.search || url.hash || url.pathname !== '/') throw new Error('SONAR_HOST_URL must be a local HTTP(S) server URL without credentials');
  const response = await fetch(new URL(endpoint, url), {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    redirect: 'error',
    signal: AbortSignal.timeout(10000),
  });
  if (!response.ok) {
    const error = new Error(`SonarQube API returned HTTP ${response.status}`);
    error.status = response.status;
    throw error;
  }
  return response.json();
}

// Assigned-gate configuration may require permissions that analysis tokens lack.
// The actual analysis gate and measured overall targets remain mandatory.
export async function readAssignedGatePolicy({host, project, token, request=sonarRequest}) {
  let name;
  try {
    const assigned = await request(host, '/api/qualitygates/get_by_project?project=' + encodeURIComponent(project), token);
    name = assigned.qualityGate?.name;
    if (!name) throw new Error('Assigned Quality Gate is unavailable');
    const policy = await request(host, '/api/qualitygates/show?name=' + encodeURIComponent(name), token);
    return {status:'VERIFIED', name, conditions:policy.conditions ?? []};
  } catch (error) {
    if (error.status !== 403) throw error;
    return {status:'NOT VERIFIED', ...(name ? {name} : {}), reason:'Assigned Quality Gate configuration returned HTTP 403'};
  }
}


export const scanRows = ['SonarQube Access', 'Backend Reports', 'Frontend Reports', 'Backend Analysis', 'Frontend Analysis', 'Quality Gate Backend', 'Quality Gate Frontend', 'Overall Targets Backend', 'Overall Targets Frontend'];
const metricCandidates = ['coverage','new_coverage','duplicated_lines_density','new_duplicated_lines_density','security_rating','reliability_rating','sqale_rating','new_security_rating','new_reliability_rating','new_maintainability_rating','software_quality_security_rating','software_quality_reliability_rating','software_quality_maintainability_rating','new_software_quality_security_rating','new_software_quality_reliability_rating','new_software_quality_maintainability_rating'];

export function overallTargets(measures) {
  const values = Object.fromEntries((measures ?? []).filter(item => item.value !== undefined).map(item => [item.metric, Number(item.value)]));
  const security = values.software_quality_security_rating ?? values.security_rating;
  const reliability = values.software_quality_reliability_rating ?? values.reliability_rating;
  const maintainability = values.software_quality_maintainability_rating ?? values.sqale_rating;
  return security === 1 && reliability === 1 && maintainability === 1 && values.coverage >= 80 && values.duplicated_lines_density <= 3;
}

export async function runScan({root=repositoryRoot, profile='dev', ambient=process.env, runCommand=execute, request=sonarRequest, log=console.log}={}) {
  const rows=Object.fromEntries(scanRows.map(name=>[name,'SKIPPED']));
  const details={};
  let secrets=[];
  let env=ambient;
  const say=text=>log(redact(text,secrets));
  const run=(command,args,cwd=root,capture=false)=>runCommand(command,args,{cwd,env,capture,secrets,log:say});
  const mvn=resolveMavenCommand(root);
  const npm=process.platform==='win32'?'npm.cmd':'npm';
  const front=path.join(root,'frontend');
  const started=Date.now();
  async function step(name, action) {
    say('\n[CHECK] '+name);
    try { rows[name]=await action()?'PASS':'FAIL'; }
    catch(error) { rows[name]='FAIL'; details[name]=redact(error.message,secrets); say('[ERROR] '+details[name]); }
  }
  try {
    if (!['dev','prod'].includes(profile)) throw new Error('Profile must be dev or prod');
    env=loadSonarEnv(root,profile,ambient);
    secrets=[env.SONAR_TOKEN];
    const host=env.SONAR_HOST_URL || 'http://localhost:5099';
    const keys={Backend:env.SONAR_BACKEND_PROJECT_KEY || 'restofy-backend',Frontend:env.SONAR_FRONTEND_PROJECT_KEY || 'restofy-frontend'};
    for(const key of Object.values(keys)) if(!/^[A-Za-z0-9_.:-]+$/.test(key)) throw new Error('Invalid project key');
    let metricKeys=[];
    await step('SonarQube Access',async()=>{
      const server=await request(host,'/api/system/status');
      if(server.status!=='UP') throw new Error('SonarQube is not ready: '+server.status);
      if(!env.SONAR_TOKEN?.trim()) throw new Error('SONAR_TOKEN missing; set it in ignored root .env.dev or your shell. Never send it in chat.');
      const auth=await request(host,'/api/authentication/validate',env.SONAR_TOKEN);
      if(!auth.valid) throw new Error('SONAR_TOKEN is invalid');
      const gate=await request(host,'/api/qualitygates/show?name=Sonar%20way',env.SONAR_TOKEN);
      say('Sonar way NEW CODE conditions: '+JSON.stringify(gate.conditions ?? []));
      if(!sonarWayPolicy(gate.conditions)) throw new Error('Sonar way conditions do not match the required new-code targets');
      const catalog=await request(host,'/api/metrics/search?ps=500',env.SONAR_TOKEN);
      const available=new Set((catalog.metrics ?? []).map(item=>item.key));
      metricKeys=metricCandidates.filter(key=>available.has(key));
      if(!metricKeys.includes('coverage') || !metricKeys.includes('duplicated_lines_density')) throw new Error('Required server metrics are unavailable');
      return true;
    });
    if(rows['SonarQube Access']!=='PASS') throw new Error('Scan stopped before tests/scanners because server access is unavailable');
    const modules=readModules(root);
    clearReports(root,modules);
    let backendReports=false, frontendReports=false;
    await step('Backend Reports',async()=>{
      await requireMaven25(run,mvn,root);
      const result=await run(mvn,['-B','-ntp','-f','backend/pom.xml','clean','verify']);
      if(result.code!==0) throw new Error('Backend build/tests failed');
      details.backend=backendResults(root,modules,started);
      backendReports=true;
      say('Backend fresh reports: '+details.backend.tests+' tests; LOCAL overall line coverage '+details.backend.lineCoverage.toFixed(2)+'%. Report generation is not a coverage-target PASS.');
      return true;
    });
    await step('Frontend Reports',async()=>{
      if(!fs.existsSync(path.join(front,'node_modules/@sonar/scan/package.json'))) throw new Error('Official frontend scanner missing; run npm ci in frontend');
      const result=await run(npm,['run','test:coverage'],front);
      if(result.code!==0) throw new Error('Frontend test/coverage command failed');
      details.frontend=frontendResults(root,started);
      frontendReports=true;
      say('Frontend fresh reports: '+details.frontend.tests.numTotalTests+' tests; LOCAL overall line coverage '+details.frontend.summary.lines.pct+'%.');
      return true;
    });
    for(const area of ['Backend','Frontend']) {
      await step(area+' Analysis',async()=>{
        if(!(area==='Backend'?backendReports:frontendReports)) throw new Error('No current successful tests and measurable coverage for '+area);
        cleanGenerated(root,area==='Backend'?'backend/target/sonar':'frontend/.scannerwork');
        const props=['-Dsonar.projectKey='+keys[area],'-Dsonar.qualitygate.wait=true','-Dsonar.qualitygate.timeout=300'];
        const result=area==='Backend'
          ?await run(mvn,['-B','-ntp','-f','backend/pom.xml','sonar:sonar',...props])
          :await run(npm,['run','sonar','--',...props],front);
        const metadata=freshFile(root,area==='Backend'?'backend/target/sonar/report-task.txt':'frontend/.scannerwork/report-task.txt',started);
        const taskId=metadata.match(/^ceTaskId=(.+)$/m)?.[1]?.trim();
        if(!taskId) throw new Error('Scanner did not submit a current analysis');
        const deadline=Date.now()+300000;
        let task;
        do {
          task=(await request(host,'/api/ce/task?id='+encodeURIComponent(taskId),env.SONAR_TOKEN)).task;
          if(['SUCCESS','FAILED','CANCELED'].includes(task.status)) break;
          await new Promise(resolve=>setTimeout(resolve,2000));
        } while(Date.now()<deadline);
        if(task.status!=='SUCCESS' || !task.analysisId) throw new Error('Analysis processing failed/timed out: '+task.status);
        const gate=await request(host,'/api/qualitygates/project_status?analysisId='+encodeURIComponent(task.analysisId),env.SONAR_TOKEN);
        rows['Quality Gate '+area]=gatePassed(gate)?'PASS':'FAIL';
        details['gate_'+area]=gate.projectStatus;
        say(area+' Quality Gate: '+(gate.projectStatus?.status ?? 'unknown'));
        say('Required overall targets are enforced separately by this runner; built-in Sonar way checks do not prove assigned overall gate conditions.');
        const metrics=await request(host,'/api/measures/component?component='+encodeURIComponent(keys[area])+'&metricKeys='+metricKeys.join(','),env.SONAR_TOKEN);
        const measures=metrics.component?.measures ?? [];
        details['measures_'+area]=measures;
        const overall=measures.filter(item=>!item.metric.startsWith('new_'));
        const newCode=measures.filter(item=>item.metric.startsWith('new_'));
        say(area+' OVERALL CODE metrics (rating 1=A): '+JSON.stringify(overall));
        say(area+' NEW CODE metrics: '+JSON.stringify(newCode));
        rows['Overall Targets '+area]=overallTargets(measures)?'PASS':'FAIL';
        const policy=await readAssignedGatePolicy({host,project:keys[area],token:env.SONAR_TOKEN,request});
        details['gatePolicy_'+area]=policy;
        if(policy.status==='VERIFIED') {
          say(area+' ASSIGNED GATE '+policy.name+' conditions: '+JSON.stringify(policy.conditions));
        } else {
          say('[WARNING] '+area+' assigned gate configuration NOT VERIFIED (HTTP 403); actual analysis gate and overall targets checked independently.');
        }
        if(result.code!==0) say('[ERROR] '+area+' scanner exited with code '+result.code);
        return result.code===0;
      });
    }
  } catch(error) { say('[ERROR] '+error.message); }
  const passed=scanRows.every(name=>rows[name]==='PASS');
  say('\n========================================\nRESTOFY SONARQUBE SCAN\n========================================');
  for(const name of scanRows) say(name.padEnd(26)+' '+rows[name]);
  say(passed?'SONAR SCAN PASSED':'SONAR SCAN FAILED');
  return {exitCode:passed?0:1,rows,details};
}

export async function runSonar({action,root=repositoryRoot,profile='dev',ambient=process.env,runCommand=execute,request=sonarRequest,log=console.log}={}) {
  if(!['up','status','scan','down'].includes(action) || !['dev','prod'].includes(profile)) {
    log('Usage: sonar.cmd [up|status|scan|down] [dev|prod]');return {exitCode:1};
  }
  if(action==='scan') return withReportLock(root,()=>runScan({root,profile,ambient,runCommand,request,log}));
  let secrets=[];
  try {
    const env=loadSonarEnv(root,profile,ambient);
    secrets=[env.SONAR_TOKEN];
    const say=text=>log(redact(text,secrets));
    const base=['compose','-p','restofy-sonar','--env-file','.env','--env-file','.env.'+profile,'-f','docker-compose.sonar.yml'];
    // Stop retains containers as well as all database volumes.
    const args=action==='up'?['up','-d','--wait','--wait-timeout','360','sonar-db','sonarqube']
      :action==='down'?['stop','sonarqube','sonar-db']:['ps'];
    const result=await runCommand('docker',[...base,...args],{cwd:root,env,secrets,log:say});
    if(result.code!==0) {say('[ERROR] Docker operation failed: '+result.output);return {exitCode:1};}
    if(action==='status' || action==='up') {
      const status=await request(env.SONAR_HOST_URL || 'http://localhost:5099','/api/system/status');
      say('SonarQube readiness: '+status.status+'; version: '+(status.version ?? 'unknown'));
      return {exitCode:status.status==='UP'?0:1};
    }
    say('SonarQube services stopped; containers and volumes retained.');
    return {exitCode:0};
  } catch(error) {log(redact('[ERROR] '+error.message,secrets));return {exitCode:1};}
}

if(process.argv[1] && path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  try {
    const action=process.argv[2];
    const label=['up','status','scan','down'].includes(action)?'sonarqube-'+action:'sonarqube';
    process.exitCode=(await withRunLog(repositoryRoot,label,async log=>{
      try {return await runSonar({action,profile:process.argv[3] ?? 'dev',log});}
      catch(error) {log('Sonar runner could not start: '+error.message);return {exitCode:1};}
    },{secrets:[process.env.SONAR_TOKEN]})).exitCode;
  }
  catch(error) {console.error('Sonar runner could not start: '+error.message);process.exitCode=1;}
}

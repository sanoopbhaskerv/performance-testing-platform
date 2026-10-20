import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const runtime = path.dirname(fileURLToPath(import.meta.url));
const runnerDir = path.resolve(runtime, '../../engines/engine-playwright/src/main/resources/playwright-runner');
const digest = value => 'sha256:' + crypto.createHash('sha256').update(value).digest('hex');
let requests = 0;
let correlationSeen = false;
const server = http.createServer((req, res) => {
  requests++;
  correlationSeen ||= req.headers['x-performance-test-run-id'] === 'browser-contract-run';
  res.setHeader('Content-Type', 'text/html');
  res.end('<!doctype html><title>Performance target</title><h1>Ready</h1>');
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
after(() => new Promise(resolve => server.close(resolve)));
const target = `http://127.0.0.1:${server.address().port}`;
test('live snapshots bind the real runner identity and count journeys separately from supporting metrics', () => {
  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'browser-progress-test-'));
  const csv = path.join(work, 'results.csv');
  fs.writeFileSync(csv, 'metric,operation,value,success,timestamp\nbrowser.journey.duration,checkout,1000,false,10000\nbrowser.step.duration,checkout/action,20,true,10000\nweb.cls,checkout,95,true,10000\n');
  const output = execFileSync(process.execPath, [path.join(runtime, 'progress.js'), csv, '0', '1', '1'],
    { env: { ...process.env, PERF_RUN_ID: 'proof-run', PERF_SHARD_ID: 'proof-shard', PERF_PLAN_DIGEST: 'sha256:proof' }, stdio: ['ignore', 'pipe', 'pipe'] });
  const progress = JSON.parse(output);
  assert.equal(progress.runId, 'proof-run'); assert.equal(progress.shardId, 'proof-shard');
  assert.equal(progress.planDigest, 'sha256:proof');
  assert.equal(progress.overall.sampleCount, 1); assert.equal(progress.overall.errorCount, 1);
  assert.ok(progress.byOperation['web.cls@checkout']);
  fs.rmSync(work, { recursive: true });
});
async function run(script, { sessions = 1, iterations = 1, tamper = false, empty = false } = {}) {
  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'browser-runner-test-'));
  const file = path.join(work, 'journey.spec.ts');
  fs.writeFileSync(file, script);
  const sourceDigest = digest(script);
  const plan = JSON.stringify({ engine: 'playwright', durationSeconds: 30, workloads: [{
    name: 'journey', sourceUri: `artifact://teams/test-team/sources/playwright/${sourceDigest.slice(7)}/journey.spec.ts`, sourceDigest,
    browserUsers: sessions, iterationsPerUser: iterations, device: 'desktop', network: 'none', cpuThrottle: 1,
  }] });
  const rendered = { runId: 'browser-contract-run', planDigest: tamper ? 'sha256:bad' : digest(plan),
    compiledPlan: plan, targetBaseUrl: target, correlationHeader: 'X-Performance-Test-Run-Id', correlationValue: 'browser-contract-run' };
  fs.writeFileSync(path.join(work, 'plan.json'), JSON.stringify(rendered));
  let output = '';
  const child = spawn(process.execPath, [path.join(runnerDir, 'perf-runner.mjs')], {
    env: { ...process.env, PERF_PLAN_FILE: path.join(work, 'plan.json'), PERF_WORK_DIR: work,
      PERF_RUNTIME_ROOT: runtime, PERF_TEAM_ID: 'test-team', PERF_SOURCE_FILE: file,
      PERF_RESULTS_FILE: path.join(work, 'results.csv'), PERF_DEADLINE_SECONDS: '30' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  child.stdout.on('data', data => { output += data; }); child.stderr.on('data', data => { output += data; });
  const code = await new Promise(resolve => child.on('exit', resolve));
  return { code, output, work,
    rows: fs.existsSync(path.join(work, 'results.csv')) ? fs.readFileSync(path.join(work, 'results.csv'), 'utf8').trim().split('\n').slice(1) : [] };
}
const journey = `import {test,expect} from '@playwright/test';
test('Open target', async ({page}) => { await page.goto('/'); await expect(page.getByRole('heading')).toHaveText('Ready'); });`;
test('real cohort executes every planned journey, injects correlation and records measured page metrics', async () => {
  const result = await run(journey, { sessions: 2, iterations: 2 });
  assert.equal(result.code, 0, result.output);
  assert.equal(result.rows.filter(r => r.startsWith('browser.journey.duration,journey,')).length, 4);
  assert.ok(result.rows.some(r => r.startsWith('web.ttfb,journey,')));
  assert.ok(result.rows.some(r => r.startsWith('web.cls,journey,')));
  assert.ok(correlationSeen);
  assert.ok(fs.existsSync(path.join(result.work, 'playwright-report.html')));
});
test('a changed plan is rejected before any target traffic', async () => {
  const before = requests;
  const result = await run(journey, { tamper: true });
  assert.notEqual(result.code, 0); assert.match(result.output, /digest mismatch/);
  assert.equal(requests, before);
});
test('assertion failures remain failed measured journeys with a failure trace', async () => {
  const result = await run(journey.replace("toHaveText('Ready')", "toHaveText('Wrong', { timeout: 100 })"));
  assert.notEqual(result.code, 0);
  assert.ok(result.rows.some(r => /^browser.journey.duration,journey,\d+,false,/.test(r)));
  const report = JSON.parse(fs.readFileSync(path.join(result.work, 'playwright-report.json')));
  assert.equal(report.tests[0].status, 'failed');
  assert.ok(fs.readdirSync(path.join(result.work, 'test-output')).length > 0);
});
test('a script with no tests cannot report a completed journey', async () => {
  const result = await run('export const nothing = true;');
  assert.notEqual(result.code, 0); assert.equal(result.rows.length, 0);
});

test('a fully skipped script cannot look like a successful performance test', async () => {
  const result = await run(journey.replace("test('Open target'", "test.skip('Open target'"));
  assert.notEqual(result.code, 0); assert.equal(result.rows.length, 0);
});

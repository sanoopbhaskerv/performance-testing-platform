/** Shared local/container bootstrap. Only reviewed source runs, with capacity from the plan. */
import { spawn, execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const env = process.env;
const work = env.PERF_WORK_DIR;
const rendered = JSON.parse(fs.readFileSync(env.PERF_PLAN_FILE, 'utf8'));
const canonical = typeof rendered.compiledPlan === 'string'
  ? rendered.compiledPlan : JSON.stringify(rendered.compiledPlan);
const digest = 'sha256:' + crypto.createHash('sha256').update(canonical).digest('hex');
function fail(message) { throw new Error(message); }
if (digest !== rendered.planDigest || (env.PERF_PLAN_DIGEST && digest !== env.PERF_PLAN_DIGEST)) {
  fail('Plan digest mismatch; refusing to generate traffic.');
}
const plan = JSON.parse(canonical);
const workload = plan.workloads?.[0];
if (!workload || plan.engine !== 'playwright') fail('No Playwright workload in this plan.');
const sessions = Number(workload.browserUsers);
const iterations = Number(workload.iterationsPerUser);
if (!Number.isInteger(sessions) || sessions < 1 || sessions > 20 || !Number.isInteger(iterations) || iterations < 1 || iterations > 1000) {
  fail('Browser count must be 1–20 and iterations 1–1000.');
}
if (!['none', 'slow-4g', 'fast-4g', 'wifi'].includes(workload.network)) fail('Unsupported network profile.');
if (!Number.isFinite(Number(workload.cpuThrottle)) || Number(workload.cpuThrottle) < 1) fail('Invalid CPU profile.');
const runtimeRoot = env.PERF_RUNTIME_ROOT || path.join(env.PERF_WORKSPACE_ROOT || '', 'runner-images/playwright-runner');
let testFile;
let projectDir;

function inside(root, relative) {
  const base = fs.realpathSync(root);
  const file = fs.realpathSync(path.resolve(root, relative));
  if (!file.startsWith(base + path.sep)) fail('Source path escapes its allowed workspace.');
  return file;
}
function findProject(from, root) {
  for (let dir = from; dir.startsWith(root); dir = path.dirname(dir)) {
    if (fs.existsSync(path.join(dir, 'package.json'))) return dir;
    if (dir === path.dirname(dir)) break;
  }
  return root;
}
if (workload.sourceUri?.startsWith('workspace://')) {
  if (env.PERF_CONTAINER === 'true') fail('workspace:// sources are development only. Upload a reviewed script or pin a repository.');
  const root = fs.realpathSync(env.PERF_WORKSPACE_ROOT);
  testFile = inside(root, workload.sourceUri.slice('workspace://'.length));
  projectDir = findProject(path.dirname(testFile), root);
} else if (workload.sourceGitRepository) {
  if (env.PERF_CONTAINER !== 'true') fail('Git sources require the isolated container runner.');
  const { sourceGitRepository: repository, sourceGitRef: ref, sourceGitPath: relative } = workload;
  if (!/^[a-f0-9]{40}$/i.test(ref)) fail('Use a complete immutable 40-character commit SHA.');
  const url = new URL(repository);
  if (url.username || url.password || !['https:'].includes(url.protocol)) fail('Use a credential-free HTTPS repository URL.');
  projectDir = path.join(work, 'source');
  fs.mkdirSync(projectDir, { recursive: true });
  const gitEnv = { ...env, GIT_TERMINAL_PROMPT: '0', GIT_ASKPASS: '/bin/false', GIT_CONFIG_NOSYSTEM: '1' };
  const git = (...args) => execFileSync('git', ['-C', projectDir, ...args], { env: gitEnv, stdio: 'pipe', timeout: 120000 });
  git('init', '--quiet'); git('remote', 'add', 'origin', repository);
  git('fetch', '--quiet', '--depth', '1', 'origin', ref); git('checkout', '--quiet', '--detach', 'FETCH_HEAD');
  if (git('rev-parse', 'HEAD').toString().trim() !== ref.toLowerCase()) fail('Fetched commit differs from the approved commit.');
  testFile = inside(projectDir, relative);
  projectDir = findProject(path.dirname(testFile), projectDir);
  if (!fs.existsSync(path.join(projectDir, 'package-lock.json'))) fail('Git projects require package-lock.json for deterministic installation.');
  execFileSync('npm', ['ci', '--ignore-scripts', '--no-audit', '--no-fund'], { cwd: projectDir, stdio: 'pipe', timeout: 180000 });
} else {
  const expected = `artifact://teams/${env.PERF_TEAM_ID}/sources/playwright/${String(workload.sourceDigest).slice(7)}/journey.spec.ts`;
  if (workload.sourceUri !== expected) fail('Browser source is not owned by this run’s team.');
  testFile = env.PERF_SOURCE_FILE;
  if (!testFile) fail('Uploaded browser source was not delivered.');
  const actual = 'sha256:' + crypto.createHash('sha256').update(fs.readFileSync(testFile)).digest('hex');
  if (actual !== workload.sourceDigest) fail('Browser source digest mismatch.');
  projectDir = runtimeRoot;
  // Playwright resolves test imports beside the uploaded script. Only the pinned runtime is exposed.
  const modules = path.join(path.dirname(testFile), 'node_modules');
  if (!fs.existsSync(modules)) fs.symlinkSync(path.join(runtimeRoot, 'node_modules'), modules, 'dir');
}
const require = createRequire(path.join(projectDir, 'package.json'));
const cli = require.resolve('@playwright/test/cli');
const version = require('@playwright/test/package.json').version;
if (env.PERF_PLAYWRIGHT_VERSION && version !== env.PERF_PLAYWRIGHT_VERSION) {
  fail(`The reviewed project uses Playwright ${version}; this image provides ${env.PERF_PLAYWRIGHT_VERSION}. Build a matching runner image.`);
}
// Browser projects use the platform's single Chromium project. Project selection is deliberately
// refused rather than silently ignoring a repository config that could override quotas or retries.
if (workload.sourceGitProject) fail('Named repository projects are not supported; provide a self-contained journey.');
const mobile = workload.device === 'mobile';
if (!['desktop', 'mobile'].includes(workload.device || 'desktop')) fail('Choose desktop or mobile emulation.');
const originalTestFile = testFile;
const fixtureFile = path.join(path.dirname(new URL(import.meta.url).pathname), 'perf-fixture.mjs');
testFile = path.join(path.dirname(originalTestFile), `.perf-${crypto.randomUUID()}.spec.ts`);
const harness = `import { test as _platformTest } from '@playwright/test';\nimport { beforeBrowserJourney, afterBrowserJourney } from ${JSON.stringify(fixtureFile)};\n_platformTest.beforeEach(beforeBrowserJourney);\n_platformTest.afterEach(afterBrowserJourney);\n`;
fs.writeFileSync(testFile, harness + fs.readFileSync(originalTestFile, 'utf8'));
const cleanup = () => { try { fs.unlinkSync(testFile); } catch {} };
process.on('exit', cleanup);
const config = path.join(work, 'perf-playwright.config.mjs');
const output = path.join(work, 'test-output');
const timeout = Math.min(300000, Math.max(1000, Number(env.PERF_JOURNEY_TIMEOUT_MS || 60000)));
fs.writeFileSync(config, `export default ${JSON.stringify({
  testDir: path.dirname(testFile), testMatch: path.basename(testFile), fullyParallel: true,
  workers: sessions, repeatEach: sessions * iterations, retries: 0, forbidOnly: true,
  timeout, globalTimeout: Math.max(1000, Number(env.PERF_DEADLINE_SECONDS || plan.durationSeconds || 300) * 1000),
  reporter: [[path.join(path.dirname(new URL(import.meta.url).pathname), 'perf-reporter.mjs')]],
  outputDir: output,
  use: { browserName: 'chromium', baseURL: rendered.targetBaseUrl, headless: true,
    viewport: mobile ? { width: 390, height: 844 } : { width: 1440, height: 900 },
    isMobile: mobile, hasTouch: mobile, deviceScaleFactor: mobile ? 3 : 1,
    trace: 'retain-on-failure', screenshot: 'only-on-failure',
    extraHTTPHeaders: { [rendered.correlationHeader || 'X-Performance-Test-Run-Id']: rendered.correlationValue || rendered.runId },
  },
})};`, 'utf8');
fs.writeFileSync(env.PERF_RESULTS_FILE, 'metric,operation,value,success,timestamp\n');
const child = spawn(process.execPath, [cli, 'test', '--config', config], {
  cwd: projectDir, stdio: ['ignore', 'pipe', 'pipe'],
  env: { ...env, PERF_WORKLOAD_NAME: workload.name, PERF_BROWSER_PROFILE: JSON.stringify(workload), BASE_URL: rendered.targetBaseUrl,
    PERF_RUN_ID: rendered.runId, PERF_CORRELATION_HEADER: rendered.correlationHeader,
    PERF_CORRELATION_VALUE: rendered.correlationValue },
});
// Test console output is not a durable artifact. The sanitized summary reports failures without
// copying arbitrary response bodies or credentials out of an imported test.
child.stdout.resume(); child.stderr.resume();
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => child.kill(signal));
child.on('error', () => { process.stderr.write('Could not launch the pinned Playwright runtime.\n'); process.exitCode = 78; });
child.on('exit', (code, signal) => {
  const measured = fs.readFileSync(env.PERF_RESULTS_FILE, 'utf8').split('\n').some(line => line.startsWith('browser.journey.duration,'));
  process.exit(signal ? 143 : (!measured ? 78 : (code ?? 1)));
});

/*
 * Local browser runner bootstrap.
 *
 * Reads the immutable rendered plan the control plane wrote beside it, resolves the reviewed
 * Playwright script, generates a config from the plan's cohort settings, and runs
 * `npx playwright test`. It decides nothing: every number it uses — browser sessions, iterations,
 * target URL, correlation header — comes from the plan, which is digested and was validated before
 * a slot was ever reserved for it.
 *
 * Written in Node rather than Java for one reason: the plan is JSON and Node parses JSON without a
 * dependency, so the control plane needs no JSON library on the execution path. It also keeps this
 * file close in shape to runner-images/playwright-runner/entrypoint.sh, so the local adapter and the
 * production container stay recognizably the same procedure.
 *
 * Exit codes: 0 success, non-zero failure. Playwright's own exit code is passed through when it ran,
 * so a failing journey fails the shard rather than being reported as a platform fault.
 */

import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';

const planFile = process.env.PERF_PLAN_FILE;
const workDir = process.env.PERF_WORK_DIR;
const workspaceRoot = process.env.PERF_WORKSPACE_ROOT;
const resultsFile = process.env.PERF_RESULTS_FILE;
const npxCommand = process.env.PERF_NPX_COMMAND || 'npx';

function fail(message) {
  process.stderr.write(`${message}\n`);
  process.exit(78);
}

const rendered = JSON.parse(fs.readFileSync(planFile, 'utf8'));

// The control plane writes the compiled plan as a JSON *string* field, so that the plan's own bytes
// — the ones the digest was taken over — survive being embedded verbatim. Parsing it back here is
// what makes it an object again.
const plan = typeof rendered.compiledPlan === 'string'
  ? JSON.parse(rendered.compiledPlan)
  : rendered.compiledPlan;

const workload = plan?.workloads?.[0];
if (!workload) {
  fail('The rendered plan carries no browser workload, so there is nothing to run.');
}

/*
 * Resolving the reviewed script.
 *
 * Only workspace:// is supported by this local adapter. git coordinates are the production path and
 * belong to the container runner, which fetches a pinned commit and verifies that what the remote
 * sent is what was asked for. Doing a half-version of that here — fetching without the verification
 * — would make pinning look implemented while it was a comment, so this refuses instead.
 */
const sourceUri = workload.sourceUri || '';
if (!sourceUri.startsWith('workspace://')) {
  fail(workload.sourceGitRepository
    ? 'This source is pinned to a git commit, which only the container runner fetches. '
      + 'Use the Kubernetes provider, or point the source at workspace://<path> for local runs.'
    : `Source '${sourceUri}' is not a workspace:// path, so the local runner cannot resolve it.`);
}

const relative = sourceUri.slice('workspace://'.length);
const testFile = path.resolve(workspaceRoot, relative);

// Checked against the resolved root rather than by inspecting the string, so a symlink or an
// encoding trick cannot land outside the workspace the operator configured.
const rootReal = fs.realpathSync(workspaceRoot);
if (!fs.existsSync(testFile)) {
  fail(`No Playwright test at ${relative} inside the configured workspace root.`);
}
if (!fs.realpathSync(testFile).startsWith(rootReal + path.sep)) {
  fail(`Refusing to run ${relative}: it resolves outside the configured workspace root.`);
}

const projectDir = findProjectDir(path.dirname(testFile), rootReal);

function findProjectDir(from, stopAt) {
  // The nearest ancestor with a package.json owns the Playwright install and node_modules. Walking
  // up rather than assuming the repository root matters here: this repository keeps its browser
  // journeys in demo-student-ui, which has its own @playwright/test.
  let dir = from;
  for (;;) {
    if (fs.existsSync(path.join(dir, 'package.json'))) {
      return dir;
    }
    const parent = path.dirname(dir);
    if (dir === parent || !dir.startsWith(stopAt)) {
      return stopAt;
    }
    dir = parent;
  }
}

const sessions = Math.max(1, Number(workload.browserUsers) || 1);
const iterations = Math.max(1, Number(workload.iterationsPerUser) || 1);

/*
 * The load model, and the one arithmetic mistake worth spelling out.
 *
 * `workers` is the browser cohort: how many real browser instances are open at once. `repeatEach`
 * is how many times Playwright runs *each test*, which is the total journey count — not the count
 * per worker. So a 5-user cohort doing 2 iterations each is 10 journeys, and `repeatEach: 2` would
 * schedule 2 journeys across 5 workers: three browsers would open, do nothing, and close, and the
 * run would report a cohort of five having measured two journeys.
 *
 * Hence sessions * iterations. There is no ramp: a browser cohort is a small fixed population, not
 * a curve (CLAUDE.md §21 — default 5, policy ceiling 20).
 */
const totalJourneys = sessions * iterations;

/*
 * Any playwright.config in the project is deliberately NOT extended. It would carry its own workers,
 * repeatEach and baseURL, and a run whose concurrency came from a file in the repository rather than
 * from the approved plan would make the plan's capacity figure — the one admission control reserved
 * slots against — a fiction.
 */
/*
 * Refused here rather than at the first confusing stack trace. Playwright is resolved from the
 * project, not from this runner, so the journey executes under the version it was reviewed against.
 */
const require = createRequire(path.join(projectDir, 'package.json'));
try {
  require.resolve('@playwright/test');
} catch {
  fail(`No @playwright/test install was found in ${projectDir}. `
    + 'Run `npm install` there so the platform can start the journey with the same Playwright '
    + 'version the project was reviewed against.');
}

/*
 * The config lives in the shard's work directory, not in the project, so a run leaves nothing behind
 * in somebody's checkout — a killed run cannot strand a scratch config that the next
 * `npx playwright test` silently picks up.
 *
 * It exports a plain object rather than calling defineConfig, which only exists to attach types.
 * Importing it would mean resolving a CommonJS module from an ESM file outside the project, and
 * paying that complexity for a type annotation nothing here checks.
 */
const configFile = path.join(workDir, 'perf-playwright.config.mjs');
fs.writeFileSync(configFile, `
export default ({
  testDir: ${JSON.stringify(path.dirname(testFile))},
  testMatch: ${JSON.stringify(path.basename(testFile))},
  fullyParallel: true,
  workers: ${sessions},
  repeatEach: ${totalJourneys},
  retries: 0,
  reporter: [
    [${JSON.stringify(path.join(workDir, 'perf-reporter.mjs'))}],
    ['json', { outputFile: ${JSON.stringify(path.join(workDir, 'playwright-report.json'))} }],
  ],
  outputDir: ${JSON.stringify(path.join(workDir, 'test-output'))},
  use: {
    baseURL: ${JSON.stringify(rendered.targetBaseUrl || '')},
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    extraHTTPHeaders: {
      ${JSON.stringify(rendered.correlationHeader || 'X-Performance-Test-Run-Id')}: ${JSON.stringify(rendered.correlationValue || rendered.runId || '')},
    },
  },
});
`, 'utf8');


fs.writeFileSync(resultsFile, 'metric,operation,value,success,timestamp\n', 'utf8');

// retries: 0 above and no retry here. CLAUDE.md §16: a replayed performance test is duplicate load
// against a real system and invalid results on top of it.
const child = spawn(npxCommand, ['playwright', 'test', '--config', configFile], {
  cwd: projectDir,
  stdio: 'inherit',
  env: {
    ...process.env,
    PERF_RESULTS_FILE: resultsFile,
    PERF_WORKLOAD_NAME: workload.name || 'browser',
    BASE_URL: rendered.targetBaseUrl || '',
    PERF_RUN_ID: rendered.runId || '',
    PERF_CORRELATION_HEADER: rendered.correlationHeader || '',
    PERF_CORRELATION_VALUE: rendered.correlationValue || '',
  },
});

child.on('error', (error) => fail(`Could not start Playwright: ${error.message}`));
child.on('exit', (code, signal) => process.exit(signal ? 143 : (code ?? 1)));

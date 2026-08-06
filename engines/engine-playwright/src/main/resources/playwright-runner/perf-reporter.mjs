/*
 * Playwright reporter that writes the platform's engine-neutral result rows.
 *
 * Columns: metric,operation,value,success,timestamp — the same compact CSV the production browser
 * runner image emits and PlaywrightResultParser reads. Writing it from a reporter rather than
 * post-processing Playwright's JSON report is deliberate: the JSON report's shape is Playwright's
 * own and changes between versions, whereas the reporter API is the documented contract. A result
 * format that silently stopped parsing after a dependency bump would present as a run that measured
 * nothing, which is the failure mode this platform is built to avoid.
 *
 * Operation labels are what objectives are judged against, so they are not cosmetic:
 *
 *   <workload>              one row per completed journey  -> objective scope: <workload>
 *   <workload>/<step title> one row per step               -> objective scope: <workload>/<step>
 *
 * The exact-match rule in ObjectiveEvaluator means a journey objective scoped to the workload name
 * sees journey rows only; step rows sit underneath and are reachable by naming them. If both were
 * labelled the same, a journey threshold would silently be judged against a mixture of whole
 * journeys and individual clicks.
 *
 * Web vitals (web.lcp, web.cls, ...) are deliberately NOT emitted. Capturing them needs an observer
 * injected into the page before it loads, which a reporter cannot do without changing what the
 * reviewed test runs under. Emitting nothing makes those objectives INDETERMINATE, which is the
 * honest outcome: nothing measured them. Fabricating a value from step timings would produce a
 * number that looks like a web vital, passes a threshold, and means nothing.
 */

import fs from 'node:fs';

const RESULTS = process.env.PERF_RESULTS_FILE;
const WORKLOAD = process.env.PERF_WORKLOAD_NAME || 'browser';

/** A label safe to put in a CSV cell and to match an objective scope against. */
function label(text) {
  return String(text ?? '')
    .replace(/[,\r\n]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function row(metric, operation, value, success, timestamp) {
  return `${metric},${label(operation)},${Math.max(0, Math.round(value))},${success},${timestamp}\n`;
}

export default class PerfReporter {
  constructor() {
    this.rows = [];
  }

  onTestEnd(test, result) {
    const finishedAt = result.startTime
      ? new Date(result.startTime).getTime() + (result.duration ?? 0)
      : Date.now();

    // Only the outcomes that describe a journey that actually ran. A test Playwright skipped
    // measured nothing, and recording a zero-duration success for it would pull a percentile down
    // with journeys that never happened.
    if (result.status === 'skipped') {
      return;
    }

    const passed = result.status === 'passed';
    this.rows.push(row('browser.journey.duration', WORKLOAD, result.duration ?? 0, passed, finishedAt));

    for (const step of result.steps ?? []) {
      // Playwright reports a step tree; the interesting level is the test's own actions rather
      // than every internal expect. `pw:api` and `test.step` are what a journey author sees.
      if (step.category !== 'pw:api' && step.category !== 'test.step') {
        continue;
      }
      const stepFinishedAt = step.startTime
        ? new Date(step.startTime).getTime() + (step.duration ?? 0)
        : finishedAt;
      this.rows.push(row(
        'browser.step.duration',
        `${WORKLOAD}/${step.title}`,
        step.duration ?? 0,
        !step.error,
        stepFinishedAt));
    }
  }

  async onEnd() {
    // Appended, because the bootstrap writes the header before the run starts: a results file that
    // exists and is empty is distinguishable from one that was never created.
    if (RESULTS && this.rows.length > 0) {
      await fs.promises.appendFile(RESULTS, this.rows.join(''), 'utf8');
    }
  }
}

/** Incremental, payload-free result reporter shared by local and Kubernetes runners. */
import fs from 'node:fs';
import path from 'node:path';
const clean = text => String(text ?? '').replace(/[,\r\n<>]/g, ' ').slice(0, 200);
export default class PerfReporter {
  constructor() { this.tests = []; }
  onTestEnd(test, result) {
    if (result.status === 'skipped') return;
    const workload = clean(process.env.PERF_WORKLOAD_NAME || 'browser');
    const end = new Date(result.startTime).getTime() + result.duration;
    const rows = [`browser.journey.duration,${workload},${Math.max(0, Math.round(result.duration))},${result.status === 'passed'},${new Date(result.startTime).getTime()}`];
    let action = 0;
    for (const step of result.steps ?? []) {
      if (!['pw:api', 'test.step'].includes(step.category)) continue;
      // Playwright API titles may include a filled value or a URL query. Preserve timing without
      // copying those values into the broadly readable metrics table.
      const label = step.category === 'test.step' ? clean(step.title) : `Browser action ${++action}`;
      rows.push(`browser.step.duration,${workload}/${label},${Math.max(0, Math.round(step.duration))},${!step.error},${end}`);
    }
    for (const attachment of result.attachments ?? []) {
      if (attachment.name !== 'platform.web-metrics' || !attachment.body) continue;
      const metrics = JSON.parse(attachment.body.toString());
      for (const [field, metric] of [['lcp', 'web.lcp'], ['cls', 'web.cls'], ['ttfb', 'web.ttfb']]) {
        if (typeof metrics[field] !== 'number' || !Number.isFinite(metrics[field])) continue;
        const value = Math.round(metrics[field] * (field === 'cls' ? 1000 : 1));
        rows.push(`${metric},${workload},${Math.max(0, value)},true,${end}`);
      }
    }
    // A complete journey immediately becomes visible to the progress publisher.
    fs.appendFileSync(process.env.PERF_RESULTS_FILE, rows.join('\n') + '\n');
    this.tests.push({ name: clean(test.title), status: result.status, durationMs: result.duration,
      error: result.error ? 'Journey failed. Inspect the restricted trace or screenshot for details.' : null });
  }
  onEnd(result) {
    const summary = { status: result.status, tests: this.tests };
    const work = process.env.PERF_WORK_DIR;
    fs.writeFileSync(path.join(work, 'playwright-report.json'), JSON.stringify(summary));
    const escape = x => String(x).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('"', '&quot;');
    fs.writeFileSync(path.join(work, 'playwright-report.html'), `<!doctype html><meta charset="utf-8"><title>Browser journey results</title><h1>Browser journey results</h1><p>${escape(result.status)}</p><table><tr><th>Journey</th><th>Result</th><th>Duration (ms)</th></tr>${this.tests.map(t => `<tr><td>${escape(t.name)}</td><td>${escape(t.status)}</td><td>${t.durationMs}</td></tr>`).join('')}</table>`);
  }
}

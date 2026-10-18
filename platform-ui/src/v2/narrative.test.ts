import { describe, it, expect } from 'vitest';
import { summarize, METRIC_EXPLANATIONS } from './narrative';
import type { ObjectiveOutcome, OperationMetrics, RunReport } from './api';

/**
 * The report's opening sentence.
 *
 * A narrative layer is exactly where a platform's carefulness gets undone: the data says
 * "indeterminate" and the sentence says "fine". This platform refuses to pass on incomplete
 * evidence in four separate places, and these tests exist so the sentence at the top of the report
 * cannot quietly disagree with any of them.
 */

const metrics = (over: Partial<OperationMetrics> = {}): OperationMetrics => ({
  name: '*', sampleCount: 1000, errorCount: 0, unparsedCount: 0, errorRatePercent: 0,
  minMillis: 5, maxMillis: 900, meanMillis: 100, p50Millis: 90, p90Millis: 300,
  p95Millis: 420, p99Millis: 800, throughputPerSecond: 25, ...over,
});

const objective = (over: Partial<ObjectiveOutcome> = {}): ObjectiveOutcome => ({
  objectiveId: 'o1', metric: 'http.response.time', scope: 'demo-load',
  verdict: 'PASS', observed: 420, threshold: 750, detail: '', ...over,
});

const report = (over: Partial<RunReport> = {}): RunReport => ({
  run: {} as RunReport['run'],
  synthetic: false,
  syntheticNotice: null,
  test: null,
  execution: null,
  objectives: [objective()],
  summary: { passed: 1, breached: 0, indeterminate: 0, demonstrated: true },
  measurements: {
    engine: 'jmeter', measuredFrom: null, measuredTo: null,
    shardsExpected: 1, shardsReported: 1, complete: true,
    overall: metrics(), operations: [metrics()],
  },
  timeline: [],
  artifacts: [],
  ...over,
});

describe('summarize', () => {
  it('describes browser journeys without claiming configured concurrency was observed', () => {
    const r = report();
    r.measurements!.engine = 'playwright';
    r.measurements!.overall = metrics({ sampleCount: 4 });
    r.objectives = [objective({ metric: 'browser.journey.duration' })];
    const detail = summarize(r).detail.join(' ');
    expect(detail).toContain('4 browser journeys were measured');
    expect(detail).not.toContain('requests were sent');
    expect(detail).not.toContain('95% completed within');
  });
  describe('a synthetic run says so before anything else', () => {
    const narrative = summarize(report({ synthetic: true }));

    it('leads with the fact that nothing was measured', () => {
      expect(narrative.headline).toContain('generated no load');
      expect(narrative.tone).toBe('unknown');
    });

    it('quotes no measurement at all', () => {
      // The whole point of the synthetic marking is that a figure must not travel without its
      // caveat. A narrative that mentioned a latency here would be the failure it exists to prevent.
      const everything = [narrative.headline, ...narrative.detail].join(' ');
      expect(everything).not.toMatch(/\d+ ms|\d+ requests|95%/);
    });
  });

  it('says nothing has been measured when a run has not got that far', () => {
    const narrative = summarize(report({ measurements: null }));

    expect(narrative.headline).toBe('Nothing has been measured yet.');
    expect(narrative.tone).toBe('unknown');
  });

  it('passes plainly when every target was met', () => {
    const narrative = summarize(report());

    expect(narrative.tone).toBe('good');
    expect(narrative.headline).toContain('passed');
  });

  it('names what missed, using the server\u2019s own wording for the numbers', () => {
    const narrative = summarize(
      report({
        objectives: [objective({
          verdict: 'BREACH', observed: 1900, threshold: 750,
          detail: 'p95 was 1900ms against a ceiling of 750ms.',
        })],
        summary: { passed: 0, breached: 1, indeterminate: 0, demonstrated: false },
      }),
    );

    expect(narrative.tone).toBe('bad');
    expect(narrative.headline).toContain('did not pass');
    expect(narrative.detail[0]).toContain('p95 was 1900ms against a ceiling of 750ms.');
  });

  it('never re-formats observed and threshold, whose scale it cannot know', () => {
    // Regression, found by running a real test rather than by any assertion. An error rate arrives
    // as thousandths: 1.485% is the number 1485, against 1000 for a 1% ceiling. Formatting those
    // client-side rendered "1485.00% of requests failed" -- confident nonsense, and the kind that
    // discredits every other number on the page. The server computes the verdict and the sentence
    // explaining it together; the client uses both.
    const narrative = summarize(
      report({
        objectives: [objective({
          metric: 'http.error.rate', verdict: 'BREACH', observed: 1485, threshold: 1000,
          detail: '1.485% of 2221 requests failed, against a ceiling of 1%.',
        })],
        summary: { passed: 0, breached: 1, indeterminate: 0, demonstrated: false },
      }),
    );

    const everything = [narrative.headline, ...narrative.detail].join(' ');
    expect(everything).toContain('1.485% of 2221 requests failed');
    expect(everything).not.toContain('1485.00');
  });

  it('still says something useful when the server supplies no detail', () => {
    const narrative = summarize(
      report({
        objectives: [objective({ verdict: 'BREACH', detail: '' })],
        summary: { passed: 0, breached: 1, indeterminate: 0, demonstrated: false },
      }),
    );

    expect(narrative.detail[0]).toContain('target not met');
  });

  it('never reports an indeterminate run as a pass', () => {
    const narrative = summarize(
      report({
        objectives: [objective({ verdict: 'INDETERMINATE', detail: 'No samples were recorded.' })],
        summary: { passed: 0, breached: 0, indeterminate: 1, demonstrated: false },
      }),
    );

    expect(narrative.tone).toBe('unknown');
    expect(narrative.headline).toContain('neither passed nor failed');
    expect(narrative.headline).not.toContain('passed:');
  });

  it('says a run measured less than it should have, before any verdict', () => {
    const narrative = summarize(
      report({
        measurements: {
          ...report().measurements!,
          shardsExpected: 3,
          shardsReported: 1,
          complete: false,
        },
      }),
    );

    expect(narrative.detail.join(' ')).toContain('1 of 3 load generators');
  });

  it('distinguishes a test with no targets from one that passed', () => {
    const narrative = summarize(
      report({ objectives: [], summary: { passed: 0, breached: 0, indeterminate: 0, demonstrated: false } }),
    );

    expect(narrative.tone).toBe('unknown');
    expect(narrative.headline).toContain('no targets');
  });

  it('names the slowest journey when there is more than one to compare', () => {
    const narrative = summarize(
      report({
        objectives: [objective({ verdict: 'BREACH', observed: 1900, threshold: 750 })],
        measurements: {
          ...report().measurements!,
          operations: [
            metrics({ name: 'browse', p95Millis: 200 }),
            metrics({ name: 'checkout', p95Millis: 3400 }),
          ],
        },
      }),
    );

    expect(narrative.detail.join(' ')).toContain('checkout');
    expect(narrative.detail.join(' ')).toContain('3.4 seconds');
  });

  it('does not name a slowest journey when there is only one', () => {
    const narrative = summarize(
      report({ objectives: [objective({ verdict: 'BREACH', observed: 900, threshold: 750 })] }),
    );

    expect(narrative.detail.join(' ')).not.toContain('slowest journey');
  });

  it('says "none failed" rather than "0.00% failed"', () => {
    const narrative = summarize(report());

    expect(narrative.detail.join(' ')).toContain('None failed');
  });

  describe('one number, one place', () => {
    // A workload-scoped objective takes the HIGHEST percentile among its journeys, so a workload
    // passes only if every journey would have. The whole-test aggregate is a different, also
    // correct, number. Printing both was two right answers that read as a contradiction -- and a
    // reader who cannot reconcile two numbers stops trusting both.

    it('omits the summary latency when an objective already reports one', () => {
      const narrative = summarize(
        report({ objectives: [objective({ metric: 'http.response.time' })] }),
      );

      expect(narrative.detail.join(' ')).not.toContain('95% completed within');
    });

    it('includes it when nothing else judges latency', () => {
      const narrative = summarize(
        report({ objectives: [objective({ metric: 'http.error.rate' })] }),
      );

      expect(narrative.detail.join(' ')).toContain('95% completed within');
    });
  });

  it('ends every sentence, so they can be read aloud in sequence', () => {
    for (const candidate of [report(), report({ synthetic: true }), report({ measurements: null })]) {
      const narrative = summarize(candidate);
      for (const sentence of [narrative.headline, ...narrative.detail]) {
        expect(sentence.trim().endsWith('.'), sentence).toBe(true);
      }
    }
  });
});

describe('METRIC_EXPLANATIONS', () => {
  it('explains every column the latency table shows', () => {
    for (const key of ['p50', 'p90', 'p95', 'p99', 'max', 'requests', 'failed', 'throughput', 'unreadable']) {
      expect(METRIC_EXPLANATIONS[key], key).toBeTruthy();
    }
  });

  it('explains percentiles without using the word percentile', () => {
    // "Nineteen in twenty" is a sentence somebody can picture. "The 95th percentile" is the term
    // they came here not knowing.
    expect(METRIC_EXPLANATIONS.p95).toContain('twenty');
    expect(METRIC_EXPLANATIONS.p95.toLowerCase()).not.toContain('percentile');
  });
});

import type { ObjectiveOutcome, OperationMetrics, RunReport } from './api';
import { objectiveLabel } from './labels';

/**
 * A run's result, said in a sentence.
 *
 * <p>The existing report is a good expert report: verdict chips, a latency table, a stage profile.
 * Someone who has run performance tests before reads it in ten seconds. Someone who has not reads a
 * table of percentiles and has no idea whether to be pleased.
 *
 * <p>So the report now opens with the answer. Not a chip above a table — a sentence naming what
 * happened, what missed, and by how much. Everything below it stays exactly as it was, because the
 * expert reading is still the one that settles arguments.
 *
 * <h2>What this must never do</h2>
 * Soften. The platform is unusually careful about incomplete evidence — indeterminate objectives,
 * synthetic-run marking, refusing to pass on missing shards — and a narrative layer is precisely
 * where that care gets undone by a sentence that reads better than the data. So: a synthetic run
 * says so first and says nothing else; an indeterminate verdict is never rendered as "fine"; and no
 * sentence is generated for a measurement that does not exist.
 */

export type Tone = 'good' | 'bad' | 'unknown';

export interface Narrative {
  tone: Tone;
  /** The answer, in one sentence. */
  headline: string;
  /** Supporting sentences, each safe to read on its own. Possibly empty. */
  detail: string[];
}

const ms = (value: number) => `${Math.round(value).toLocaleString()} ms`;

/** Thousands separated, because a six-digit request count is unreadable without it. */
const count = (value: number) => Math.round(value).toLocaleString();

/**
 * A duration in the words someone would speak, for a sentence rather than a field.
 *
 * `1.9 seconds` rather than `1900 ms` once past a second: a reader compares "1.9 seconds" against
 * their own patience far faster than they compare four digits of milliseconds.
 */
function spokenMillis(value: number): string {
  if (value < 1000) return ms(value);
  const seconds = value / 1000;
  return `${seconds.toFixed(seconds < 10 ? 1 : 0)} seconds`;
}

/** The whole-test row, which is what a test-wide objective is judged against. */
function overall(report: RunReport): OperationMetrics | null {
  return report.measurements?.overall ?? null;
}

/** The journey with the worst 95th percentile, when there is more than one to compare. */
function slowestJourney(report: RunReport): OperationMetrics | null {
  const browserOnly = report.measurements?.engine === 'playwright';
  const operations = (report.measurements?.operations ?? []).filter((o) => o.name !== '*'
    && !o.name.startsWith('web.') && (!browserOnly || !o.name.includes('/')));
  if (operations.length < 2) return null;
  return operations.reduce((worst, o) => (o.p95Millis > worst.p95Millis ? o : worst));
}

/**
 * A breached objective, in one line.
 *
 * <p>The numbers come from the server's own {@code detail}, never from re-formatting
 * {@code observed} and {@code threshold}. Those two are scaled per metric — an error rate arrives
 * as thousandths, so 1.485% is the number 1485 — and a client formatting them itself would have to
 * know each metric's scale and keep knowing it as metrics are added. It got this wrong once,
 * rendering a 1.485% error rate as "1485.00% of requests failed": confident nonsense, and the kind
 * that discredits every other number on the page.
 *
 * <p>The server computes the verdict and the sentence explaining it in one place. Using both is what
 * makes them impossible to disagree.
 */
function breachSentence(objective: ObjectiveOutcome): string {
  const label = objectiveLabel(objective.metric, objective.scope);
  return objective.detail ? `${label}: ${objective.detail}` : `${label}: target not met.`;
}

/**
 * The report's opening sentence, and what supports it.
 *
 * <p>Ordered by what a reader needs first: whether the numbers mean anything at all, then whether
 * the test passed, then what specifically missed.
 */
export function summarize(report: RunReport): Narrative {
  // Before anything else. A synthetic run's figures describe nothing, and a narrative that led with
  // a latency would be the exact failure the synthetic marking exists to prevent.
  if (report.synthetic) {
    return {
      tone: 'unknown',
      headline: 'This run generated no load, so nothing here is a measurement.',
      detail: [
        'The platform is configured with an execution provider that produces no traffic. The ' +
          'figures below were invented so the run could be exercised end to end.',
      ],
    };
  }

  if (!report.measurements) {
    return {
      tone: 'unknown',
      headline: 'Nothing has been measured yet.',
      detail: ['This run has not reached the point where results are read.'],
    };
  }

  const measurements = report.measurements;
  const totals = overall(report);
  const detail: string[] = [];

  // Said before any verdict. A run missing a shard measured less load than its plan describes, and
  // every objective on it is indeterminate — a headline about passing would be actively misleading.
  if (!measurements.complete) {
    detail.push(
      `Only ${measurements.shardsReported} of ${measurements.shardsExpected} load generators ` +
        'reported, so this run measured less traffic than it was meant to.',
    );
  }

  if (totals) {
    const browserOnly = measurements.engine === 'playwright';
    const peak = report.test?.workloads?.[0]?.peakUsers;
    const profile = report.test?.workloads?.[0];
    const configuredLoad = profile?.modelType === 'ARRIVAL_RATE'
      ? `, targeting up to ${count(profile.peakArrivalRate ?? 0)} requests per second with at most ${count(profile.maxConcurrency ?? profile.peakUsers)} requests in flight`
      : peak ? `, configured for up to ${count(peak)} ${browserOnly ? 'browsers' : 'users'} at once` : '';
    // The latency clause is added only when no response-time objective carries one.
    //
    // Both figures are correct and they differ: a workload-scoped objective takes the *highest*
    // percentile among its journeys, so a workload passes only if every journey would have, while
    // this is the aggregate over every sample. Printing "95% completed within 533 ms" directly above
    // "p95 was 584ms" is two right answers that read as a contradiction — and a reader who cannot
    // reconcile two numbers stops trusting both. So each number appears in exactly one place.
    const latencyIsJudged = report.objectives.some((o) => ['http.response.time', 'browser.journey.duration'].includes(o.metric));
    detail.push(
      `${count(totals.sampleCount)} ${browserOnly ? 'browser journeys were measured' : 'requests were sent'}` +
        configuredLoad +
        `. ${totals.errorRatePercent < 0.005 ? 'None failed' : `${totals.errorRatePercent.toFixed(2)}% failed`}` +
        (latencyIsJudged
          ? '.'
          : `, and 95% completed within ${spokenMillis(totals.p95Millis)}.`),
    );
  }

  const breached = report.objectives.filter((o) => o.verdict === 'BREACH');
  const indeterminate = report.objectives.filter((o) => o.verdict === 'INDETERMINATE');

  if (report.objectives.length === 0) {
    return {
      tone: 'unknown',
      headline: 'This test measured its traffic but set no targets, so there is no pass or fail.',
      detail,
    };
  }

  if (breached.length > 0) {
    const slowest = slowestJourney(report);
    if (slowest) {
      detail.push(`The slowest journey was ${slowest.name}, at ${spokenMillis(slowest.p95Millis)}.`);
    }
    return {
      tone: 'bad',
      headline:
        breached.length === 1
          ? 'This test did not pass: one target was missed.'
          : `This test did not pass: ${breached.length} of ${report.objectives.length} targets were missed.`,
      detail: [...breached.map(breachSentence), ...detail],
    };
  }

  if (indeterminate.length > 0) {
    return {
      tone: 'unknown',
      headline:
        indeterminate.length === report.objectives.length
          ? 'No target could be judged, so this run neither passed nor failed.'
          : `${indeterminate.length} of ${report.objectives.length} targets could not be judged.`,
      detail: [...indeterminate.map((o) => `${objectiveLabel(o.metric, o.scope)}: ${o.detail}`), ...detail],
    };
  }

  return {
    tone: 'good',
    headline:
      report.objectives.length === 1
        ? 'This test passed: its target was met.'
        : `This test passed: all ${report.objectives.length} targets were met.`,
    detail,
  };
}

/**
 * What a metric means, for the reader who has not met it before.
 *
 * <p>Shown where the number is, not in a glossary. A glossary is a page nobody opens, and the moment
 * somebody needs to know what a 95th percentile is, is the moment they are looking at one.
 */
export const METRIC_EXPLANATIONS: Record<string, string> = {
  p50: 'Half of all requests were faster than this. It describes a typical request, and hides the slow ones.',
  p90: 'Nine in ten requests were faster than this.',
  p95:
    'Nineteen in twenty requests were faster than this. It is the usual figure to set a target ' +
    'against, because it covers almost everyone while ignoring a handful of outliers that no ' +
    'system avoids entirely.',
  p99: 'Ninety-nine in a hundred requests were faster than this. It describes the worst experiences people actually had.',
  max: 'The single slowest request. One unlucky request can produce this, so it is a warning sign rather than a measurement to set targets against.',
  requests: 'Recorded samples: requests for API tests, or completed journeys and actions for browser tests. Page metrics have their own table.',
  failed:
    'Requests the target rejected or did not answer. A rising error rate under load usually means ' +
    'the system is shedding work rather than slowing down.',
  throughput:
    'Completed requests per second. It can fall while the number of users rises — that is what a ' +
    'saturated system looks like, and it is the pattern worth watching for.',
  unreadable:
    'Results that could not be read at all. Counted as failures rather than skipped, because ' +
    'dropping them would make a run look healthier the worse it went.',
};

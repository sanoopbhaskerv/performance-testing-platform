/**
 * Consumer-language labels for the platform's own identifiers.
 *
 * A module rather than an export from a component file, so that a component file exports only
 * components — which is what React Fast Refresh needs, and what the lint rule enforces.
 *
 * Scoped deliberately to labelling and nothing else. This is not a place for general helpers; a
 * shared module with no stated boundary is how a `utils` dumping ground starts (CLAUDE.md §8).
 */

/** Metric identifiers this release evaluates, in the words a consumer uses for them. */
const METRIC_LABELS: Record<string, string> = {
  'http.response.time': 'Response time',
  'http.error.rate': 'Error rate',
  'http.throughput': 'Throughput',
  'browser.journey.duration': 'Browser journey duration',
  'browser.step.duration': 'Browser step duration',
  'browser.error.rate': 'Browser error rate',
  'web.lcp': 'Largest Contentful Paint',
  'web.inp': 'Interaction to Next Paint',
  'web.cls': 'Cumulative Layout Shift',
  'web.ttfb': 'Time to First Byte',
  'web.tbt': 'Total Blocking Time',
  'web.lighthouse.score': 'Lighthouse score',
};

/**
 * An objective's metric and scope, as one readable label.
 *
 * The scope alone is not enough to tell two objectives apart: a response-time ceiling and an
 * error-rate ceiling on the same workload both read as the workload's name, leaving a reader unable
 * to see which verdict answers which question.
 *
 * An unrecognized metric falls back to its identifier rather than to a guess. Inventing a friendly
 * name for a metric this release does not evaluate would describe a check that did not happen.
 */
export function objectiveLabel(metric: string, scope: string): string {
  return `${METRIC_LABELS[metric] ?? metric} · ${scope}`;
}

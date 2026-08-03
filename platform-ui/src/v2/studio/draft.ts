import type { CorrelationSuggestion, ImportedScenario } from '../api';

/**
 * What the studio holds while somebody builds a test.
 *
 * The studio's screens edit this; one function turns it into the canonical specification
 * (`toSpecification`). Keeping the two apart is what makes the interesting half testable without
 * rendering anything, and it is why the acceptance criterion for S1 — that a studio-built test is
 * byte-identical to the same test written by hand in YAML — is checkable at all.
 *
 * ## Consumer language
 *
 * Every field here is named the way CLAUDE.md §24 requires: expected users, ramp-up, steady state,
 * journey mix, response-time objective. Nothing is called a thread group, a sampler or a workload
 * model. The translation into the platform's vocabulary happens in one place, on the way out.
 */

/** Which question the test answers. The studio's first screen, and it shapes every later one. */
export type TestType = 'api-load' | 'web-experience';

/**
 * One journey in the test: a request, and how much of the traffic it represents.
 *
 * `share` is what the specification calls a scenario weight. It is a percentage of iterations, not
 * a number of users, and the studio says so — "60% of traffic" is a sentence someone can check
 * against what they know about their application, where "weight: 60" is not.
 */
export interface Journey {
  name: string;
  share: number;
  method: string | null;
  path: string | null;
  headers: Record<string, string>;
  query: Record<string, string>;
  body: { contentType: string; content: string } | null;
  steps: JourneyStep[];
  correlationSuggestions: CorrelationRuleDraft[];
  /** What the source called this operation, kept so a report can be matched back to it. */
  operationId: string | null;
  /** Cleared on the Refine screen to drop a journey without losing it. */
  include: boolean;
}

export interface JourneyStep {
  name: string;
  method: string;
  path: string;
  headers: Record<string, string>;
  query: Record<string, string>;
  body: { contentType: string; content: string } | null;
  expectStatus: boolean;
  expectedStatus: number | null;
  thinkTimeSeconds: number;
}

export interface CorrelationRuleDraft extends CorrelationSuggestion {
  decision: 'pending' | 'accepted' | 'rejected';
}

/**
 * The shape of the load, in the three parts a person actually thinks in.
 *
 * Not a list of stages. A stage list is how the platform expresses this and it is a poor way to ask
 * the question: someone who has never run a performance test knows how many people they expect and
 * roughly how long to hold it, and does not know that the last stage has to target zero users.
 */
export interface LoadShape {
  peakUsers: number;
  rampUpSeconds: number;
  steadySeconds: number;
  rampDownSeconds: number;
}

/** The metrics the platform can currently judge. An objective on anything else is not evaluated. */
export type ObjectiveMetric = 'http.response.time' | 'http.error.rate' | 'http.throughput';

export interface ObjectiveDraft {
  metric: ObjectiveMetric;
  /** Only meaningful for a latency objective; null for a rate or a throughput. */
  percentile: number | null;
  /** A journey name to scope to, or null for the whole test. */
  journey: string | null;
  /** The number the consumer typed. Its unit comes from the metric. */
  value: number;
}

export interface StudioDraft {
  testType: TestType;
  name: string;
  team: string;
  application: string;
  environment: string;
  runnerCell: string;
  journeys: Journey[];
  load: LoadShape;
  objectives: ObjectiveDraft[];
}

/**
 * A new draft.
 *
 * Every default is a working value, so a test needs a name, a target and nothing else to run
 * (PLAN.md §4.3). They are deliberately small: 10 users for two minutes is a smoke test somebody can
 * afford to get wrong, and someone who wants 500 users will say so. A default that booked a large
 * slot would make a first attempt expensive.
 */
export function newDraft(testType: TestType, team: string): StudioDraft {
  return {
    testType,
    name: '',
    team,
    application: '',
    environment: 'performance',
    runnerCell: 'local',
    journeys: [],
    load: { peakUsers: 10, rampUpSeconds: 30, steadySeconds: 120, rampDownSeconds: 15 },
    objectives: [
      { metric: 'http.response.time', percentile: 95, journey: null, value: 1000 },
      { metric: 'http.error.rate', percentile: null, journey: null, value: 1 },
    ],
  };
}

/** The journeys that will actually run. */
export function includedJourneys(draft: StudioDraft): Journey[] {
  return draft.journeys.filter((journey) => journey.include);
}

/**
 * Turns imported scenarios into journeys, keeping every one and letting the Refine screen decide.
 *
 * Nothing is dropped here even when an importer clearly did not intend it to run. What an importer
 * removed is reported as a finding, and a journey silently missing from the table is a finding
 * nobody can act on.
 */
export function journeysFromImport(
  scenarios: ImportedScenario[],
  suggestions: CorrelationSuggestion[] = [],
): Journey[] {
  return scenarios.map((scenario) => ({
    name: scenario.name,
    share: scenario.weight,
    method: scenario.method,
    path: scenario.path,
    headers: scenario.headers,
    query: scenario.query,
    body: scenario.body,
    steps: scenario.steps.map((step) => ({
      name: step.name,
      method: step.method,
      path: step.path,
      headers: step.headers,
      query: step.query,
      body: step.body,
      expectStatus: false,
      expectedStatus: null,
      thinkTimeSeconds: 0,
    })),
    correlationSuggestions: suggestions
      .filter((suggestion) => suggestion.scenarioName === scenario.name)
      .map((suggestion) => ({ ...suggestion, decision: 'pending' as const })),
    operationId: scenario.operationId,
    include: true,
  }));
}

/**
 * Re-apportions shares across the included journeys so they sum to exactly 100.
 *
 * Largest remainder, the same method the HAR importer uses, so dropping a journey in the studio and
 * re-importing without it produce the same mix rather than two subtly different tests.
 *
 * Every journey keeps at least 1. A share of zero means the journey never runs, which the
 * specification refuses outright — and a test that silently excluded a journey somebody chose to
 * include would be describing traffic nobody asked for.
 */
export function normalizeShares(journeys: Journey[]): Journey[] {
  if (journeys.length === 0) return [];
  if (journeys.length > 100) {
    // Above 100 journeys the guarantee is arithmetically impossible; the caller must reduce first.
    throw new RangeError('A test cannot have more than 100 journeys, since each needs at least 1%.');
  }

  const total = journeys.reduce((sum, journey) => sum + Math.max(0, journey.share), 0);
  if (total <= 0) {
    // No usable input: an equal split is the only honest answer, and it says so by being even.
    return apportionEvenly(journeys);
  }

  const exact = journeys.map((journey) => (Math.max(0, journey.share) / total) * 100);
  const floors = exact.map((share) => Math.max(1, Math.floor(share)));

  let remaining = 100 - floors.reduce((sum, share) => sum + share, 0);
  const byRemainder = exact
    .map((share, index) => ({ index, remainder: share - Math.floor(share) }))
    .sort((a, b) => b.remainder - a.remainder || a.index - b.index);

  const shares = [...floors];
  // Hand out what is left one point at a time, largest fractional part first. When flooring
  // overshot (every journey was forced up to 1), take back from the smallest remainders instead.
  for (let i = 0; remaining > 0; i = (i + 1) % byRemainder.length) {
    shares[byRemainder[i].index] += 1;
    remaining -= 1;
  }
  for (let i = byRemainder.length - 1; remaining < 0; i = i === 0 ? byRemainder.length - 1 : i - 1) {
    if (shares[byRemainder[i].index] > 1) {
      shares[byRemainder[i].index] -= 1;
      remaining += 1;
    }
  }

  return journeys.map((journey, index) => ({ ...journey, share: shares[index] }));
}

function apportionEvenly(journeys: Journey[]): Journey[] {
  const base = Math.floor(100 / journeys.length);
  const extra = 100 - base * journeys.length;
  return journeys.map((journey, index) => ({
    ...journey,
    share: base + (index < extra ? 1 : 0),
  }));
}

/**
 * A name the platform will accept.
 *
 * A test's name reaches artifact object keys and Kubernetes labels, so it has to be lowercase
 * alphanumeric with hyphens. Applied as someone types rather than rejected on save: a validation
 * error about label syntax is a demand that a consumer learn Kubernetes' naming rules to run a test.
 */
export function toResourceName(raw: string): string {
  return raw
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 63);
}

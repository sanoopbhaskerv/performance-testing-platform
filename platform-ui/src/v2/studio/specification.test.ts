import { describe, it, expect } from 'vitest';
import {
  toSpecification,
  humanDuration,
  objectiveThreshold,
  totalDurationSeconds,
  approximateDuration,
  governanceCeilingSeconds,
  IncompleteDraftError,
} from './specification';
import { newDraft, normalizeShares, toResourceName, includedJourneys } from './draft';
import type { Journey, StudioDraft } from './draft';

/**
 * The default load shape, spread into partial overrides.
 *
 * Present so a test that cares about durations does not have to restate the load model, the rate
 * and the concurrency ceiling — and so adding a field to the shape does not break tests that have
 * nothing to say about it.
 */
const LOAD = {
  model: 'users' as const,
  peakUsers: 10,
  peakRate: 50,
  maxConcurrency: 25,
  rampUpSeconds: 30,
  steadySeconds: 120,
  rampDownSeconds: 15,
};


/**
 * The studio's central claim, tested: a test built by clicking is the same document as a test
 * written by hand.
 *
 * CLAUDE.md §32 requires one canonical specification behind the UI, the REST API and YAML. It is
 * easy to satisfy that on paper and lose it in practice — the studio grows a field, the emitter
 * grows a line, and the two channels drift into describing subtly different tests. The
 * byte-for-byte comparison below is what makes the drift impossible to miss.
 */

function journey(overrides: Partial<Journey> = {}): Journey {
  return {
    name: 'browse-users',
    share: 100,
    method: 'GET',
    path: '/api/users',
    headers: {},
    query: {},
    body: null,
    steps: [],
    correlationSuggestions: [],
    operationId: null,
    include: true,
    ...overrides,
  };
}

function draft(overrides: Partial<StudioDraft> = {}): StudioDraft {
  return {
    ...newDraft('api-load', 'local-dev'),
    name: 'checkout-baseline',
    application: 'checkout',
    journeys: [journey()],
    ...overrides,
  };
}

describe('toSpecification', () => {
  it('emits exactly the document someone would have written by hand', () => {
    const spec = toSpecification(
      draft({
        journeys: [
          journey({ name: 'browse-users', share: 80 }),
          journey({ name: 'submit-payment', share: 20, method: 'POST', path: '/api/payments' }),
        ],
        load: { ...LOAD, peakUsers: 10, rampUpSeconds: 30, steadySeconds: 60, rampDownSeconds: 15 },
        objectives: [
          { metric: 'http.response.time', percentile: 95, journey: null, value: 400 },
          { metric: 'http.error.rate', percentile: null, journey: 'browse-users', value: 1 },
        ],
      }),
    );

    expect(spec).toBe(
      `apiVersion: performance.ameriprise.com/v1alpha1
kind: PerformanceTest

metadata:
  name: checkout-baseline
  team: local-dev
  application: checkout

spec:
  environment:
    name: performance
    runnerCell: local
    targetClass: non-production

  workloads:
    - name: checkout-baseline-load
      type: protocol
      engine: jmeter
      model:
        type: stages
        stages:
          - duration: 30s
            targetUsers: 10
          - duration: 1m
            targetUsers: 10
          - duration: 15s
            targetUsers: 0

      scenarios:
        - name: "browse-users"
          weight: 80
          method: GET
          path: "/api/users"
        - name: "submit-payment"
          weight: 20
          method: POST
          path: "/api/payments"

  objectives:
    - metric: http.response.time
      scope: checkout-baseline-load
      percentile: 95
      threshold: 400ms
    - metric: http.error.rate
      scope: checkout-baseline-load/browse-users
      threshold: 1%

  governance:
    maxDuration: 12m
`,
    );
  });

  it('states a governance ceiling, so no consumer sees a warning they cannot act on', () => {
    // The validator warns when maxDuration is absent. The studio knows the answer, so it says it.
    const spec = toSpecification(
      draft({ load: { ...LOAD, peakUsers: 5, rampUpSeconds: 0, steadySeconds: 300, rampDownSeconds: 0 } }),
    );

    expect(spec).toContain('maxDuration: 15m');
  });

  it('omits the percentile line for a metric that has none', () => {
    const spec = toSpecification(
      draft({ objectives: [{ metric: 'http.error.rate', percentile: null, journey: null, value: 2 }] }),
    );

    expect(spec).toContain('threshold: 2%');
    expect(spec).not.toContain('percentile:');
  });

  it('carries headers, query and body through the escaping that is already tested', () => {
    const spec = toSpecification(
      draft({
        journeys: [
          journey({
            method: 'POST',
            path: '/api/orders',
            headers: { 'X-Note': 'he said "hello"' },
            query: { q: 'a b&c' },
            body: { contentType: 'application/json', content: '{"a": 1}' },
          }),
        ],
      }),
    );

    expect(spec).toContain('"X-Note": "he said \\"hello\\""');
    expect(spec).toContain('"q": "a b&c"');
    expect(spec).toContain('content: "{\\"a\\": 1}"');
  });

  it('emits accepted correlation rules as reviewed extractions and replacements', () => {
    const spec = toSpecification(
      draft({
        journeys: [
          journey({
            method: null,
            path: null,
            steps: [
              {
                name: 'post-api-basket',
                method: 'POST',
                path: '/api/basket',
                headers: {},
                query: {},
                body: null,
                expectStatus: false,
                expectedStatus: null,
                thinkTimeSeconds: 0,
              },
              {
                name: 'post-api-orders',
                method: 'POST',
                path: '/api/orders',
                headers: { 'Content-Type': 'application/json' },
                query: {},
                body: {
                  contentType: 'application/json',
                  content: '{"basketId":"basket-123"}',
                },
                expectStatus: true,
                expectedStatus: 201,
                thinkTimeSeconds: 3,
              },
            ],
            correlationSuggestions: [
              {
                id: 'checkout:post-api-basket:basketId',
                scenarioName: 'browse-users',
                variableName: 'basketId',
                decision: 'accepted',
                extraction: {
                  stepName: 'post-api-basket',
                  from: 'json-body',
                  expression: '$.basketId',
                  valuePreview: 'basket-123',
                },
                replacements: [
                  {
                    stepName: 'post-api-orders',
                    location: 'body',
                    name: null,
                    currentValue: 'basket-123',
                  },
                ],
              },
            ],
          }),
        ],
      }),
    );

    expect(spec).toContain('steps:');
    expect(spec).toContain('extract:');
    expect(spec).toContain('name: "basketId"');
    expect(spec).toContain('expression: "$.basketId"');
    expect(spec).toContain('content: "{\\"basketId\\":\\"${basketId}\\"}"');
    expect(spec).toContain('expect:');
    expect(spec).toContain('value: "201"');
    expect(spec).toContain('thinkTime: 3s');
  });

  it('does not apply pending or rejected correlation suggestions', () => {
    const spec = toSpecification(
      draft({
        journeys: [
          journey({
            method: null,
            path: null,
            steps: [
              {
                name: 'source',
                method: 'GET',
                path: '/api/source',
                headers: {},
                query: {},
                body: null,
                expectStatus: false,
                expectedStatus: null,
                thinkTimeSeconds: 0,
              },
              {
                name: 'target',
                method: 'GET',
                path: '/api/target/basket-123',
                headers: {},
                query: {},
                body: null,
                expectStatus: false,
                expectedStatus: null,
                thinkTimeSeconds: 0,
              },
            ],
            correlationSuggestions: [
              {
                id: 'pending',
                scenarioName: 'browse-users',
                variableName: 'basketId',
                decision: 'pending',
                extraction: {
                  stepName: 'source',
                  from: 'json-body',
                  expression: '$.basketId',
                  valuePreview: 'basket-123',
                },
                replacements: [
                  { stepName: 'target', location: 'path', name: null, currentValue: 'basket-123' },
                ],
              },
            ],
          }),
        ],
      }),
    );

    expect(spec).not.toContain('extract:');
    expect(spec).toContain('path: "/api/target/basket-123"');
    expect(spec).not.toContain('${basketId}');
  });

  it('excludes journeys dropped on the Refine screen, and re-apportions what is left', () => {
    const spec = toSpecification(
      draft({
        journeys: [
          journey({ name: 'kept', share: 50 }),
          journey({ name: 'dropped', share: 50, include: false }),
        ],
      }),
    );

    expect(spec).toContain('- name: "kept"');
    expect(spec).not.toContain('dropped');
    // The survivor takes the whole mix rather than keeping a 50 that no longer sums to 100.
    expect(spec).toContain('weight: 100');
  });

  describe('refusals — the studio blocks Save rather than emitting a document that cannot run', () => {
    it.each([
      ['no name', draft({ name: '' }), 'needs a name'],
      ['no application', draft({ application: '' }), 'needs an application'],
      ['no journeys', draft({ journeys: [] }), 'at least one journey'],
      ['every journey dropped', draft({ journeys: [journey({ include: false })] }), 'at least one journey'],
    ])('refuses a draft with %s', (_case, incomplete, message) => {
      expect(() => toSpecification(incomplete)).toThrow(IncompleteDraftError);
      expect(() => toSpecification(incomplete)).toThrow(message);
    });
  });

  describe('the load shape', () => {
    it('always ends at zero users, which is what expresses ramp-down', () => {
      const spec = toSpecification(draft());
      const stages = spec.slice(spec.indexOf('stages:'), spec.indexOf('scenarios:'));

      expect(stages.trimEnd().endsWith('targetUsers: 0')).toBe(true);
    });

    it('omits a zero-length ramp rather than emitting a stage with no duration', () => {
      const spec = toSpecification(
        draft({ load: { ...LOAD, peakUsers: 5, rampUpSeconds: 0, steadySeconds: 60, rampDownSeconds: 0 } }),
      );
      const stages = spec.slice(spec.indexOf('stages:'), spec.indexOf('scenarios:'));

      expect(stages).toContain('duration: 1m');
      expect(stages).not.toContain('duration: 0s');
      expect(stages.match(/- duration:/g)).toHaveLength(1);
    });

    it('still emits a stage when every duration is zero, so the document stays valid', () => {
      const spec = toSpecification(
        draft({ load: { ...LOAD, peakUsers: 5, rampUpSeconds: 0, steadySeconds: 0, rampDownSeconds: 0 } }),
      );

      expect(spec).toContain('- duration: 1m');
      expect(spec).toContain('targetUsers: 5');
    });
  });
});

describe('humanDuration', () => {
  it.each([
    [0, '0s'],
    [15, '15s'],
    [60, '1m'],
    [90, '90s'],
    [120, '2m'],
    [3600, '1h'],
    [5400, '90m'],
  ])('renders %i seconds as %s', (seconds, expected) => {
    expect(humanDuration(seconds)).toBe(expected);
  });

  it('keeps a duration exact rather than rounding it to a nicer unit', () => {
    // 90 seconds is not "about 2 minutes". The number someone typed is how long the test runs.
    expect(humanDuration(90)).toBe('90s');
  });
});

describe('approximateDuration', () => {
  it.each([
    [45, '45 seconds'],
    [60, '1 minute'],
    [165, '3 minutes'],
    [900, '15 minutes'],
    [3600, '1 hour'],
    [5400, '1h 30m'],
  ])('renders %i seconds as %s for a sentence a person reads', (seconds, expected) => {
    expect(approximateDuration(seconds)).toBe(expected);
  });

  it('rounds where humanDuration does not, because the two answer different questions', () => {
    // The document must run for exactly as long as somebody asked; the summary says "about".
    expect(humanDuration(165)).toBe('165s');
    expect(approximateDuration(165)).toBe('3 minutes');
  });
});

describe('objectiveThreshold', () => {
  it.each([
    ['http.response.time' as const, 750, '750ms'],
    ['http.error.rate' as const, 1, '1%'],
    ['http.throughput' as const, 200, '200/s'],
  ])('gives %s the unit its metric implies', (metric, value, expected) => {
    expect(objectiveThreshold({ metric, percentile: null, journey: null, value })).toBe(expected);
  });
});

describe('normalizeShares', () => {
  it('leaves a mix that already sums to 100 alone', () => {
    const shares = normalizeShares([journey({ share: 60 }), journey({ share: 40 })]);

    expect(shares.map((j) => j.share)).toEqual([60, 40]);
  });

  it('scales a mix that does not sum to 100', () => {
    const shares = normalizeShares([journey({ share: 3 }), journey({ share: 1 })]);

    expect(shares.map((j) => j.share)).toEqual([75, 25]);
  });

  it.each([
    [2, [50, 50]],
    [3, [34, 33, 33]],
    [7, [15, 15, 14, 14, 14, 14, 14]],
  ])('splits %i equal journeys to exactly 100', (count, expected) => {
    const shares = normalizeShares(Array.from({ length: count }, () => journey({ share: 1 })));

    expect(shares.reduce((sum, j) => sum + j.share, 0)).toBe(100);
    expect(shares.map((j) => j.share)).toEqual(expected);
  });

  it('gives every journey at least 1, because a zero share means it never runs', () => {
    // 999 to 1 would floor the second journey to 0, and the specification refuses a zero weight.
    const shares = normalizeShares([journey({ share: 999 }), journey({ share: 1 })]);

    expect(Math.min(...shares.map((j) => j.share))).toBeGreaterThanOrEqual(1);
    expect(shares.reduce((sum, j) => sum + j.share, 0)).toBe(100);
  });

  it('splits evenly when no journey carries a usable share', () => {
    const shares = normalizeShares([journey({ share: 0 }), journey({ share: 0 })]);

    expect(shares.map((j) => j.share)).toEqual([50, 50]);
  });

  it('refuses more than 100 journeys, where the guarantee is arithmetically impossible', () => {
    const many = Array.from({ length: 101 }, () => journey({ share: 1 }));

    expect(() => normalizeShares(many)).toThrow(RangeError);
  });

  it('sums to exactly 100 across many awkward mixes', () => {
    for (let count = 1; count <= 100; count++) {
      const journeys = Array.from({ length: count }, (_, i) => journey({ share: i + 1 }));
      const total = normalizeShares(journeys).reduce((sum, j) => sum + j.share, 0);
      expect(total, `${count} journeys`).toBe(100);
    }
  });
});

describe('toResourceName', () => {
  it.each([
    ['Checkout Baseline', 'checkout-baseline'],
    ['listOrdersByCustomer', 'listordersbycustomer'],
    ['  spaces  ', 'spaces'],
    ['a//b__c', 'a-b-c'],
    ['---leading---', 'leading'],
  ])('turns %s into %s', (raw, expected) => {
    expect(toResourceName(raw)).toBe(expected);
  });

  it('truncates to a length a Kubernetes label accepts', () => {
    expect(toResourceName('x'.repeat(200))).toHaveLength(63);
  });
});

describe('totalDurationSeconds', () => {
  it('adds the three parts, so the studio can say how long this will take', () => {
    expect(
      totalDurationSeconds(draft({ load: { ...LOAD, peakUsers: 1, rampUpSeconds: 30, steadySeconds: 120, rampDownSeconds: 15 } })),
    ).toBe(165);
  });
});

describe('governanceCeilingSeconds', () => {
  it('clears the stages by the platform\u2019s own execution grace', () => {
    // Startup, ramp-down and artifact upload all sit outside the stage arithmetic. A ceiling that
    // clipped them would kill healthy runs exactly when they were writing their evidence.
    const shape = draft({ load: { ...LOAD, peakUsers: 1, rampUpSeconds: 30, steadySeconds: 120, rampDownSeconds: 15 } });

    expect(governanceCeilingSeconds(shape)).toBeGreaterThanOrEqual(totalDurationSeconds(shape) + 600);
  });

  it('rounds up to a whole minute, never down', () => {
    // 105s of stages plus ten minutes is 705s. Rounding down would give 11m, which is under the
    // grace the ceiling exists to allow.
    const shape = draft({ load: { ...LOAD, peakUsers: 1, rampUpSeconds: 30, steadySeconds: 60, rampDownSeconds: 15 } });

    expect(governanceCeilingSeconds(shape)).toBe(720);
    expect(toSpecification(shape)).toContain('maxDuration: 12m');
  });
});

describe('includedJourneys', () => {
  it('returns only what will run', () => {
    const kept = includedJourneys(
      draft({ journeys: [journey({ name: 'a' }), journey({ name: 'b', include: false })] }),
    );

    expect(kept.map((j) => j.name)).toEqual(['a']);
  });

  describe('the open load model', () => {
    /**
     * The studio's whole job here is that a person picks a question and gets a document that asks
     * it. A rate model that emitted targetUsers would be refused by the validator — and rightly,
     * because the run would send whatever the users produced rather than the rate that was asked
     * for.
     */
    it('emits a rate profile with its ceiling when the model is a rate', () => {
      const spec = toSpecification({
        ...draft(),
        load: {
          model: 'rate',
          peakUsers: 10,
          peakRate: 400,
          maxConcurrency: 250,
          rampUpSeconds: 60,
          steadySeconds: 300,
          rampDownSeconds: 30,
        },
      });

      expect(spec).toContain('type: arrival-rate');
      expect(spec).toContain('maxConcurrency: 250');
      expect(spec).toContain('targetRate: 400');
      expect(spec).not.toContain('targetUsers');
    });

    it('emits a user profile with no ceiling when the model is users', () => {
      const spec = toSpecification(draft());

      expect(spec).toContain('type: stages');
      expect(spec).toContain('targetUsers:');
      // Refused by the validator on a staged model: the user count already is the concurrency.
      expect(spec).not.toContain('maxConcurrency');
      expect(spec).not.toContain('targetRate');
    });

    it('ends a rate profile at zero, which is what expresses ramp-down', () => {
      const spec = toSpecification({
        ...draft(),
        load: {
          model: 'rate',
          peakUsers: 10,
          peakRate: 400,
          maxConcurrency: 250,
          rampUpSeconds: 60,
          steadySeconds: 300,
          rampDownSeconds: 30,
        },
      });

      expect(spec.trimEnd()).toContain('targetRate: 0');
    });
  });
});

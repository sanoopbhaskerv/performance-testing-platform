import { describe, it, expect } from 'vitest';
import { stepsFor, blockerFor, outstandingBlockers, isSaveable } from './steps';
import { newDraft } from './draft';
import { toSpecification } from './specification';
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
 * The rules that decide whether someone can move on.
 *
 * Tested because a wizard's worst failure is a disabled button with no explanation. The studio uses
 * these in two places — whether *Next* is available, and what it says is missing — and the two
 * agreeing is what stops somebody being stranded on a step with no idea why.
 */

const journey = (over: Partial<Journey> = {}): Journey => ({
  name: 'browse-users', share: 100, method: 'GET', path: '/api/users',
  headers: {}, query: {}, body: null, steps: [], correlationSuggestions: [],
  operationId: null, include: true, ...over,
});

const draft = (over: Partial<StudioDraft> = {}): StudioDraft => ({
  ...newDraft('api-load', 'local-dev'),
  name: 'checkout-baseline', application: 'checkout', journeys: [journey()], ...over,
});

describe('stepsFor', () => {
  it('names the browser step for what it is, not for load', () => {
    const web = stepsFor('web-experience').find((s) => s.id === 'load');
    const api = stepsFor('api-load').find((s) => s.id === 'load');

    expect(web?.title).toBe('Browsers');
    expect(api?.title).toBe('Load');
  });

  it('ends both flows at review', () => {
    for (const type of ['api-load', 'web-experience'] as const) {
      expect(stepsFor(type).at(-1)?.id).toBe('review');
    }
  });

  it('puts rules between journey review and load', () => {
    expect(stepsFor('api-load').map((step) => step.id)).toEqual([
      'sources',
      'about',
      'refine',
      'rules',
      'load',
      'objectives',
      'validate',
      'review',
    ]);
  });

  /**
   * The Validator resolves its target from team, application and environment. Asking for them after
   * it ran meant it failed with `metadata.team is required` against a draft the studio had never
   * asked about.
   */
  it('asks what the test points at before trying to validate it', () => {
    for (const type of ['api-load', 'web-experience'] as const) {
      const ids = stepsFor(type).map((step) => step.id);
      expect(ids.indexOf('about')).toBeLessThan(ids.indexOf('validate'));
    }
  });
});

describe('blockerFor', () => {
  it('asks for a journey before anything else', () => {
    expect(blockerFor('sources', draft({ journeys: [] }))).toMatch(/add a request/);
  });

  it('refuses a refine step where everything was dropped', () => {
    expect(blockerFor('refine', draft({ journeys: [journey({ include: false })] })))
      .toMatch(/at least one journey/);
  });

  it('requires a rate and a ceiling when the model is a rate', () => {
    expect(
      blockerFor('load', draft({ load: { ...LOAD, model: 'rate', peakRate: 0 } })),
    ).toMatch(/at least one request per second/);

    // The platform refuses a ceiling-less rate model outright, so the studio says so here rather
    // than letting somebody reach Save and meet the refusal there.
    expect(
      blockerFor('load', draft({ load: { ...LOAD, model: 'rate', maxConcurrency: 0 } })),
    ).toMatch(/waiting at once/);

    expect(blockerFor('load', draft({ load: { ...LOAD, model: 'rate' } }))).toBeNull();
  });

  it('requires a user and a duration', () => {
    expect(blockerFor('load', draft({ load: { ...LOAD, peakUsers: 0, rampUpSeconds: 10, steadySeconds: 10, rampDownSeconds: 0 } })))
      .toMatch(/at least one user/);
    expect(blockerFor('load', draft({ load: { ...LOAD, peakUsers: 5, rampUpSeconds: 0, steadySeconds: 0, rampDownSeconds: 0 } })))
      .toMatch(/needs a duration/);
  });

  it('does not require objectives', () => {
    // Forcing a threshold before a first run produces a number somebody invented and then trusts.
    expect(blockerFor('objectives', draft({ objectives: [] }))).toBeNull();
  });

  it('requires a name, a team and an application before validating', () => {
    expect(blockerFor('about', draft({ name: '' }))).toMatch(/needs a name/);
    expect(blockerFor('about', draft({ team: '' }))).toMatch(/needs a team/);
    expect(blockerFor('about', draft({ application: '' }))).toMatch(/an application/);
    expect(blockerFor('about', draft({}))).toBeNull();
  });

  it('phrases every blocker as the thing to do, not the rule that was broken', () => {
    const messages = outstandingBlockers(draft({ journeys: [], name: '', application: '' }))
      .map((b) => b.blocker);

    expect(messages.length).toBeGreaterThan(0);
    for (const message of messages) {
      expect(message).not.toMatch(/must be|cannot be null|length|undefined|\bnull\b/);
      expect(message.endsWith('.')).toBe(true);
    }
  });
});

describe('isSaveable', () => {
  it('is true for a complete draft', () => {
    expect(isSaveable(draft())).toBe(true);
  });

  it.each([
    ['no journeys', draft({ journeys: [] })],
    ['no name', draft({ name: '' })],
    ['no application', draft({ application: '' })],
    ['every journey dropped', draft({ journeys: [journey({ include: false })] })],
  ])('is false with %s, and says why', (_case, incomplete) => {
    expect(isSaveable(incomplete)).toBe(false);
    expect(outstandingBlockers(incomplete).length).toBeGreaterThan(0);
  });

  it('agrees with the emitter about what is incomplete', () => {
    // The two must never disagree: a saveable draft that the emitter refuses would be a Save button
    // that throws, and an unsaveable draft the emitter accepts would be a button disabled for no
    // visible reason.
    const cases = [draft(), draft({ journeys: [] }), draft({ name: '' }), draft({ application: '' })];
    for (const candidate of cases) {
      let emitterAccepts = true;
      try {
        toSpecification(candidate);
      } catch {
        emitterAccepts = false;
      }
      expect(isSaveable(candidate)).toBe(emitterAccepts);
    }
  });
});

import { describe, it, expect } from 'vitest';
import { stepsFor, blockerFor, outstandingBlockers, isSaveable } from './steps';
import { newDraft } from './draft';
import { toSpecification } from './specification';
import type { Journey, StudioDraft } from './draft';

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
      'refine',
      'rules',
      'load',
      'objectives',
      'review',
    ]);
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

  it('requires a user and a duration', () => {
    expect(blockerFor('load', draft({ load: { peakUsers: 0, rampUpSeconds: 10, steadySeconds: 10, rampDownSeconds: 0 } })))
      .toMatch(/at least one user/);
    expect(blockerFor('load', draft({ load: { peakUsers: 5, rampUpSeconds: 0, steadySeconds: 0, rampDownSeconds: 0 } })))
      .toMatch(/needs a duration/);
  });

  it('does not require objectives', () => {
    // Forcing a threshold before a first run produces a number somebody invented and then trusts.
    expect(blockerFor('objectives', draft({ objectives: [] }))).toBeNull();
  });

  it('requires a name and an application before saving', () => {
    expect(blockerFor('review', draft({ name: '' }))).toMatch(/needs a name/);
    expect(blockerFor('review', draft({ application: '' }))).toMatch(/an application/);
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

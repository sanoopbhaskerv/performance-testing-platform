import { describe, it, expect } from 'vitest';
import { newDraft } from './draft';
import { toSpecification } from './specification';
import { blockerFor, stepsFor } from './steps';

function draft() {
  const d = newDraft('web-experience', 'team-a');
  d.name = 'checkout'; d.application = 'shop';
  d.browser = { ...d.browser, reviewed: true, uri: 'artifact://teams/team-a/sources/playwright/fingerprint/journey.spec.ts', digest: 'sha256:' + 'a'.repeat(64), uploadedForTeam: 'team-a' };
  return d;
}
describe('browser wizard contract', () => {
  it('emits a browser workload and browser objectives without a protocol load model', () => {
    const text = toSpecification(draft());
    expect(text).toContain('type: browser'); expect(text).toContain('engine: playwright');
    expect(text).toContain('browserUsers: 1'); expect(text).toContain('iterationsPerUser: 1');
    expect(text).toContain('metric: browser.journey.duration'); expect(text).toContain('metric: browser.error.rate');
    expect(text).not.toContain('engine: jmeter'); expect(text).not.toContain('scenarios:');
    expect(text).toContain('network: none');
  });
  it('requires team ownership and a reviewed stored script', () => {
    const d = draft(); d.team = 'team-b';
    expect(blockerFor('sources', d)).toMatch(/this team/);
    expect(() => toSpecification(d)).toThrow(/selected team/);
    d.team = 'team-a'; d.browser.reviewed = false;
    expect(() => toSpecification(d)).toThrow(/Review/);
  });
  it('preserves the distinct units of page experience objectives', () => {
    const d = draft();
    d.objectives = [{ metric: 'web.cls', value: 0.1, percentile: 95, journey: null },
      { metric: 'web.ttfb', value: 800, percentile: 95, journey: null }];
    expect(toSpecification(d)).toContain('threshold: 0.1score');
    expect(toSpecification(d)).toContain('threshold: 800ms');
  });
  it('pins repository coordinates and quotes YAML-significant paths', () => {
    const d = draft(); d.browser.mode = 'git'; d.browser.repository = 'https://example.com/tests.git';
    d.browser.ref = 'f'.repeat(40); d.browser.path = 'tests/check:out.spec.ts';
    const text = toSpecification(d);
    expect(text).toContain('path: "tests/check:out.spec.ts"'); expect(text).not.toContain('      uri:');
    d.browser.ref = 'main'; expect(blockerFor('sources', d)).toMatch(/commit SHA/);
  });
  it('asks for a target before source upload and excludes API-only rule screens', () => {
    const ids = stepsFor('web-experience').map(s => s.id);
    expect(ids[0]).toBe('about'); expect(ids).not.toContain('rules'); expect(ids).not.toContain('refine');
  });
  it('enforces integer browser counts, repetitions and time limits', () => {
    const d = draft();
    for (const users of [0, 21, 1.5, NaN]) { d.browser.users = users; expect(blockerFor('load', d)).not.toBeNull(); }
    d.browser.users = 1; d.browser.iterations = 1001; expect(blockerFor('load', d)).not.toBeNull();
    d.browser.iterations = 1; d.browser.maxMinutes = 0; expect(blockerFor('load', d)).not.toBeNull();
    d.browser.maxMinutes = 5; expect(blockerFor('load', d)).toBeNull();
  });
});

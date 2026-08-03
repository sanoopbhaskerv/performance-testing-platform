import { describe, it, expect } from 'vitest';
import { scenariosToYaml, spliceScenarios } from './scenarioYaml';
import type { ImportedScenario } from './api';

/**
 * The first tests this UI has ever had, on the logic `HANDOFF.md` §3k named as the most valuable to
 * pin: YAML escaping, indentation detection and block extent.
 *
 * Why this module and not a component. Everything here is a pure function whose output is fed
 * straight back to the server as a specification. A mistake in it does not look like a broken screen
 * — it looks like a test that saves cleanly and describes a request nobody wrote. The server-side
 * validation round-trip catches most of that, and these tests catch the rest before it gets there.
 */

function scenario(overrides: Partial<ImportedScenario> = {}): ImportedScenario {
  return {
    name: 'browse-users',
    weight: 100,
    operationId: null,
    method: 'GET',
    path: '/api/users',
    headers: {},
    query: {},
    body: null,
    steps: [],
    ...overrides,
  };
}

describe('scenariosToYaml', () => {
  it('emits a scenario indented to sit under its scenarios key', () => {
    const yaml = scenariosToYaml([scenario()], 6);

    expect(yaml).toBe(
      ['        - name: "browse-users"', '          weight: 100', '          method: GET', '          path: "/api/users"'].join(
        '\n',
      ),
    );
  });

  it('carries operationId through when the source had one, and omits it when not', () => {
    expect(scenariosToYaml([scenario({ operationId: 'listUsers' })], 0)).toContain(
      'operationId: "listUsers"',
    );
    expect(scenariosToYaml([scenario({ operationId: null })], 0)).not.toContain('operationId');
  });

  describe('quoting — every imported value is whatever a source contained', () => {
    // Each case below is a value that would change the document's meaning, or break its parse,
    // if it were emitted bare. They are the reason quote() never tries to decide case by case.

    it('quotes a path containing a colon, which would otherwise read as a mapping', () => {
      expect(scenariosToYaml([scenario({ path: '/api/v1:search' })], 0)).toContain(
        'path: "/api/v1:search"',
      );
    });

    it('escapes an embedded double quote rather than ending the scalar early', () => {
      const yaml = scenariosToYaml([
        scenario({ headers: { 'X-Note': 'he said "hello"' } }),
      ], 0);

      expect(yaml).toContain('"X-Note": "he said \\"hello\\""');
    });

    it('escapes a backslash before anything else, so an escape is not doubled twice', () => {
      const yaml = scenariosToYaml([scenario({ query: { path: 'C:\\temp' } })], 0);

      expect(yaml).toContain('"path": "C:\\\\temp"');
    });

    it('folds a newline in a body to \\n, keeping the scalar on one line', () => {
      const yaml = scenariosToYaml([
        scenario({
          method: 'POST',
          body: { contentType: 'application/json', content: '{\n  "a": 1\n}' },
        }),
      ], 0);

      expect(yaml).toContain('content: "{\\n  \\"a\\": 1\\n}"');
      // One line per field. A raw newline here would silently truncate the body at the first break.
      expect(yaml.split('\n').filter((line) => line.includes('content:'))).toHaveLength(1);
    });

    it('quotes a value beginning with * or &, which YAML reads as an alias or anchor', () => {
      const yaml = scenariosToYaml([scenario({ headers: { Accept: '*/*' } })], 0);

      expect(yaml).toContain('"Accept": "*/*"');
    });
  });

  it('omits header and query blocks entirely when there are none', () => {
    const yaml = scenariosToYaml([scenario()], 0);

    expect(yaml).not.toContain('headers:');
    expect(yaml).not.toContain('query:');
  });
});

describe('spliceScenarios', () => {
  const document = [
    'spec:',
    '  workloads:',
    '    - name: demo-load',
    '      scenarios:',
    '        - name: placeholder',
    '          weight: 100',
    '  objectives:',
    '    - metric: http.error.rate',
  ].join('\n');

  it('replaces the journeys block and leaves everything after it intact', () => {
    const result = spliceScenarios(document, [scenario()]);

    expect(result.reason).toBeNull();
    expect(result.document).toContain('- name: "browse-users"');
    expect(result.document).not.toContain('placeholder');
    expect(result.document).toContain('  objectives:');
    expect(result.document).toContain('    - metric: http.error.rate');
  });

  it('replaces rather than appends, so a template\'s journeys do not survive alongside the import', () => {
    const result = spliceScenarios(document, [scenario()]);

    const names = (result.document ?? '').split('\n').filter((line) => line.includes('- name:'));
    // The workload's own `- name: demo-load` plus exactly one journey.
    expect(names).toHaveLength(2);
  });

  it('keeps the indentation of the key it replaced', () => {
    const result = spliceScenarios(document, [scenario()]);

    expect(result.document).toContain('      scenarios:');
    expect(result.document).toContain('        - name: "browse-users"');
  });

  describe('refusals — a text splice is only safe when the target is unambiguous', () => {
    it('refuses a document with two journey sections and says why', () => {
      const twoWorkloads = [
        'spec:',
        '  workloads:',
        '    - name: first',
        '      scenarios:',
        '        - name: a',
        '    - name: second',
        '      scenarios:',
        '        - name: b',
      ].join('\n');

      const result = spliceScenarios(twoWorkloads, [scenario()]);

      expect(result.document).toBeNull();
      expect(result.reason).toContain('2 journey sections');
    });

    it('refuses a document with no journey section', () => {
      const result = spliceScenarios('spec:\n  workloads: []', [scenario()]);

      expect(result.document).toBeNull();
      expect(result.reason).toContain('no journeys section');
    });

    it('refuses an empty import rather than emptying the document', () => {
      const result = spliceScenarios(document, []);

      expect(result.document).toBeNull();
      expect(result.reason).toContain('no journeys to insert');
    });
  });

  describe('block extent', () => {
    it('stops at the first line indented no further than the key', () => {
      const result = spliceScenarios(document, [scenario()]);
      const lines = (result.document ?? '').split('\n');

      // `objectives:` sits at two spaces, shallower than `scenarios:` at six, so it bounds the block.
      expect(lines[lines.indexOf('  objectives:') - 1]).toContain('path: "/api/users"');
    });

    it('leaves a trailing blank line with what follows, not with the block it replaced', () => {
      const spaced = [
        'spec:',
        '  workloads:',
        '    - name: demo-load',
        '      scenarios:',
        '        - name: placeholder',
        '',
        '  objectives: []',
      ].join('\n');

      const result = spliceScenarios(spaced, [scenario()]);
      const lines = (result.document ?? '').split('\n');

      expect(lines[lines.length - 2]).toBe('');
      expect(lines[lines.length - 1]).toBe('  objectives: []');
    });

    it('replaces a journeys block that runs to the end of the document', () => {
      const trailing = [
        'spec:',
        '  workloads:',
        '    - name: demo-load',
        '      scenarios:',
        '        - name: placeholder',
        '          weight: 100',
      ].join('\n');

      const result = spliceScenarios(trailing, [scenario()]);

      expect(result.reason).toBeNull();
      expect(result.document).not.toContain('placeholder');
      expect(result.document).toContain('- name: "browse-users"');
    });
  });
});

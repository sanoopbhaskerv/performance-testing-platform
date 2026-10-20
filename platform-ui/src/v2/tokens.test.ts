import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * The guard that makes the studio genuinely re-brandable.
 *
 * A design token system fails quietly. Nothing breaks when someone writes `#4f46e5` into a rule —
 * it looks right, it reviews fine, and it stays invisible until a deployment supplies its own brand
 * colour and one button stays indigo. This test is what turns that from a thing people have to
 * remember into a thing the build refuses.
 *
 * See PLAN.md §4.4.
 */

const V2_DIR = join(import.meta.dirname, '.');
const TOKEN_FILE = 'tokens.css';

/** Hex colours, and the rgb()/hsl() families in either syntax. */
const COLOUR_LITERAL = /#[0-9a-fA-F]{3,8}\b|\b(?:rgba?|hsla?)\(/;

function stylesheetsUnderTest(): string[] {
  return readdirSync(V2_DIR)
    .filter((name) => name.endsWith('.css') && name !== TOKEN_FILE)
    .sort();
}

/** Strips comments, so an example hex written in prose is not read as a rule. */
function withoutComments(css: string): string {
  return css.replace(/\/\*[\s\S]*?\*\//g, '');
}

describe('design tokens', () => {
  it('has stylesheets to check, so a rename cannot make this test vacuously pass', () => {
    expect(stylesheetsUnderTest().length).toBeGreaterThan(0);
  });

  it.each(stylesheetsUnderTest())('%s contains no colour literal', (file) => {
    const source = withoutComments(readFileSync(join(V2_DIR, file), 'utf8'));

    const offenders = source
      .split('\n')
      .map((line, index) => ({ line: line.trim(), number: index + 1 }))
      .filter(({ line }) => COLOUR_LITERAL.test(line));

    expect(
      offenders,
      `Colour literals belong in ${TOKEN_FILE}. Use a semantic token, or add one there:\n` +
        offenders.map((o) => `  ${file}:${o.number}  ${o.line}`).join('\n'),
    ).toEqual([]);
  });

  describe('tokens.css itself', () => {
    const tokens = readFileSync(join(V2_DIR, TOKEN_FILE), 'utf8');

    it('defines the brand primitives a re-brand replaces', () => {
      for (const name of ['--pf-brand-500', '--pf-brand-600', '--pf-brand-rgb']) {
        expect(tokens).toContain(`${name}:`);
      }
    });

    it('derives the action colour and the data series from the brand, not from a literal', () => {
      expect(tokens).toMatch(/--pf-action:\s*var\(--pf-brand-600\)/);
      expect(tokens).toMatch(/--pf-series-1:\s*var\(--pf-brand-500\)/);
    });

    it('keeps verdict colours separate from the brand', () => {
      // A brand override must not be able to render a breach in a reassuring green. Verdict tokens
      // resolve from their own status hues, and nothing in that chain reaches --pf-brand-*.
      const verdictBlock = tokens.slice(tokens.indexOf('--pf-verdict-pass-fg'));
      const verdictLines = verdictBlock
        .split('\n')
        .filter((line) => /--pf-(verdict|status)-/.test(line));

      expect(verdictLines.length).toBeGreaterThan(0);
      expect(verdictLines.join('\n')).not.toContain('--pf-brand');
    });

    it('declares channel triples space-separated, not comma-separated', () => {
      // Regression. These are consumed as `rgb(var(--pf-hue-fail) / 15%)`. Commas expand to
      // `rgb(239, 68, 68 / 15%)`, which mixes legacy and modern colour syntax, fails to parse, and
      // drops the declaration — silently. Every pill, banner and the synthetic-run disclosure lost
      // its background, and `border-color` fell back to `currentColor`. Nothing threw; it was found
      // by comparing computed styles in a browser against the values the stylesheet had before.
      const triples = [...tokens.matchAll(/(--pf-(?:hue-[a-z]+|brand-rgb)):([^;]+);/g)];

      expect(triples.length).toBeGreaterThan(0);
      for (const [, name, value] of triples) {
        expect(value, `${name} must use space-separated channels`).not.toContain(',');
        expect(value.trim().split(/\s+/), `${name} must have three channels`).toHaveLength(3);
      }
    });

    it('documents the override example with the same space-separated syntax', () => {
      // The comment is what a re-brand copies. An example with commas would break every status
      // surface, which is exactly the failure above with a longer fuse.
      const example = tokens.match(/--pf-brand-rgb: [^;}]+;\s*}/);
      expect(example?.[0]).not.toContain(',');
    });

    it('defines a light theme for every tint the dark theme defines', () => {
      const light = tokens.slice(tokens.indexOf("[data-theme='light']"));
      const darkTints = [...tokens.matchAll(/(--pf-tint-[a-z-]+):/g)].map((m) => m[1]);

      expect(darkTints.length).toBeGreaterThan(0);
      // --pf-tint-warn-rule is a border rule rather than text, so it needs no contrast reworking.
      for (const tint of darkTints.filter((t) => t !== '--pf-tint-warn-rule')) {
        expect(light, `${tint} has no light-theme value`).toContain(`${tint}:`);
      }
    });
  });
});

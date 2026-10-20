import { describe, expect, it } from 'vitest';
import { specificationFileName } from './download';

describe('specificationFileName', () => {
  it('names the file after the test', () => {
    expect(specificationFileName('student-crud-baseline')).toBe('student-crud-baseline.yaml');
  });

  it('falls back when the draft has no name yet', () => {
    // The Validate step comes before Review asks for a name, so this is a normal path rather than a
    // defensive one. A bare ".yaml" would be a hidden file on macOS and Linux.
    expect(specificationFileName('')).toBe('performance-test.yaml');
    expect(specificationFileName('   ')).toBe('performance-test.yaml');
  });

  it('does not keep surrounding whitespace in the file name', () => {
    expect(specificationFileName('  checkout-baseline  ')).toBe('checkout-baseline.yaml');
  });
});

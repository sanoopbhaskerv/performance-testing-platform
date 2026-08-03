/**
 * Test environment setup, loaded once before any test file.
 *
 * Registers jest-dom's matchers — `toBeInTheDocument`, `toHaveAccessibleName` and the rest — with
 * vitest's `expect`, both at runtime and in the type system. The `/vitest` entry point is what does
 * the latter; importing the plain `@testing-library/jest-dom` would leave every matcher a type error.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';

// Unmounts anything a test rendered. Without it a component survives into the next test's queries,
// which produces "found multiple elements" failures that read as ambiguous selectors rather than as
// leaked state — a slow and misleading way to discover the real cause.
afterEach(cleanup);

/**
 * Saving the generated specification to a file.
 *
 * The studio is one of three ways to author the same document (CLAUDE.md §32); the other two are the
 * REST API and YAML in Git. Someone who describes a test by clicking should be able to take the
 * result away — to commit it, to review it in a pull request, or to hand it to a colleague who works
 * in YAML — without retyping it out of a read-only preview.
 *
 * What is downloaded is the same text the studio would POST, byte for byte. It is not a rendering of
 * the draft produced for this button: a second emitter would be a second set of bugs, and a file that
 * differs from what was saved is worse than no file at all, because the difference is invisible.
 */

/**
 * The file name a downloaded specification gets.
 *
 * Named after the test so a directory of these is navigable, and suffixed `.yaml` rather than `.yml`
 * to match `specification/examples/`. The fallback matters: the Validate step is reachable before the
 * Review step asks for a name, so a nameless draft must still produce a usable file rather than one
 * called `.yaml` that some systems treat as hidden.
 */
export function specificationFileName(testName: string): string {
  const trimmed = testName.trim();
  return `${trimmed === '' ? 'performance-test' : trimmed}.yaml`;
}

/**
 * Hands the specification to the browser as a file.
 *
 * `window.document` rather than a bare `document`, because the studio's step components bind a local
 * `document` to the generated specification — the exact place this function is called from. The
 * global would be shadowed and the call would fail on a string.
 *
 * The object URL is revoked once the click has been dispatched; without that, the blob is held for
 * the lifetime of the page and every download leaks one.
 */
export function downloadSpecification(yaml: string, testName: string): void {
  const blob = new Blob([yaml], { type: 'application/yaml;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = window.document.createElement('a');
  link.href = url;
  link.download = specificationFileName(testName);
  window.document.body.appendChild(link);
  link.click();
  window.document.body.removeChild(link);
  URL.revokeObjectURL(url);
}

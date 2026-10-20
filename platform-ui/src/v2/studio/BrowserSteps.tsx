import { useMemo, useState } from 'react';
import type { V2Client } from '../api';
import { describeError } from '../api';
import type { StudioDraft } from './draft';
import { toSpecification } from './specification';

interface Props { draft: StudioDraft; onChange: (draft: StudioDraft) => void }
export const EXAMPLE_JOURNEY = `import { test, expect } from '@playwright/test';

test('Open the application', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('body')).toBeVisible();
  // Add the actions and assertions that describe your real user journey.
});
`;

export function BrowserSourceStep({ client, draft, onChange }: Props & { client: V2Client }) {
  const b = draft.browser;
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const set = (patch: Partial<typeof b>) => onChange({ ...draft, browser: { ...b, ...patch }, validator: null });
  async function store() {
    setBusy(true); setError(null);
    try {
      const source = await client.uploadBrowserSource(draft.team, b.script);
      set({ ...source, uploadedForTeam: draft.team });
    } catch (err) { setError(describeError(err)); }
    finally { setBusy(false); }
  }
  return <>
    <p className="studio-summary">A browser journey is a short script that opens pages, uses the application and checks that it worked. The platform measures its duration in real Chromium browsers and adds measurement hooks for page timings.</p>
    <fieldset className="studio-field">
      <legend>Where is your journey?</legend>
      <label><input type="radio" name="browser-source" checked={b.mode === 'upload'} onChange={() => set({ mode: 'upload', reviewed: false })} /> Paste or upload a script</label>
      <label><input type="radio" name="browser-source" checked={b.mode === 'git'} onChange={() => set({ mode: 'git', reviewed: false })} /> A reviewed test in a repository</label>
    </fieldset>
    {b.mode === 'upload' ? <>
      <p className="studio-hint">Use a self-contained .spec.ts file importing @playwright/test. Use page.goto('/') so the registered target chooses the host. Keep passwords and tokens out of the script.</p>
      <label htmlFor="browser-file">Upload a Playwright script</label>
      <input id="browser-file" type="file" accept=".ts,.js,.mts" onChange={async e => {
        const file = e.target.files?.[0];
        if (!file) return;
        if (file.size > 1024 * 1024) { setError('Choose a script smaller than 1 MiB.'); return; }
        set({ script: await file.text(), reviewed: false, uri: '', digest: '' });
      }} />
      <div className="studio-field">
        <label htmlFor="browser-script">Reviewed journey</label>
        <textarea id="browser-script" rows={16} value={b.script} spellCheck={false} onChange={e => set({ script: e.target.value, reviewed: false, uri: '', digest: '' })} />
      </div>
      <button className="btn btn-quiet" type="button" onClick={() => set({ script: EXAMPLE_JOURNEY, uri: '', digest: '', reviewed: false })}>Use a page-opening example</button>
      <details className="advanced"><summary>I do not have a script yet</summary>
        <p>Ask your development team for an existing Playwright journey, or record one with Playwright codegen on your own machine. Review the generated actions and assertions, replace the recorded host with a relative path, then paste the script here. The platform’s API traffic recorder produces API tests; it does not create browser scripts.</p>
        <pre>npx playwright codegen https://your-staging-application.example</pre>
      </details>
    </> : <div className="studio-grid">
      <div className="studio-field"><label htmlFor="browser-repo">Repository HTTPS URL</label><input id="browser-repo" value={b.repository} onChange={e => set({ repository: e.target.value, reviewed: false })} /><p className="studio-hint">Your operator must allowlist the repository. Anonymous HTTPS repositories only; credentials in clone URLs are refused.</p></div>
      <div className="studio-field"><label htmlFor="browser-ref">Complete commit SHA</label><input id="browser-ref" value={b.ref} onChange={e => set({ ref: e.target.value, reviewed: false })} /><p className="studio-hint">40 characters; a branch such as main can change between runs.</p></div>
      <div className="studio-field"><label htmlFor="browser-path">Test path inside the repository</label><input id="browser-path" value={b.path} onChange={e => set({ path: e.target.value, reviewed: false })} /><p className="studio-hint">For example tests/checkout.spec.ts. Include package-lock.json; the Playwright version must match the runner image. Repository tests require Kubernetes execution.</p></div>
    </div>}
    <label><input type="checkbox" checked={b.reviewed} onChange={e => set({ reviewed: e.target.checked })} /> I reviewed this journey, removed embedded credentials, and may test the selected application.</label>
    {b.mode === 'upload' && <div className="studio-button-row"><button className="btn" type="button" disabled={busy || !b.reviewed || !b.script.trim()} onClick={store}>{busy ? 'Storing…' : 'Store reviewed script'}</button>
      {b.uri && b.uploadedForTeam === draft.team && <p role="status">Script stored immutably. Editing it creates a new fingerprint.</p>}</div>}
    {error && <p className="banner banner-bad" role="alert">{error}</p>}
  </>;
}

export function BrowserCohortStep({ draft, onChange }: Props) {
  const b = draft.browser;
  const set = (patch: Partial<typeof b>) => onChange({ ...draft, browser: { ...b, ...patch }, validator: null });
  return <>
    <p className="studio-summary">Start with one browser and one journey. Once it works, increase the cohort. Browser sessions measure the experience; API load tests generate large background traffic.</p>
    <div className="studio-grid">
      <div className="studio-field"><label htmlFor="browser-users">Browsers at once</label><input id="browser-users" type="number" min={1} max={20} value={b.users} onChange={e => set({ users: Number(e.target.value) })} /><p className="studio-hint">Each browser consumes real CPU and memory. The server enforces a separate browser budget.</p></div>
      <div className="studio-field"><label htmlFor="browser-iterations">Journeys per browser</label><input id="browser-iterations" type="number" min={1} max={1000} value={b.iterations} onChange={e => set({ iterations: Number(e.target.value) })} /></div>
      <div className="studio-field"><label htmlFor="browser-device">Screen to emulate</label><select id="browser-device" value={b.device} onChange={e => set({ device: e.target.value as typeof b.device })}><option value="desktop">Desktop — 1440 × 900</option><option value="mobile">Mobile — 390 × 844, touch</option></select></div>
      <div className="studio-field"><label htmlFor="browser-limit">Stop after at most (minutes)</label><input id="browser-limit" type="number" min={1} max={60} value={b.maxMinutes} onChange={e => set({ maxMinutes: Number(e.target.value) })} /><p className="studio-hint">A safety limit, not a promise that the test will run this long. It finishes after the configured journeys.</p></div>
    </div>
    <p role="status">{b.users * b.iterations} executions of each test in the script, using up to {b.users} browsers at once. Failed journeys are not retried.</p>
    <p className="studio-hint">Network and CPU are unthrottled. These are controlled lab timings, not a substitute for real-user monitoring. The harness collects LCP, layout shift and time to first byte on the final page when supported. It does not infer INP or Lighthouse scores from journey timings.</p>
  </>;
}

export function BrowserCheckStep({ client, draft, onChange }: Props & { client: V2Client }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const document = useMemo(() => { try { return toSpecification(draft); } catch { return null; } }, [draft]);
  const result = draft.validator?.forDocument === document ? draft.validator.result : null;
  async function check() {
    if (!document) return;
    setBusy(true); setError(null);
    try {
      const response = await client.validate(document);
      onChange({ ...draft, validator: { forDocument: document, result: { ...response, target: null, journeys: [] } } });
    } catch (err) { setError(describeError(err)); }
    finally { setBusy(false); }
  }
  return <>
    <p className="studio-summary">This checks the configuration, source reference and server policy. It does not run the script or prove the application is fast.</p>
    <p>Save the test, then choose <strong>Run this</strong> in the test list for a real browser check. The default is one browser and one iteration. Read the measured report before increasing the cohort.</p>
    <button className="btn" type="button" disabled={busy || !document} onClick={check}>{busy ? 'Checking…' : 'Check browser configuration'}</button>
    {result && <div role="status"><p>{result.valid ? 'Configuration checked. Ready to save.' : 'Configuration needs attention.'}</p><ul className="findings">{result.findings.map((f, i) => <li key={i}>{f.message}{f.remediation && ` ${f.remediation}`}</li>)}</ul></div>}
    {error && <p role="alert" className="banner banner-bad">{error}</p>}
  </>;
}

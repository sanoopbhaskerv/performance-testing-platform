# Test a real browser journey

A browser test answers “How long does this journey take for a person, and does it work?”
An API load test answers “Can the backend sustain this traffic?” Browser count and API virtual
users are different budgets. Start with one browser; use API traffic for large background loads.

## First successful test

1. Run `./scripts/start-platform.sh --build`. Open the console on `http://localhost:5173`.
2. Enter `local-dev-key-not-a-secret` in **Team access token**. This fixed key works only in local development.
3. Choose **Performance tests → New test → Test web performance**.
4. On **About**, choose team `local-dev`, application `student-app`, environment `performance`,
   and a new name such as `first-browser-check`. The registered application resolves to the student
   web UI on port 5174. Your own application needs a registered target first.
5. Choose **Use a page-opening example**. Review it and tick the review confirmation, then
   **Store reviewed script**. An uploaded source is stored immutably under your team; the
   fingerprint, rather than a mutable filename, identifies what later runs execute.
6. Keep **1 browser, 1 journey per browser**, desktop and the five-minute emergency time limit.
   The time limit is a ceiling; a small test normally finishes much sooner.
7. Start with an illustrative journey-duration objective of **8,000 ms at p95** and **0% failed
   journeys**. These are starter requirements, not universal performance standards. With one
   sample, p95 is that sample: use repeated representative journeys before making decisions.
8. **Check browser configuration** validates the configuration and policy. It does not run a
   browser, validate selectors, or prove performance. Save the test, then press **Run this** beside
   its saved version. This is when real traffic starts.
9. Watch the run, then read its report. Confirm `synthetic=false`, actual samples, and objective
   verdicts. Download the raw CSV, sanitized summary/HTML report and, on failures, the restricted
   trace/screenshot archive. A failed objective is a measured finding; a provisioning/setup failure
   means the requested experiment did not complete.

The local launcher installs the pinned Playwright runtime and Chromium. It uses local JMeter
when installed. Browser execution is routed independently; see the launcher’s execution banner.
If the deployment says `fake`, it generates no load and must not be used as performance evidence.
Local H2 definitions disappear at restart. Use PostgreSQL and object storage for durable hosting.

## Bring a real journey

Paste or upload a self-contained `.spec.ts` file importing `@playwright/test`:

```typescript
import { test, expect } from '@playwright/test';

test('Browse the catalogue', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading')).toBeVisible();
  // Add the user actions and business checks that make this journey meaningful.
});
```

Use relative URLs so the registered target selects the host. Do not put credentials, cookies,
production data, or authorization headers in source. Secrets belong in approved runtime references.
Uploaded scripts must be self-contained; a repository source is the choice for helper modules and
other dependencies. The page-opening example proves connectivity and rendering, not your business
workflow. The platform does not invent business checks for you.

If you have no existing journey, Playwright codegen can record actions on your machine:
`npx playwright codegen https://your-staging-application.example`. Review the script, replace
recorded absolute target URLs, remove credentials and add meaningful assertions before uploading.
The platform’s interactive traffic recorder produces API journeys, not Playwright action scripts.

Repository mode requires an operator-allowlisted credential-free HTTPS repository, a complete
40-character commit SHA, a test path inside the checkout, and `package-lock.json`. The runner
fetches exactly that commit, checks real paths against traversal/symlinks, and installs locked
dependencies with lifecycle scripts disabled. The repository’s Playwright version must match the
runner image. Private repository credentials and named Playwright project configs are not supported.
Repository mode executes only in isolated Kubernetes Jobs.

## What is measured

- Whole-journey and top-level step durations; failed assertions are unsuccessful samples.
- LCP and CLS from an observer injected before page navigation; TTFB from Navigation Timing.
  Metrics describe the final page still open at the end of the journey. Unsupported/missing
  measurements are omitted, never replaced with invented zeroes.
- The harness applies desktop/mobile emulation and configured CPU/network profiles. Mobile here
  is Chromium screen/touch emulation, not a physical device or a device-cloud result.
- INP, TBT and Lighthouse scores are not collected by this harness. An objective requiring absent
  measurements is INDETERMINATE. Journey duration is not a substitute for a web vital.

Each test function executes `browsers × iterations` times, scheduled across the configured worker
ceiling. Multiple tests therefore multiply the journey count. Retries are disabled, focused tests
are refused, and the platform does not extend repository configs that could override capacity.
Measurement hooks run around the reviewed script; user actions are not generated or rewritten.

## Troubleshooting

| Symptom | Action |
|---|---|
| Cannot store a script | Select a team first, confirm review, check token scopes, and keep the script below 1 MiB |
| Changed team after upload | Store the script for the newly selected team; source ownership is enforced |
| Source/plan fingerprint mismatch | Stop and restore or re-upload the reviewed source; do not disable verification |
| No tests found | Include a real `test(...)` in the script; a setup-only file cannot produce a successful journey |
| Browser executable missing | Install the pinned Chromium runtime locally, or use a runner image matching the project’s Playwright version |
| Repository refused | Ask the operator to allowlist it; never embed credentials in the clone URL |
| No progress yet | Completed journeys are published periodically; the first slow journey can take time before a sample appears |
| Runner was killed or timed out | Check the time limit, generator resources and restricted logs; do not blindly replay the load |

# Playwright runner

The production browser executor runs one shard in an ephemeral Kubernetes Job. The API never
launches its browser. The local development adapter uses the same harness and reporter.

Build from the **repository root**, because the harness is shared with the Java engine module:

```bash
docker build -f runner-images/playwright-runner/Dockerfile -t perfplatform/playwright-runner:1.62.1-1 .
```

The image pins Playwright 1.62.1 and its matching Chromium distribution. It runs as UID 10001 with
a writable `/workspace`; the Job supplies read-only root filesystem, deadlines, resource limits,
service account, no privilege escalation and zero automatic retries. See
[standalone deployment](../../docs/deployment/standalone.md) for image allowlists, object storage,
credentials and enforced network isolation.

## Input and source verification

The shipped Kubernetes provider supplies `PERF_RUN_ID`, `PERF_TEAM_ID`, `PERF_SHARD_ID`,
`PERF_SHARD_INDEX`, `PERF_SHARD_COUNT`, `PERF_PLAN_DIGEST`, `PERF_WORKLOAD_KEY`,
`PERF_ARTIFACT_BUCKET` and `PERF_ARTIFACT_PREFIX`. Optional storage endpoint, region and path-style
settings support private S3-compatible storage. Credentials come from an operator-mounted Secret
or cloud workload identity, never from a test specification or command argument.

The bootstrap fetches the rendered plan and verifies its compiled-plan digest before running a test.
It accepts either a reviewed, team-owned `artifact://` script with verified content digest, or an
allowlisted anonymous HTTPS repository pinned to a complete commit SHA. Repository paths must stay
inside the real checkout, `package-lock.json` is required, installation uses `npm ci --ignore-scripts`,
and the installed Playwright version must match the image. Private repository authentication and
named repository project configurations remain unsupported. `workspace://` is development-only.

The platform supplies a controlled configuration: Chromium, fixed worker ceiling, no retries,
no focused tests, bounded duration, base URL, screen/touch emulation, network/CPU profile and run
correlation header. Each test function executes `browserUsers × iterationsPerUser` times. Imported
scripts remain executable code and must be reviewed; use a dedicated runner cell and restrictive
egress policy. The platform does not extend repository configuration or generate user actions.

## Artifacts and measurements

Every successful measured shard uploads a `runner-manifest.json`, `results.csv`, sanitized
`playwright-report.json`, sanitized `playwright-report.html` and `runner.log` to its private,
team/run/shard prefix. Failure evidence is packaged as `browser-evidence.tar.gz`; traces and
screenshots can contain application data and require the same restricted artifact access.

CSV columns are `metric,operation,value,success,timestamp`. Journey/step duration, LCP and TTFB
use milliseconds. CLS uses milli-score internally (`95` means `0.095`); the UI displays scores.
Page metrics describe the final open page, and missing observations remain unavailable. INP,
TBT and Lighthouse scores are not fabricated. Built-in action labels exclude parameter values;
explicit `test.step` names should also avoid sensitive data.

An assertion failure with recorded journeys is analyzed against browser objectives. Setup errors,
empty scripts, all-skipped tests and timeouts cannot pass as successful material execution.
The manifest binds results to the run, shard and immutable plan digest. Upload failure fails the Job.

Every five seconds, and at completion, `progress.js` reduces available journey samples and publishes
`progress.json`. Web metrics remain separate from latency aggregates. An unavailable progress
snapshot does not invalidate the final result. See [live monitoring](../../docs/operations/live-monitoring.md).

## Verification

```bash
npm ci --prefix runner-images/playwright-runner
npx --prefix runner-images/playwright-runner playwright install chromium
npm --prefix runner-images/playwright-runner test
```

These tests launch real Chromium against a disposable local target and check capacity, correlation,
page metrics, source tampering, assertion failures, empty scripts and skipped tests. The optional
`scripts/verify-browser-kubernetes.py` bootstraps a fresh disposable kind cluster's proof stack with
the shipped production chart, PostgreSQL and MinIO. It requires locally built images already loaded
in `kind-perf-browser-verify`; it never selects a cloud account. Reuse the generated private token
for the UI or CLI, and delete that disposable cluster when finished.

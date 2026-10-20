# Roadmap

## Review fixes — 20 October 2026

All five findings from the current-code review are fixed. Failure intent is persisted in the
existing failure fields with a `FAILURE_REQUESTED` event before stopping runner handles. Active
failures retain capacity/completion timestamps until termination is confirmed; restart resumes
cleanup, including provisioning refusals after another runner started and lost-contact shards.

Operator-owned limits default to 1,000 protocol users across workloads and one hour. Configure
`capacity.maxProtocolUsersPerRun` / `capacity.maxDuration` in Helm or the corresponding
`platform.orchestration.capacity` properties. Authoring, new triggers and provisioning recheck
policy, including compiled engine duration estimates. Existing idempotent retries retain their
original identity; a queued run cannot bypass newly lowered limits.

The Validator disables automatic redirects, checks DNS policy before each hop, permits at most
five same-origin redirects and refuses a different scheme/host/port before connecting to it.
Objective validation binds units to metrics. Supported percentiles are 50/75/90/95/99/100; p100 is
the maximum. Exact p75 is computed, persisted through additive nullable V11 migrations and exposed
as an optional report field. Missing historical p75 and invalid old objective contracts remain
indeterminate when evaluated, never substituted. Historical stored verdicts are not rewritten.

Verification: `mvn -q clean verify` with Docker: **1,392 tests, zero failures/errors, one opt-in
live-recorder skip** (23 added tests). **205 UI tests**, lint and production build pass. JMeter
upload contract test, company Helm lint/template, limit environment assertions, documentation
links/fences and `git diff --check` pass. PostgreSQL covers pending failure persistence and p75
round trips; controlled providers/HTTP servers reproduce the five defects without material load.

Operational limits: unavailable termination confirmation deliberately keeps capacity reserved.
Operators upgrading older installations should inspect already-failed runs for surviving Jobs;
terminal history is not reopened automatically. A fresh run or operator reanalysis is required to
correct historical objective outcomes; there is no public reanalysis endpoint. No dependency
upgrade, external deployment, image publication or merge is included. Update the existing PR #1.
Next recommended increment: operator-owned infrastructure acceptance and five-team calibration.

## Current verified state — 19 October 2026

**Company configuration and request-rate follow-up (supersedes older counts/image pins):**
Standalone SVG architecture, technology placement and sequence diagrams are included and visually
verified in Chrome, with editable Mermaid text and a dependency-free regeneration script. They
remain viewable offline when GitHub rich rendering is unavailable. XML/idempotent regeneration pass.

Runtime branding now sets product/tab name, local/HTTPS logo, light/dark theme and validated brand
colors using public JSON or Helm values, without rebuilding the deploying company's UI. The same
image was verified with baked Performance Studio/dark defaults and mounted Acme/light branding.
The logo and configuration return 200 before login; a run report still returns 401 without a token.
Extra non-secret Spring configuration was mounted and its application name observed in startup logs.
Visual verification covered sign-in and the persisted report; light-theme sign-in fields were corrected.

Helm now supports control-plane cloud annotations, separate registry pull Secrets in both
namespaces, configurable ingress annotations, operator-owned runner namespaces, branding/logo
ConfigMaps and additional application configuration. Legacy sample YAML API keys are no longer
created by default. The AWS SDK STS module reuses the existing SDK version and its projected-token
exchange is tested against a local STS fixture; no cloud account was used.

Documentation now includes the 15-step company installation, cloud mappings, exact configuration
reference, branding, troubleshooting, architecture/technology placement/run-sequence diagrams,
expanded README, corrected current API authentication/status and a repeatable extension workflow.
Start at `docs/README.md`; copy `helm/performance-platform/examples/company-values.yaml`.

Chrome browser demo `cbeeefa0-19fe-4fa2-8f3b-0bbcd9e79b2c` completed four journeys with two browsers,
zero failures and three met objectives (journey p95 1,318 ms). Chrome-authored API scenario
`demo-20-concurrent-100-rps-214629` targets 100 RPS with concurrency ceiling 20, a 10s ramp, 60s
hold and 10s ramp-down. Final run `1ec111c4-b6da-4673-b6cf-38a393d355a0`: 7,000 requests,
zero errors, p95 2 ms, 5,000 requests in a 50s interior steady window = 100 RPS. Maximum reported
thread count was 15, not a claim of twenty constantly active users. Four evidence downloads were
SHA-256 verified. Runner `5.6.3-3` now supplies upload fingerprints/content types; older missing
fingerprints remain unverifiable. Rate-based report/progress/review screens now preserve rates
and ceilings, and throughput objectives say "At least".

Checks: full Maven verify with Docker **1,369 tests, zero failures/errors, one opt-in recorder
skip**; **205 UI tests**, lint and production build; runner upload contract test; full control-plane
and JMeter Docker builds; company Helm lint/template, invalid branding rejection, namespace/mount/
identity/pull-secret assertions and documentation link checks. The Apache CDN mirror argument was
used for a slow archive download; SHA-512 verification remained enabled. Private proof JSON,
credentials, raw results and extra screenshots stay in ignored `.local/browser-proof/`.

Local inspection uses the existing disposable kind cluster/namespaces and localhost:18080.
Control-plane image: `perfplatform/platform-core:company-verify-2`; reviewed runner images remain
pinned. The branded 7,000-request report is left open in Chrome. No external cloud deployment,
image publication or merge was performed. Current work is published through the existing PR #1.
Next: validate the company installation on an operator's infrastructure and calibrate five-team
resources. Human OIDC, native Blob/GCS adapters, private Git credentials, remote federation and
BrowserStack remain extensions, not configuration switches or certified integrations.

**Recorder replay follow-up:** the fresh Linux backend build passed in full. Its slower UI run
exposed duplicate evidence when a recorder poll replayed the same sequence. The recorder now
ignores older/repeated frames, refusals and captures, preserving monotonic cursors and one visible
entry per event. The regression test explicitly waits for repeated polls and checks both evidence
lists. All 193 UI tests, production build and lint pass locally after this correction.

**Fresh-install portability follow-up:** the first GitHub Linux run exposed that the old MinIO
prebuilt image had been withdrawn from both upstream registries; cached local images hid this. The
optional S3 fixture now builds the same 2024 release from official commit
`cefc43e4daa4cbb490ef6726ea374e26a93eb85e`, with a verified archive SHA-256 and pinned Go toolchain.
Its real S3 integration tests pass from that source build (first build about 3m25s locally). The
proof bootstrap creates buckets using a temporary scoped AWS CLI pod, independently smoke-tested.
The CI runner is pinned to Ubuntu 24.04 and current setup actions. Production still uses the
operator's own S3-compatible storage; no storage product upgrade or external image publication was
introduced. See the updated deployment guide for building/loading this optional fixture.

This section supersedes the historical increment notes below. The user's direction is now an open
source, customizable, self-hosted product usable without performance engineering experience. This
increment completes the browser wizard and production Playwright path and reviews the complete
self-service journey. Default global admission is **five** material runs, one per team; browser
capacity remains separate. The user's working preference explicitly authorizes commit/push and
updating open PR #1 on `feature/platform-v2-foundation`; merge/deployment/image publication are not
authorized or performed.

**Delivered:** six browser steps (About/target, reviewed source, browsers, objectives, configuration
check, save/run); immutable team-owned source upload and S3/filesystem ports; pinned Playwright
1.62.1-1 production image sharing the local harness; deterministic import, bounded workers/deadline,
correlation header, page/step evidence, final-page LCP/CLS/TTFB and failure traces; digest-bound
results and uploads, real progress identities, no empty-test successes. A typed primary-sample flag
keeps secondary scores/actions out of headline results. New application target registration now
onboards its application after team authorization. Source Java packages named `target` are now
explicitly included in Git; the old Maven-output ignore pattern had hidden these required files.

**Self-hosting:** production UI/API image, Helm existing Secret support, expiring hashed scoped
service identities, production legacy/dev identity restrictions, PostgreSQL driver auto-detection,
disabled unused session acquisition, restricted runner egress declarations, optional Karpenter,
MIT license, contribution guide, user/configuration/deployment/extension docs and portable
`skills/performance-testing/SKILL.md` plus Python CLI. No provider-specific corporate IdP, cloud
account or secret manager was selected.

**Evidence:** UI-created run `36bf6494-bd51-40ad-b447-f9a30f2a9b13`, four primary journeys, real
Kubernetes provider, complete evidence, three PASS objectives (journey p95 407 ms, error rate 0%,
CLS score 0). Restart during ANALYZING completed that persisted run with unchanged Job UID
`c37b5dd0-6c75-4d1f-8d25-ea07cc73ec3c`. The actual Job ran non-root/read-only root with no retries.
Portable CLI/API proof: success `c2cd7b13-a652-466d-9fc0-298e7194b453`, assertion breach
`35def99c-a4ea-4f5d-93b8-ccd990ae8513`, cancellation `6216106c-88f8-455a-9878-3dbbcf291399`;
seven private digest-verified downloads, idempotent triggers/cancellation, cross-team and expired
credentials denied. These local fixture timings do not establish application or generator capacity.

**Checks:** full Maven verify with Docker: 1,366 tests, zero failures/errors, one opt-in live recorder
skip; UI 193 tests/build/lint pass; six runner checks including real Chromium pass; both Docker
images build; Helm lint and unrestricted-network rejection pass; Python helpers compile, token
helper permissions/hash/no-output/overwrite checks pass; skill validator passes. Exact guides and
review: `docs/reviews/browser-self-service-2026-10-19.md`, `docs/user-guide/`,
`docs/deployment/standalone.md`, `docs/development/extending.md`.

**Local inspection:** disposable kind cluster `perf-browser-verify`, namespaces `perf-browser-proof`
and `perf-browser-proof-runners`, UI port-forward localhost:18080. Private short-lived credentials
and proof JSON/screenshots are in ignored `.local/browser-proof/`; never commit them. Setup:
`scripts/verify-browser-kubernetes.py` on a fresh cluster, verification:
`scripts/check-browser-self-service.py`. The cluster is left for inspection; cleanup is
`kind delete cluster --name perf-browser-verify`. No external cloud infrastructure was deployed and
no container image was published.

**Remaining product work:** operator verification of enforced CNI egress, cloud IAM/encryption,
real five-team resource calibration, human OIDC, private repository credentials, named project
configs, remote runner federation, BrowserStack, wizard hybrid authoring. INP/TBT/Lighthouse are
unavailable rather than inferred. A reviewed browser script is still required; the platform does
not autonomously generate one. The recommended next increment is release hardening on the target
operator's infrastructure and a calibrated five-team acceptance test, followed by human SSO once
the identity provider is selected. Do not revive the historical claim that all product phases are
complete solely because modules or scaffolds compile.

---

## Historical handoff notes (prior to this verification)

What is left, and what it is worth. `HANDOFF.md` says where things stand; this says where they could
go. Nothing here is committed to — it exists so that "not done" is a list somebody chose from rather
than a surprise.

**Read the recommendation in §0 before picking anything up.**

---

## 0. Where to stop

> **Superseded, in part, by [`PLAN.md`](PLAN.md).** The direction is a **Performance Studio** — one
> UI where someone with no performance testing experience configures an API load test or a web
> performance test and reads a report they can act on. `PLAN.md` sequences that as twelve increments
> (S1–S12), of which **S1–S6 are release 1**, and reorders most of what follows in this file.
> **Read `PLAN.md` first.** This file remains the inventory of what exists and what does not;
> `PLAN.md` is the decision about what happens next.
>
> **Built so far: S1 through S11** — the studio, a report a non-expert can read, run progress,
> multi-step journeys with correlation, HAR-imported ordered journeys, the Rules screen, the live
> Validator, target/credential/session foundations, broker-backed `run://session`, mounted session
> delivery and lifecycle cleanup, a kind-level broker smoke and failed-acquisition runbook,
> browser-only Playwright execution behind the SPI and Core Web Vitals objective evaluation, and
>**live monitoring** — runner-published snapshots, a merged durable series, live objectives and
> rule-based observations in the console — and **load models**: requests per second as well as
> concurrent users, with the concurrency ceiling an open model cannot run without, and staged
> concurrency that glides rather than steps — and **per-workload plans**, so one run can carry a
> protocol load and a browser cohort, with an engine this deployment lacks refused at validation.
> `HANDOFF.md` §3n–§3y.
>
> **The recorder records.** The required security review is complete:
> [`docs/architecture/recorder-security-review.md`](docs/architecture/recorder-security-review.md).
> S10a added the domain-level recorder allowlist, address policy and route guard. S10b added the
> recorder pod manifest, per-session egress policy renderer and local-only network-enforcement
> bypass. S10c added durable recorder audit events, capture sanitization and a safe pending console
> entry point. **S10d added the guarded CDP bridge**: a session attaches a real browser behind the
> `RecorderBrowser` port, streams JPEG frames to the console and accepts a closed vocabulary of ten
> interactions, with frozen DNS, per-request route guarding and audit-by-origin. **S10e turned a
> recording into a draft test** — the captures are rendered as a HAR and read by the ordinary HAR
> importer, so a recording reaches the studio with journeys, frequency-derived weights and
> evidence-backed correlation suggestions, none of it written twice. **S10h made the Validator prove
> them**: run against the recording, each accepted correlation gets a verdict, and a rule extracted
> but sent by nothing fails validation instead of passing on a still-valid captured literal.
> **S10i finished S10**: a git-pinned Playwright source now reaches the runner, its coordinates are
> part of the plan digest, and a repository this deployment has not allowlisted is refused — an
> empty allowlist permitting nothing rather than everything. S11 added coordinated web performance
> under load. **S10j made a browser journey actually run** from the console: execution is routed per
> engine rather than per deployment, a local Playwright adapter drives a real browser cohort from a
> `workspace://` script, and `CanonicalJson`'s missing field separators — which made **every plan
> this platform ever rendered unparseable** — are fixed. **The container runner is still a
> placeholder, so browser workloads do not yet execute in production.**

**The foundation is complete.** CLAUDE.md §32's fifteen conditions are met, with one partial
(team authorization is enforced, but against a development identity provider — corporate OIDC is
Increment 4 and needs a decision this project has not been given).

**The loose thread is closed.** Increment 2h made a dataset arrive: uploaded per team, stored
immutably per version, verified against the columns a test declares, sliced per shard, and delivered
(`HANDOFF.md` §3m). A consumer can now onboard an API from a cURL command, an OpenAPI document, a
HAR or a Postman collection, give it real data, run it and read a report — without writing JMX.

~~**Recommendation: this is a good place to stop.**~~ That was the recommendation before a direction
was chosen. It has been: `PLAN.md`, release 1 (S1–S6), and that release plus the S6a readiness
slice, S7's live monitoring, S8's load models, S9's per-workload plans, the S10a–S10e recorder
through to a live guarded browser, a draft test, a Validator that proves its correlations and
git-pinned Playwright import, and S11's hybrid coordination are built. Everything below is still an
accurate inventory of what exists and what
does not.

**V1's removal is authorized** and deferred until after the studio build phases. S5 removed the
credentials/session blocker, but the API half still needs a focused removal audit because
development identity is local-only and V1 endpoints must stay operational until an explicit removal
increment.

**One thing to carry forward, wherever you go next:** 2h changed the runner image's entrypoint, and
that change has not been run on a cluster. The local path is verified with real load. Re-run
`docs/operations/local-kubernetes-run.md` before trusting the Kubernetes path with a dataset.

---

## 1. Increment 2h — dataset storage and delivery — **done**

Kept for the record. All four steps landed:

1. **The port.** A sibling rather than a widening: `DatasetStore` for content and `DatasetCatalog`
   for the record, keyed `teams/{teamId}/datasets/{name}/{version}/`. `ArtifactStore` stayed
   run-scoped, which is what makes a run's evidence one retrievable, authorized, reclaimable unit.
2. **Upload.** `POST /api/v2/datasets`, team-scoped, size-capped, header required, column names
   scanned — a column named as a credential is refused and nothing is stored.
3. **Slice at preparation.** `prepareShard` partitions and stores each shard's slice as a `DATASET`
   artifact, after comparing the file's header against the declared columns and failing the run on a
   mismatch.
4. **Deliver.** The local provider writes partitions beside the JMX; the Kubernetes provider passes
   `PERF_DATASETS` as `fileName=objectKey` pairs and the runner fetches them.

## 2. Increment 2, remaining

Reordered by `PLAN.md`; kept here as the inventory. Correlation is done; the rest map onto S-numbers.

| Item | Why it matters | Size |
|---|---|---|
| ~~**Dry-run preflight**~~ | **Done in S4** for protocol workloads. One iteration of each API journey runs against the resolved target before saving, so a wrong path or broken correlation fails in seconds instead of after a load run. Browser dry-run validation remains later work after the S6 browser execution foundation. | — |
| **Baseline comparisons** | A single run's p95 means little; the question is always "worse than last week?". Needs a stored baseline per definition and a comparison in the report. **`PLAN.md` S12**; S2 shaped the report to take one. | Medium |
| ~~**Jenkins shared library**~~ | **Dropped.** CI triggering is not this organisation's practice; the V2 API remains available for whoever wants it. | — |
| ~~**Manual correlation and extraction**~~ | **Done in `PLAN.md` S3** — scenarios carry ordered steps, values are extracted and carried forward, and a failed extraction fails the sample rather than reporting good latency. `HANDOFF.md` §3p. | — |

---

## 3. Increment 3 — Playwright and hybrid runs

**Absorbed into [`PLAN.md`](PLAN.md).** Increment 3's content is now **S6** (the browser engine, Core
Web Vitals and Lighthouse diagnostics — in release 1), **S10** (the server-side recorder and
git-pinned Playwright import), **S11** (web performance under load) and **S12** (scale and
BrowserStack). `PLAN.md` also folds in the remaining §2 items above — correlation lands in S3,
dry-run preflight becomes S4's Validator, baseline comparison becomes S12. The Jenkins shared
library is dropped: CI triggering is not current practice.

The description below stands as background on the chapter's contract.

A clean, large chapter. CLAUDE.md §21 has the full contract. In short: import an existing Playwright
test by repository, immutable commit SHA and path — never a moving branch; run it in a pinned,
restricted runner image; normalise browser results into the same model as protocol results; then
coordinate a small browser cohort during JMeter steady state. Default browser concurrency 5, policy
maximum 20 — **not** 1,000, and never equated with protocol virtual users.

The engine SPI is now exercised by JMeter and Playwright for browser-only runs, and S11 coordinates
delayed browser cohorts under active protocol load. **S10j closed the gap between compiling a browser
plan and executing one** — until then nothing could run a Playwright workload anywhere, because the
container entrypoint was a placeholder and the control plane bound a single engine-specific provider
for the whole deployment. Execution now routes per engine, and a local adapter runs real browsers for
development. **Porting that adapter into the pinned runner image is the outstanding piece of this
chapter**: a production deployment still cannot execute a browser workload.

---

## 4. Increment 4 — enterprise integration

Every item is a **pause condition** (CLAUDE.md §31): corporate OIDC, a secret manager, a production
S3 bucket, Dynatrace tokens. None can be chosen by whoever is writing the code.

What exists today: the authorization domain, the Spring Security seams, a development identity
provider that maps an API key to its owning team and **refuses to start under a production profile**.
What does not: any real identity provider, and the `SecretResolver` implementations.

Also here: defect **D7** — unauthenticated calls return 403 rather than 401, inherited from V1's
shared filter chain and pinned by `RunLifecycleEndToEndTest.v2RequiresAuthentication` so it stays
visible.

---

## 5. Increment 5 — assisted authoring

Recorder, AI-assisted draft generation, diff and review, historical recommendations. CLAUDE.md §28
is explicit that this comes **only after governance**, and that no unreviewed AI-generated material
test may run. The importers already establish the shape: produce a draft plus findings, and never let
it become executable without a person acting.

---

## 6. Smaller open items

Each is real, none blocks anything.

- **Sharding is untested.** Everything so far is one shard. The data half is now fully solved —
  partitioning, storage and per-shard delivery all exist and are tested — so what remains before the
  count rises is calibration (`docs/operations/runner-calibration.md`).
- **The runner image's dataset fetch has not run on a cluster.** Added by 2h and verified only
  through the local provider. Re-run `docs/operations/local-kubernetes-run.md`.
- **Neither runner image's progress publisher has run on a cluster.** S7 added a Python reducer and
  a rolling publish loop to the JMeter image and a Node equivalent to the Playwright one, both
  verified only through the local provider's Java implementation of the same document.
  `docs/operations/local-kubernetes-run.md` §7 is the procedure, including the two-shard
  generator-kill check that is the only real test of `RUNNER_SILENT`.
- **`run_progress` has no retention.** Rows accumulate at ~360 per hour per active run and nothing
  prunes them. `ProgressCatalog.clear(runId)` exists and nothing calls it on a schedule. The series
  is derived — losing it costs a chart, never a verdict — so a retention job is safe to add.
- **The Playwright runner image is scaffolded, not cluster-proven.** S6 added the pinned
  Playwright/Lighthouse image and output contract, but the material script wrapper still needs a real
  kind/EKS run before production trust.
- **No console UI for datasets.** They are API-only; the studio's Sources step is where an upload
  belongs.
- ~~**Journeys cannot be edited by clicking.**~~ **Done in S3** — the studio has a Rules screen that
  proposes correlations with evidence, and a person accepts or rejects each one before it affects the
  specification. Verification and think time are editable per step. `HANDOFF.md` §3r.
- ~~**The HAR importer still emits sibling scenarios.**~~ **Done in S3** — a capture becomes one
  journey per page with ordered steps, and the `notOrdered` warning was inverted into
  `correlationNeeded`. `HANDOFF.md` §3q.
- **The studio still asks for the team name.** Nothing reports the caller's own team:
  `TeamDirectory` maps name to id and not back, and no endpoint exposes it. S5 closed credential and
  session handling; this belongs with the later identity/V1-removal audit.
- ~~**Production `run://session` needed a provider and delivery contract.**~~ **Done in S5**:
  `ExternalSessionBrokerSessionAcquisitionProvider`, mounted `SecretResolver`, per-shard
  `sessions.csv`, Helm RBAC and docs. `HANDOFF.md` §3t.
- ~~**A run had one plan and one engine.**~~ **Done in S9** — one plan per workload, shards scoped
  by workload, analysis merging sample sets across engines, and a new engine-capability validation
  layer that refuses a workload naming an engine this deployment does not have. **S11 added**
  `startWhen` coordination and trigger-time browser-session budget enforcement. `HANDOFF.md` §3y
  and §3ad.
- ~~**A run in flight showed a state and nothing else.**~~ **Done in S7** — throughput, latency,
  errors, per-journey breakdown, each generator's own last snapshot, live objectives and rule-based
  observations, at 11–14 s freshness. `HANDOFF.md` §3w, `docs/operations/live-monitoring.md`.
- ~~**Production broker path needed a kind-level readiness smoke and acquisition runbook.**~~
  **Done in S6a**: `docs/operations/kind-session-broker-smoke.md` adds the stub broker Service,
  mounted broker-client Secret and runner session Secret creation/cleanup checks; the failed
  acquisition runbook lives in `docs/operations/run-session-acquisition.md`. `HANDOFF.md` §3u.
- **The credential-name list is header-shaped.** `x-api-key` is refused as a query parameter but
  `api_key`, `access_token` and `token` are not, so `?api_key=short-value` passes. A query string
  reaches every access log between the runner and the target. Uncovered during S3 and deliberately
  not widened there: it changes what every existing document validates as.
- ~~**`ConcurrencyThreadGroup` is in the runner image but unused.**~~ **Done in S8** — both models
  now render through the Custom Thread Groups plugin: the Ultimate Thread Group for staged
  concurrency, so it glides rather than steps, and the Free-Form Arrivals Thread Group for the open
  model. The control plane refuses to start without the plugin, because JMeter would otherwise skip
  the thread group and report a successful run that generated no load. `HANDOFF.md` §3x.
- ~~**Throughput objectives could not be written.**~~ **Done in S8** — `Threshold` had no per-second
  unit, so `380/s` was refused and the studio's own throughput objective produced an invalid
  document.
- **Not run on EKS.** IRSA is unexercised; the kind run used a credentials Secret.
- ~~**`platform-ui` has no test runner.**~~ **Done in S1**: vitest, jsdom and testing-library.
  **158 UI tests** now, covering `scenarioYaml.ts`, the design tokens, the studio's specification
  emitter and step rules, the report narrative, run progress and the live view's unscaling layer.
- **`/actuator/health` returns 503 locally** (defect D6): the mail health indicator cannot reach
  SMTP. Readiness should distinguish essential dependencies from optional enrichment (CLAUDE.md §26).
- **UI lint reports 3 errors, 3 warnings**, all pre-existing in V1's `App.tsx`. Deliberately not
  fixed as a side effect of other work.
- **`platform-ui/src/App.css` is imported by nothing.** Rules added there silently do nothing.

---

## 7. Things deliberately not on this list

- **k6.** Not approved. CLAUDE.md §3.
- **Autonomous browser-script generation.** MVP imports an existing Playwright test; generation is
  §5 material and needs governance first.
- **Heap and thread dump collection in the run path.** CLAUDE.md §22 puts diagnostics behind
  separate, explicitly authorised providers. V1 coupled them to the run lifecycle; that coupling is
  not coming back.
- **Microservice decomposition.** The architecture asks for a modular monolith and one deployable
  control plane. §4.

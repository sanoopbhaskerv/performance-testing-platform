# Roadmap

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
> **Built so far: S1 through S9** — the studio, a report a non-expert can read, run progress,
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
> **Next: S10**, the recorder — which needs a security review first (`PLAN.md` §12 R2).

**The foundation is complete.** CLAUDE.md §32's fifteen conditions are met, with one partial
(team authorization is enforced, but against a development identity provider — corporate OIDC is
Increment 4 and needs a decision this project has not been given).

**The loose thread is closed.** Increment 2h made a dataset arrive: uploaded per team, stored
immutably per version, verified against the columns a test declares, sliced per shard, and delivered
(`HANDOFF.md` §3m). A consumer can now onboard an API from a cURL command, an OpenAPI document, a
HAR or a Postman collection, give it real data, run it and read a report — without writing JMX.

~~**Recommendation: this is a good place to stop.**~~ That was the recommendation before a direction
was chosen. It has been: `PLAN.md`, release 1 (S1–S6), and that release plus the S6a readiness
slice, S7's live monitoring, S8's load models and S9's per-workload plans are built. Everything below is still an accurate inventory of what exists and what
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

The engine SPI is now exercised by JMeter and Playwright for browser-only runs. Coordinated hybrid
execution still waits for the S9/S11 multi-plan work.

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
  layer that refuses a workload naming an engine this deployment does not have. Coordination of a
  hybrid — `startWhen`, and the browser-session budget deferred from S9 — is S11. `HANDOFF.md` §3y.
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

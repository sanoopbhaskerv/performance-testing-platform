# Handoff — live working state

## Current verified state — 19 October 2026

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

**Purpose:** If the current agent session ends, another agent picks up from here.
**Read first:** `NEXT-SESSION.md` for the immediate task and how to run it, then this file for state,
then `ROADMAP.md` for what is left and **where to stop**.
**Reference:** `CLAUDE.md`, then `docs/architecture/performance-testing-platform-target-architecture.md`.
**Last updated:** S10j — a browser journey now **executes** from the console, through a local
Playwright adapter and an engine-routing provider, and `CanonicalJson`'s missing field separators are
fixed (§3am). Before this, no browser workload could run anywhere: the container runner was a
placeholder and every plan the platform rendered was malformed JSON. **S10 is complete.** Increments
0–2h and S1–S11 are complete.
durable audit events and capture sanitization; the actual CDP bridge/browser stream remains a later
S10 slice.

**The foundation is complete** (CLAUDE.md §32), with one partial: team authorization is enforced
against a *development* identity provider until Increment 4. **The dataset loose thread is closed** —
a dataset is now uploaded, stored per team and per version, verified against the columns a test
declares, sliced per shard and delivered (§3m).

**The direction is [`PLAN.md`](PLAN.md) — the Performance Studio.** One UI usable by someone with
**no performance testing experience**, covering **API load testing** and **web performance testing**.
Twelve increments, S1–S12, each shipping a backend capability *and* its studio screen; **release 1 is
S1–S6**. **`PLAN.md` supersedes `ROADMAP.md` §§2–5 as the statement of what happens next**;
`ROADMAP.md` remains the inventory of what exists.

**S1 through S6 are built** (§3n–§3v). A test can be described by clicking, its report opens with
the answer in a sentence, a running test shows where it is in its plan, a journey can sign in, carry
a session forward, review correlation rules before they are applied, validate one live protocol
iteration before save, and production session acquisition now has secure `run://session` delivery,
lifecycle cleanup, a broker provider seam, run-facing operator docs and a kind-level broker smoke
procedure. Browser-only Playwright workloads now compile through the engine SPI, carry immutable
script digests and browser profiles, and evaluate Core Web Vitals in the same objective model as
protocol results.

Scope decisions taken with the user, recorded so they are not relitigated:

- **vitest is approved** (§31 pause cleared) and was added in S1. **158 UI tests** where there were
  none.
- **V1's removal is authorized** — the §31 pause is cleared — and the user has asked for it **after**
  the studio build phases. S5 removed the credentials/session blocker, but API removal still needs a
  focused audit of identity, target resolution and team sync (`PLAN.md` §0, "On V1").
- **The recorder is deferred to S10.** The required security review is complete in
  [`docs/architecture/recorder-security-review.md`](docs/architecture/recorder-security-review.md).
  Release 1 ships guided capture wizards instead: DevTools HAR for protocol journeys,
  `npx playwright codegen` for browser journeys. **A HAR cannot produce a Playwright script** — it
  records traffic, not user actions — so the two halves need separate sources.
- **Playwright import is in release 1**, as an uploaded or pasted `.spec.ts` stored immutably by
  content digest. Not a git clone: no credentials, no §31 pause. Git-pinned import is S10.
- ~~**Live monitoring is deferred to S7**~~ **Built in S7** (§3w). Runners publish rolling snapshots
  to object storage, the reconciler merges them, and the console shows throughput, latency, errors,
  per-journey breakdown, each generator's own last word, live objectives and rule-based observations.
- **Web performance is browser-only in release 1**, which needs no multi-plan refactor. Under-load
  web performance (the hybrid) is S11.

`NEXT-SESSION.md` §2 has the immediate task after this commit: **the studio cannot author a browser
test at all** (§3af), which now blocks two shipped increments from having a screen — size that next,
or continue to S12 scale hardening.

```bash
./scripts/start-platform.sh     # demo service + control plane + console, one command
```

---

## 1. Where things stand

| Increment | Status | Evidence |
|---|---|---|
| 0 — baseline protection | **Complete** `4685388` | 53 characterization tests; `docs/migration/baseline-report.md` |
| 1A — modular skeleton | **Complete** `19f3c38` | 8 Maven modules |
| 1B — canonical specification | **Complete** `13fb724` | see §4 |
| 1B follow-up — schema conformance | **Complete** `9826a92` | `SchemaConformanceTest` |
| 1C — durable run orchestration | **Complete** `3290e75` | see §3 |
| 1D — JMeter engine SPI and local provider | **Complete** `165232a`, `9640590` | see §3a |
| Synthetic marking and reporting | **Complete** `7438f29` | see §3b |
| Manual requests + demo-service path | **Complete** `e57b38a` | see §3c |
| 1E — Kubernetes provider and object storage | **Complete** `1556f43` | see §3d |
| 1E — runner image, proven on kind | **Complete** `cfdec46`, `7cbe93d` | see §3e, §3f |
| 1F (partial) — V2 UI foundation | **Delivered with 1D**, extended by the report | `platform-ui/src/v2/` |
| 2a/2b — request model + cURL importer | **Complete** `1b61d0e`, `9fcefe1` | see §3g |
| 2c — OpenAPI / Swagger importer | **Complete** | see §3h |
| 2d — HAR importer | **Complete** | see §3i |
| 2e — Postman importer | **Complete** | see §3j |
| 2f — importing from the console | **Complete** | see §3k |
| 2g — dataset binding and partitioning | **Complete** | see §3l |
| 2h — dataset storage and delivery | **Complete** | see §3m |
| **S1 — the studio: shell, sources, refine, load, objectives, review** | **Complete** | see §3n |
| **S2 — the report a novice can read, and run progress** | **Complete** | see §3o |
| **S3 — journeys and correlation (engine half)** | **Complete** | see §3p |
| **S3 — HAR imports ordered journeys** | **Complete** | see §3q |
| **S3 — studio Rules screen** | **Complete** | see §3r |
| **S4 — the Validator** | **Complete** | see §3s |
| **S5 — Targets, credentials and sessions** | **Complete** | see §3t |
| **S6a — broker path production-readiness validation** | **Complete** | see §3u |
| **S6 — the browser engine and Core Web Vitals** | **Complete** | see §3v |
| **S7 — live monitoring** | **Complete** | see §3w |
| **S8 — load profiles: rate as well as users** | **Complete** | see §3x |
| **S9 — one run, many workloads** | **Complete** (browser budget moved to S11) | see §3y |
| **S10 security review** | **Complete** | [`docs/architecture/recorder-security-review.md`](docs/architecture/recorder-security-review.md) |
| **S10a — recorder route-policy foundation** | **Complete** | see §3aa |
| **S10b — recorder pod and egress-rendering foundation** | **Complete** | see §3ab |
| **S10c — recorder audit, capture sanitizer and safe console entry** | **Complete** | see §3ac |
| **S10d — guarded CDP bridge and live browser stream** | **Complete** | see §3ag |
| **S10e — recording becomes a draft test** | **Complete** | see §3ah |
| **S10f — defects found in real use** | **Complete** | see §3ai |
| **S10g — long-polling and scrolling** | **Complete** | see §3aj |
| **S11 — web performance under load** | **Complete** | see §3ad |
| **S12 — release 2 scale hardening** | **Planned** | [`PLAN.md`](PLAN.md) §11 |
| 4 — enterprise integration | **Not started** | `ROADMAP.md` §4 |

**Last full Java verification: 1,331 tests green, 0 failures at S10i** (one skip, the opt-in live
Chrome test, run separately and green — §3ag) — a full
`mvn clean verify` across all 15 modules, Testcontainers included (272 at 1B, 486 at 1C, 551 at 1D,
591 after the demo path, 684 at 2b, 721 at 2c, 760 at 2d, 803 at 2e, 833 at 2g, 898 at 2h, 931 at
S3 engine, 936 after S3 Rules, 941 at S4 Validator, 1,058 at S7, 1,093 at S8, 1,106 at S9, 1,115 at
S10b, 1,148 at S10c/S11, 1,304 at S10g, 1,312 at S10h). UI: **184 tests**.

**This is the first fully green `mvn clean verify` on this branch**, and getting there meant fixing
two pre-existing failures that had been masked (§3w): the Spring context could not start at all
because two beans matched `TargetResolver`, and that failure hid seventeen end-to-end tests which
had been broken since S5 made target registration mandatory. The environment limits earlier sessions
recorded — Mockito self-attach, socket binding — did not recur with Docker running.

**158 UI tests green** — there were none before S1. `npm --prefix platform-ui test`.

**Nothing is pushed.** Pushing, tagging and opening a PR still require explicit authorization
(CLAUDE.md §6).

**PR #1 is open** and covers the four committed increments:
https://github.com/sanoopbhaskerv/performance-testing-platform/pull/1
Further commits on `feature/platform-v2-foundation` land in it automatically.

`v1-poc` tag exists on the remote at the baseline commit `9233d96` (created by the user).

**All five importers CLAUDE.md §23 requires now exist** — cURL, OpenAPI/Swagger, HAR, Postman, and
manual entry (a scenario's own `method`/`path`, delivered in §3c) — **and all four document
importers are reachable from the console** (§3k). Datasets can be **declared, bound and partitioned**
(§3l), and **uploaded, stored, verified and delivered** (§3m).

**Immediate next action:** the remaining recorder runtime bridge and git-pinned Playwright import,
or S12 scale hardening. S10c deliberately added only a safe clickable entry point: it creates an
audited, target-bound session and returns `PENDING_BROWSER_BRIDGE`; it does not expose CDP or a
live browser stream yet.

**Three structural facts the studio work has to deal with**, each found by reading the code:

1. ~~**A journey is one request.**~~ **Fixed in S3** (§3p). Scenarios carry ordered steps, values are
   extracted and carried forward, and a failed extraction fails the sample.
2. ~~**`spec.authentication` is parsed, canonicalized, validated and used by nothing.**~~ **S5
   added runtime credential handling, `SecretResolver`, broker-backed `run://session`, mounted
   session partitions and production cleanup.** Real enterprise identity and non-Kubernetes secret
   managers remain later pause-condition decisions.
3. ~~**A run still has one plan with one engine.**~~ **Fixed in S9.** A run compiles one plan per
   workload, shards are scoped by workload, and analysis merges sample sets across engines. **S11
   added coordinated hybrid start timing and the browser-session budget.**

---

## 2. Environment gotchas (these will bite you)

```bash
./scripts/start-platform.sh                     # run the whole local stack
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home mvn -B -f pom.xml clean verify
```

- **`scripts/start-platform.sh` is the supported way to run locally.** It starts the demo service
  (:8081), the control plane (:8080) and the console (:5173), clears stale ports first, waits for
  each, and picks `local-jmeter` when `jmeter` is on PATH. `--build`, `--fake`, `--stop`, `--help`.
  Logs in `.local/logs/` (git-ignored). It exists because starting these by hand went wrong in three
  different ways, each recorded below.
- **A readiness probe must not insist on a 2xx.** Every useful V2 endpoint answers **403** without an
  API key, and unauthenticated calls return 403 rather than 401 anyway (defect D7). The first version
  of the start script used `curl -f` and declared a perfectly healthy control plane dead.

- **`JAVA_HOME` is not set in the shell profile.** Every `mvn` call must supply it or Maven fails immediately.
- Only **JDK 21** is installed; the project targets **Java 17**. Building on 21 with `--release 17` is correct. **Do not change `java.version`** — CLAUDE.md §31 pause condition.
  - Consequence: **Java 21-only APIs do not compile.** `Math.clamp` was written and had to be reverted. Check the API's `@since` before using anything recent.
- The Bash tool's **working directory persists between calls**. A `cd` into a module (or `platform-ui`) silently breaks later commands. Always `cd` to the repo root first or use absolute paths. This has now bitten four times.
- Single test across the reactor: **`-Dsurefire.failIfNoSpecifiedTests=false`** (not `-DfailIfNoSpecifiedTests=false`).
- `-pl <module>` alone fails unless dependencies are installed in `~/.m2`; list them or run the full reactor.
- **Docker 29 rejects API < 1.44.** Testcontainers' docker-java negotiates lower and then *silently skips* every integration test rather than failing. Fixed permanently via `docker.api.version` → surefire `api.version` in the root pom. **If integration tests ever report `Skipped`, that is a false green — investigate, do not accept it.**
- **Testcontainers occasionally fails a whole module with an SSL/connection error.** Seen once as
  25 errors across `platform-persistence`; passed on immediate retry with no code change. If a
  PostgreSQL module fails wholesale with `An error occurred while setting up the SSL connection`,
  retry before investigating.
- **`Map.copyOf` returns an *unordered* map** whose iteration order is randomized per JVM run. It
  silently broke `NormalizedRunResult`'s documented stable ordering and only failed on a later run.
  Use `Collections.unmodifiableMap(new LinkedHashMap<>(…))` where order is part of the contract.
- **`Tests run: 0` for a class that declares `@Nested` classes was a Surefire *reporting* artefact,
  never lost coverage — and the root pom now pins Surefire 3.5.3 to stop it.** Up to 3.5.2, Surefire
  filed the outer class's own `@Test` methods into the report of the last nested class it emitted,
  then printed `Tests run: 0` for the outer class. The methods always executed: the `<testcase>`
  entries carry the correct outer `classname`, and injecting a deliberate failure turns the build
  red. Mixing top-level and `@Nested` tests is safe. A `Tests run: 0` line is still normal and
  correct for a class where *every* test is nested (18 such classes). To check attribution rather
  than trust the console, `grep 'classname="<fqcn>"' <module>/target/surefire-reports/*.xml`. See §3h.
- **`@Scheduled(fixedDelayString=…)` does not accept `10s`.** It takes milliseconds or ISO-8601. `ReconciliationScheduler` registers its task through `SchedulingConfigurer` using the already-parsed `Duration` instead, so the config file keeps one value in one format.

---

## 3. Increment 1C — delivered

### Domain (`platform-domain`)
`domain/run/` — `RunState` (11 states, `holdsCapacity`/`isTerminal`/`mayHaveRunner`), `RunStateMachine`
(the transition matrix, centralized), `TestRun` (immutable aggregate; transitions return
`RunTransition` = run + event), `RunEvent`, `RunEventType`, `RunEventDetail` (hand-written flat-JSON
codec), `RunFailureCode`, `RunnerShard`, `ShardState`, `InvalidRunTransitionException`.
`domain/admission/` — `CapacityLimits`, `CapacitySnapshot`, `AdmissionPolicy`, `AdmissionDecision`,
`ConcurrencyAdmissionPolicy`.
`domain/plan/` — `ExecutionPlan`, `ExecutionPlanCompiler`. (`BaselineExecutionPlanCompiler` was
deleted in 1E once `EngineDispatchingPlanCompiler` had fully replaced it.)
`domain/execution/` — `ExecutionProvider` port, `ProvisionRequest`, `ExecutionPhase`.
`domain/port/` — `TestRunRepository`, `RunnerShardRepository`, `ExecutionPlanRepository`,
`DefinitionVersionCatalog`, `TeamDirectory`.
`domain/security/` — `CallerContext`, `Scope`.

### Orchestration (`platform-orchestration`)
`TriggerTestRunUseCase`, `CancelTestRunUseCase`, `ReadTestRunUseCase`, `RunReconciler`,
`ReconciliationReport`, `OrchestrationSettings`, `execution/FakeExecutionProvider`.
Spring-free by design — plain classes wired by the composition root.

### Persistence (`platform-persistence`)
Flyway `V3__run_orchestration.sql` (postgresql + h2): `execution_plans`, `test_runs`, `runner_shards`,
`run_events`, `orchestration_locks`. Entities, JPA repositories, mappers, `TestRunStore`,
`RunnerShardStore`, `ExecutionPlanStore`, `TeamDirectoryStore`.

### API (`platform-api`)
`TestRunController` (`POST /api/v2/test-runs`, `GET /{runId}`, `GET /{runId}/events`,
`POST /{runId}/cancel`), DTOs, `security/CallerContextProvider`, extended `V2ProblemHandler`.

### Composition root (`platform-core`)
`v2/OrchestrationConfiguration`, `v2/OrchestrationProperties`, `v2/ReconciliationScheduler`,
`v2/PersistedDefinitionVersionCatalog`, `v2/DevelopmentCallerContextProvider`.

### Test counts (all green, 0 skipped)
domain 192 · engine-spi 9 · results 12 · **persistence 55 (PostgreSQL Testcontainers)** · orchestration 45 · api 86 · engine-jmeter 7 · core 80 = **486**

---

## 3a. Increment 1D — delivered

**A performance test can be authored, triggered, watched and judged entirely from the UI.**
Verified in a browser: create definition → trigger → `Queued → Starting → Running → Analyzing →
Passed` → objective verdicts, timeline and stored evidence.

### The pipeline, end to end
`AuthorTestDefinitionUseCase` → `EngineDispatchingPlanCompiler` → `JmeterPlanCompiler` →
`JmeterWorkloadRenderer` (JMX to the artifact store) → provider executes → `JtlParser` →
`RunResultReducer` → `ObjectiveEvaluator` → verdicts persisted → `COMPLETED` or `FAILED`.

### Four baseline defects fixed, each pinned by a test naming it
- **D1** numeric corruption at ≥1,000 users — `JmxWriter` never puts a number through a formatter.
- **D3** endurance ran the load test's scenarios.
- **D4** scenario weights discarded — weighted `ThroughputController`s; samplers labelled
  `workload/scenario` so both workload- and scenario-scoped objectives resolve.
- **D5** malformed rows deflated the error rate — counted and kept in the denominator.

### Three deviations from CLAUDE.md §14, argued in the code
1. **compile and render are separate.** Collapsing them means storing JMX in the row read every
   reconciliation pass, or recompiling at execution time — which lets two runs of one test execute
   differently.
2. **Engines parse, `platform-results` summarizes.** §14's `normalize()` would let two engines
   compute different p95s for identical latencies.
3. **JMX is generated programmatically, not from Freemarker.** D1 *is* Freemarker's number
   formatting. Templates remain as migration inputs (§5) and V1 still uses them.

### Development-only components, each refused under a production profile
`fake` provider (synthetic results, recorded as synthetic in every run's `runner-manifest.json`),
`local-jmeter` provider (child process of the control plane), `DevelopmentCallerContextProvider`
(V1 API keys), `TeamRegistrySynchronizer` (team YAML → registry), `TeamYamlTargetResolver`
(team YAML → target, checked against `TargetPolicy`).

### Target safety
`TargetPolicy` refuses cloud metadata endpoints, link-local, multicast, wildcard and unresolvable
hosts — checked against the **resolved** address, not the name. A specification cannot name its own
target.

### UI (`platform-ui/src/v2/`)
`api.ts` (typed client — all V2 access goes through it), `PerformanceConsole.tsx`, `console.css`.
A tab in `App.tsx` switches between it and the untouched V1 screens.

### Not done, and owned by later increments
- **No importers**, so a scenario's `operationId` has no real path, method, headers or body. The
  renderer emits `GET /{operationId}` — deliberately obvious rather than plausible. **Increment 2.**
- **Stages are stepwise**, not a smooth concurrency profile: vanilla JMeter cannot express one, and
  `ConcurrencyThreadGroup` is a runner-image decision. **Increment 1E.**
- **Artifacts are filesystem-local**, so multi-replica does not work. **Increment 1E.**
- **`BaselineExecutionPlanCompiler` is dead code**, superseded by `EngineDispatchingPlanCompiler`.
- No tests for the definition endpoints, the team synchronizer or the local JMeter provider.

---

## 3b. Synthetic marking and reporting — delivered

Two things that belong together: what a run measured, and whether the measurement means anything.

### A synthetic run is now unmistakable, and can never read as a pass

`ExecutionProvider.generatesRealLoad()` — a method every provider must answer, not a comparison on
its identifier, because a name check silently stops working the moment a provider is added.
`FakeExecutionProvider` returns false permanently; `LocalJmeterExecutionProvider` returns true.

The flag reaches the run at **provisioning**, before any measurement exists to be read without it:
`test_runs.synthetic` (Flyway `V5`), `TestRun.markedSynthetic()`, and `TestRunEntity.markSynthetic()`
— which offers no way to clear it, so a mapper copying `false` over a stored `true` is impossible.

Consequences, all of them deliberate:
- Every objective becomes `INDETERMINATE` with "No load was generated, so nothing could be
  measured." (`RunAnalyzer.Analysis.asIndeterminate`, applied in `RunWorkloadService` rather than in
  the analyzer, which stays a pure function of the samples).
- The run reaches `FAILED` with **`RunFailureCode.NO_LOAD_GENERATED`** — checked before anything else
  about the result, and checked *even though* the objectives are already indeterminate, because a run
  with no objectives would otherwise reach `COMPLETED` and read to a consumer as "Passed".
- `GET /api/v2/platform/execution` reports the active provider; the console shows it permanently in
  its header.
- A banner on the run, on the report, and a pill in the run list.

The local demo therefore ends in **Failed**, not **Passed**. That is the correct outcome for a run
that measured nothing. `RunAnalysisTest` covers the `PASS`, `BREACH` and `INDETERMINATE` paths, and
`OrchestrationFixtures.declaringRealLoad(...)` — a test-only decorator, deliberately not a flag on
the fake provider — keeps the reconciler's `COMPLETED` path covered.

### Measurements are persisted, not recomputed

`run_metrics` (Flyway `V5`): one row per operation plus one for the `*` aggregate, all integers,
unique on `(run_id, operation)`. Written by `ResultCatalog.recordAnalysis` — **one method, therefore
one transaction** with the objective verdicts, so a report can never pair a p95 from one analysis
with a verdict from another. `runMetricsFor` returns `Optional<NormalizedRunResult>`, so a stored
report and a recomputation are directly comparable — and one test asserts they are equal.

`engine` is duplicated on every row so a metrics row is self-describing without joining
`execution_plans`.

### Report and artifact download

- `GET /api/v2/test-runs/{runId}/report` — **JSON**, assembled by `ReadRunReportUseCase` from
  persisted state only. `measurements` and `execution` are **null, never zeroed**, when a run has not
  reached them.
- `GET /api/v2/test-runs/{runId}/artifacts/{artifactId}/content` — authorized per request, digest
  verified before any byte is written, served as `attachment` with `nosniff`. **No pre-signed URL is
  minted or stored** (CLAUDE.md §17); a test asserts the listing contains no URL at all.
- `RunReport.tsx` + `console.css`: verdict summary, per-operation latency table, stage profile with
  proportional bars, journey mix, download links, and a collapsed "How the platform ran this" for the
  engine and plan digest (§24's permitted advanced view).

### One behaviour change outside the increment's own surface

`ReadTestRunUseCase.get` now raises `UnknownRunException` (**404**) for a run belonging to a team the
caller is not in, where it previously raised `AuthorizationException` (**403**). Its own javadoc, the
problem handler's and `TestRunControllerTest`'s display name all already claimed 404; only the
implementation disagreed, and the artifact download would otherwise have been an oracle for which run
and artifact identifiers are real. A caller who *is* in the owning team but lacks the scope still gets
403 — they already know the run exists.

### Also fixed
`FileSystemArtifactStore.read` resolved keys through an in-memory index, so every artifact became
unreadable after a restart — including the ones a report links to, which are exactly the artifacts
someone opens days later. It now resolves the key against the root.

---

## 3c. Manual requests and the demo-service path — delivered

The renderer emitted `GET /{operationId}` for every scenario, so nothing could be load-tested from
the V2 console: against a real service every request 404s.

- **Optional `method` and `path` on a scenario.** The simplest case of CLAUDE.md §23's manual entry,
  additive so every earlier document stays valid. Both are in the canonical form and therefore in the
  digest — two versions differing only in the path they call are different tests.
- **Only the path comes from the document.** Scheme, host and port still come from the resolved
  target, and a path that is a full URL is refused at validation. A specification cannot name its own
  host; that is the whole point of the target policy.
- **A validator that disagreed with its evaluator, fixed.** `ObjectiveEvaluator` resolves a
  `workload/scenario` scope — samplers are labelled that way precisely so it can (defect D4's fix) —
  while `SemanticValidator` rejected exactly that scope as unresolved.
- **`demo-service` is a first-class local target**: a target on `local-dev`, a validated shipped
  example, and one of two named starting points in the console. Its objectives breach on purpose.

## 3d. Increment 1E — delivered

### Kubernetes execution provider (`runners/runner-kubernetes`)

A new module, engine-neutral: it starts, observes and stops a runner and knows nothing about JMeter,
so a second engine reuses it unchanged. Fabric8 client, chosen for its Job builder and its
`KubernetesMockServer` — the whole Job contract is tested without a cluster (CLAUDE.md §25).

`RunnerJobManifest` encodes the safety posture, each line pinned by a test that names the failure it
prevents: `backoffLimit: 0` and `restartPolicy: Never` (a retried performance test is duplicate load
on a real system), `activeDeadlineSeconds`, TTL, non-root, read-only root filesystem, no privilege
escalation, all capabilities dropped, `RuntimeDefault` seccomp, explicit requests and limits, a
size-limited `emptyDir`, and no secret value in any label, annotation, environment entry or argument.

`KubernetesExecutionProvider` **adopts an existing Job rather than recreating it** — the second
defence against duplicate load, for when a pass dies between creating the Job and recording the
handle. It compares **Job UIDs**, not names, so a recreated Job is never mistaken for the original,
and cancellation uses a **foreground cascade** so Pods die with the Job rather than being orphaned
while the platform reports the run stopped.

### S3-compatible artifact store

`S3ArtifactStore` beside the filesystem one, same key layout, so the two are interchangeable and
authorization stays a property of the key. Tested against **MinIO** rather than a fake: the
properties worth testing are exactly the ones a fake would be written to satisfy. Still no
pre-signed URL anywhere.

### Startup refusals, all tested

Production now has a real path rather than a blanket refusal, and the guards moved to the things that
actually matter: a runner cell with no namespace, no service account or no image allowlist will not
start; **a floating image tag is refused** (a moving tag means two runs of one immutable version
execute on different builds while the plan digest still matches); and a **pod-local filesystem is
refused in production**, because a file one replica writes is invisible to the others and unreachable
by a runner.

### Also
Helm runner-cell namespace with `pod-security: restricted`, a runner ServiceAccount, and a namespaced
`Role` granting the control plane `create/get/list/watch/delete` on Jobs — deliberately **no
`update`/`patch`**, since an edited Job is a test whose plan digest no longer describes what ran.
`docs/operations/runner-calibration.md` covers why a throttled generator reports its own latency, and
why shard count stays at 1 until it is measured. `BaselineExecutionPlanCompiler` deleted.

## 3e. The runner image — delivered

`runner-images/jmeter-runner`. **It has actually run**: 7,969 requests against the demo service,
executed by the image with a read-only root filesystem, all capabilities dropped and uid 10001,
fetching its plan from MinIO and uploading `results.jtl`, `jmeter.log` and `runner-manifest.json`.

Building it exposed two gaps that had to be closed first:

1. **The Job manifest told the runner nothing about storage.** It passed `PERF_WORKLOAD_KEY` and no
   bucket, so a runner had a key and nowhere to use it. `RunnerCellSettings.ArtifactStorage` now
   carries the coordinates — and only coordinates, since a credential in a manifest is a credential
   in an audit log. The runner is also *given* its key prefix rather than left to build one: key
   layout is what makes authorization structural, and a runner that constructed its own path could
   write into another team's prefix.

2. **Nothing ingested what a runner uploaded.** The local providers store an artifact and record it
   in one step because they share a process with the control plane. A runner Pod does not, and has no
   database access by design. Analysis would have found nothing at all.
   `RunWorkloadService.adoptUploadedArtifacts` now adopts what appeared in storage immediately before
   results are read — once per analysis, and idempotent by object key.

**Plan digest verification, finally implemented.** CLAUDE.md §15 required it and nothing did it. Every
runner manifest is checked against the run's own plan, and a mismatch fails the run with
`PLAN_DIGEST_MISMATCH` rather than merging the results. A manifest carrying *no* digest is tolerated —
an older runner has said nothing rather than said something wrong.

`RunnerOutputContractTest` parses a fixture the image genuinely produced. The image is versioned
separately from the Java code, so nothing else keeps its output readable; if that test fails after an
image change, the image is wrong until proven otherwise.

## 3f. Proven on a real cluster

**A real Kubernetes Job has run a performance test.** kind, control plane in-cluster, runner Job in a
`pod-security: restricted` namespace: `Complete`, 7,932 requests against the demo service, two
objectives passed and one breached. Procedure in
[`docs/operations/local-kubernetes-run.md`](docs/operations/local-kubernetes-run.md) — repeat it after
changing the runner image, the Job manifest or the artifact store.

Verified as the API server *admitted* it, not merely as built: `backoffLimit: 0`,
`restartPolicy: Never`, `activeDeadlineSeconds`, TTL, non-root uid 10001, `fsGroup`, read-only root,
`allowPrivilegeEscalation: false`, all capabilities dropped, `RuntimeDefault` seccomp, and no
credential value anywhere in the manifest. RBAC checked with `kubectl auth can-i`, including the two
refusals that matter: the control plane cannot `patch` a Job, and **a runner cannot create one**.

### It found three defects nothing else had

Each presented as *a run that measured nothing*, never as an error — the failure mode this platform
has to be most suspicious of.

1. **The runner could not authenticate to object storage.** "The Pod's projected service-account
   token authorises it" is true on EKS with IRSA and false for any S3-compatible store that only
   understands access keys. The Job ran and failed on `Unable to locate credentials`. Fixed by
   referencing a Kubernetes Secret **by name** from the Job manifest — kubelet injects the values, so
   nothing sensitive passes through the control plane, the manifest or a log (CLAUDE.md §20).

2. **The control plane could not see what a runner uploaded.** `S3ArtifactStore.listForRun`
   identified artifacts by object metadata that only its own uploads set; a runner using an ordinary
   S3 client sets none, so every runner-produced file was silently skipped and the run reported
   *"0 of 1 runner shards reported"*. Fixed by deriving identity from the **object key**, which is the
   contract both sides actually share.

3. **The shard was lost even once artifacts were found.** References rebuilt from storage carried no
   shard id, and analysis matches results to the shard that produced them — so results would have been
   adopted and still been invisible.

Also added, because a local cluster needs it and a VPC endpoint will too:
`artifacts.runnerEndpoint`, for when runners reach storage by a different route than the control
plane.

### Not done, and owned by later increments
- **Sharding is untested.** Everything so far is `PERF_SHARD_COUNT=1`, and dataset partitioning does
  not exist — multiple shards would replay identical work. Shard count stays at 1 until calibration.
- **`ConcurrencyThreadGroup` is installed in the image but unused.** Using it is a change to
  `JmeterWorkloadRenderer`, which still emits stepwise thread groups.
- **Not run on EKS**, so IRSA specifically is still unexercised; kind used a Secret instead.

---

## 3g. Increment 2 — in progress

Increment 2 as CLAUDE.md §28 specifies it is large: five importers plus dataset partitioning, dry-run
preflight, baseline comparisons and Jenkins integration. It was split. **Two parts are committed.**

### 2a — a specification can express a whole request (`1b61d0e`)

A scenario could name a method and a path and nothing else, so there was nowhere for an importer to
put what it imported. Scenarios now carry `headers`, `query` and `body`.

All three are in the canonical form and therefore the **digest**: two versions differing only in a
header describe different requests. Maps canonicalize **sorted**, so reordering a header block is not
a new test, and `.count` is written even when empty so "no headers" cannot collide with "headers
absent".

**Credentials cannot be smuggled in.** This is the part to be careful about, because a specification
is stored, rendered, committed to Git and echoed back by the validate endpoint:

- `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`, `X-Auth-Token` and
  `X-Amz-Security-Token` are refused **by name**, whatever the value. That check cannot produce a
  false negative.
- A token-shaped value under any other header name is refused too
  (`SecretValueDetector.looksLikeCredential`).
- Query parameters get the same treatment for a sharper reason: a query string is written to every
  access log between the runner and the target.
- **No finding ever echoes the value it rejected**, and a test pins that.

The shape check is deliberately **narrow**. It runs over ordinary values like `application/json`, and
a validator that cried wolf would be worked around — at which point it protects nothing. A test pins
that ordinary values pass untouched. Widening it is a mistake; add a name to the refused-header list
instead.

A body on `GET`/`HEAD` is refused: many servers and proxies drop it silently, so the test would
measure a different request than the one written — a wrong answer rather than a failure.

Rendering: query parameters are **encoded** (a value containing a space or `&` would otherwise change
which parameters the target sees); a body is **raw**, not form-encoded, and carries a `Content-Type`
header even when the scenario sets no headers at all, because JMeter sends none by default and most
APIs reject a JSON body without one. Headers go in a **per-sampler** `HeaderManager`, so one
scenario's headers do not reach another's requests.

### 2b — the cURL importer (`9fcefe1`)

`SourceImporter` is a domain port: a source in, draft scenarios **plus findings** out. Its javadoc
records the four things an importer must never do — invent business data, import a credential,
execute anything, or choose a target.

`platform-importers/importer-curl` parses a command as arguments and **never hands it to a shell**
(CLAUDE.md §23). `$(whoami); rm -rf /` survives as literal text, and a test pins that *absence of
interpretation* — it arrives over HTTP from whoever pasted it. The tokenizer implements only the
quoting a pasted command uses; no substitution, globbing, pipes or redirection.

It strips and **reports**: Authorization, cookies, `-u`, `--oauth2-bearer`, client certs, token-shaped
header values, credential-looking query parameters, and the host. Reporting matters as much as
stripping — someone told "authentication was removed, declare it as a secret reference" does that;
someone told nothing meets a wall of 401s at run time. `Content-Length`, `Host` and `Connection` are
dropped as client-negotiated rather than authored.

`POST /api/v2/imports/{sourceType}` **creates nothing** — no definition, no version, no run. Safe to
call repeatedly while iterating, and an import cannot become an executable test without someone
having looked at it. It returns scenarios rather than a whole specification, because a source cannot
answer what the load model, objectives or journey mix should be, and placeholders would produce a
test that looks ready and means nothing.

## 3h. Increment 2c — the OpenAPI / Swagger importer

`platform-importers/importer-openapi`, registered exactly as the cURL one is. OpenAPI 3 and Swagger
2, YAML and JSON, from one parser.

**The decision `NEXT-SESSION.md` left open — how an importer parses — resolved as: the module holds
its own Jackson.** `jackson-databind` and `jackson-dataformat-yaml` are already dependencies of
`platform-api` and `platform-core`, so nothing new entered the build and no §31 pause applied. The
alternative (passing a parsed tree through the port) would have put a serialization concern into
`SourceImporter`, and therefore into the domain, which 1B deliberately kept Jackson-free. An importer
is an adapter and a format library is what an adapter is for. `ModuleDependencyRulesTest` forbids
Spring, persistence, web, Kubernetes and engine types for importers, and now names both modules;
`SERIALIZATION` is deliberately not on that list, with the reasoning recorded in the test's javadoc.

### It does not invent business data — which is most of what it does

CLAUDE.md §23's hardest rule, and the reason it is hard: a draft full of plausible invented values
looks finished. It would send requests the target rejects, at full load, and report the rejection
latency as the API's performance — a wrong answer rather than a failure, which is the mode this
platform has to be most suspicious of.

`StatedValues` is the whole of the policy. Every value comes from something a person wrote in the
document — `example`, an `examples` entry, a schema `example`, a `default`, or the first `enum`
member. Nothing is derived from a type, format, pattern or minimum. Where a value is needed and none
is stated, the gap stays visible:

- An unfillable **path** parameter leaves `{orderId}` in the path and is an **ERROR**, not a warning.
  It is the one gap that is not survivable: every request 404s, and a run made of 404s reports fast,
  healthy-looking latency for a target that did no work. The scenario is still returned so it can be
  completed — the finding is what says it is not ready.
- A **request body** described only by a schema is not generated. Warning, with the reason.
- A **required** query or header parameter with no stated value is reported; an **optional** one is
  simply omitted, because omitting it is a valid request and a finding for each would be noise.
- Which values *did* come from the document is reported as a count: they are valid shapes, not
  necessarily data that exists in the environment the test will run against.

Weights are equal shares summing to 100, and the draft says so. The document describes what an API
*has*, never how much traffic each operation receives; a fabricated distribution would read as a
decision somebody made.

### Untrusted input, treated as such

The document arrives over HTTP from whoever uploaded it.

- **An external `$ref` is not followed.** Fetching a URL named by an uploaded document would be a
  request forgery using the platform's own network position — the test uses a link-local address to
  say so. Reported and left unresolved. Reference *cycles* terminate rather than looping.
- Parameters named as credentials are refused by **name**, token-shaped examples by **shape**, and
  no finding ever echoes what it refused (pinned by a test). Cookie parameters are dropped; a
  declared `security` requirement is reported so it is replaced rather than discovered as 401s.
- The host is dropped and reported. The server URL's **path prefix is kept** — `/v1` is part of the
  request, not part of the address — and a path is never a full URL, so a document cannot name its
  own host.
- `TRACE` is skipped (the specification does not support it); a body declared on `GET`/`HEAD` is not
  imported, because servers and proxies drop it unpredictably and the test would measure a different
  request than the one written. Over 200 operations the import is truncated and says so.

### operationId is preserved, and now reaches the caller

CLAUDE.md §23 requires it. `ScenarioSpec` already carried the field; **`ImportResponse.ImportedScenario`
did not**, so it would have stopped at the API boundary. Added — null for cURL, which has no such
concept. Names cannot carry it: a name reaches artifact object keys and Kubernetes labels and must
be lowercase-alphanumeric-with-hyphens, so `listOrdersByCustomer` becomes the name
`list-orders-by-customer` while the operationId survives verbatim. That is what lets a report be
matched back to the operation in the document.

### One suspected defect found on the way, since investigated and withdrawn

An earlier pass recorded that **a top-level `@Test` in a class that also declares `@Nested` classes
is silently never executed**, on the evidence that the outer class reports `Tests run: 0`. That
conclusion was wrong, and the investigation that retired it is worth keeping:

- The JUnit Platform console launcher, run against the same classpath, shows both top-level methods
  executing (`--details=tree` marks them ✔). The pattern also works in a minimal standalone
  reproducer, in either declaration order.
- Surefire's own XML confirms it. `TEST-…RunStateMachineTest$Capacity.xml` contains
  `testcase name="draftIsNotARunState" classname="…RunStateMachineTest"` — the correct class, filed
  into the wrong report file.
- Injecting a deliberate failure into `draftIsNotARunState` turns the build **red**
  (`Tests run: 29, Failures: 1`). A test that never runs cannot do that.

Every affected method was confirmed present in the reports this way. The real defect is Surefire
attributing the outer class's tests to the last nested class's report file and then printing
`Tests run: 0` for the outer class. No coverage was lost and no test source needed to change.

**The audit that accompanied the original claim was also short.** It listed 7 methods in 6 classes;
there are **8 in 7** — `domain/target/TargetPolicyTest#loopbackIsPermitted` was missed. Grepping for
the pattern is unreliable; the reliable enumeration is to diff which classes stop reporting
`Tests run: 0` across Surefire versions.

**Fixed by pinning Surefire 3.5.3** in the root pom (3.1.2, 3.2.5, 3.3.1, 3.5.0 and 3.5.2 all
misattribute; 3.5.3 does not). Verified across the full reactor: the S2-era full-test count stayed
unchanged, 0 skipped, BUILD SUCCESS, per-module totals byte-identical, and the 7 affected classes
now report their own counts with no class newly reporting 0. The 18 classes where *every* test is nested still report
`Tests run: 0` for the outer container, which is correct. `importer-openapi`'s all-`@Nested`
convention is fine as a style but is not load-bearing.

---

## 3i. Increment 2d — the HAR importer

`platform-importers/importer-har`, registered like the other two. Jackson databind only — a HAR is
JSON by definition, so no YAML dataformat.

**A HAR is the best and the most dangerous source this platform accepts**, and both halves shaped
the implementation.

### Best: it is the only source that knows how often each request was made

An OpenAPI document lists every operation and says nothing about which matter — hence 2c's equal
weights. A capture answers that question with evidence, so **scenario weights come from observed
frequency**: repeats of the same call are collapsed and each repeat raises that scenario's weight
instead of adding a scenario. Largest-remainder apportionment to exactly 100, with every scenario
guaranteed at least 1 (a zero weight means the scenario never runs, which the specification refuses).
A finding states the caveat that matters: one capture is one person's session, not production
traffic.

### Dangerous: it is a verbatim recording of a live session

Every removal below is counted and reported, because CLAUDE.md §23 requires review before load
execution and a person can only review what they have been told. **`import.har.reviewRequired` is
emitted unconditionally**, including on an import that produced nothing.

**The case `SecretValueDetector` cannot reach.** It asks whether a *value* looks like a credential,
which catches a bearer token. It cannot catch `{"username":"a.smith","password":"Sunflower2019!"}` —
nothing about that password is token-shaped, it is simply a real person's real password, and the
sign-in request is the single most likely entry in a capture to be worth load-testing. So
`CapturedBody` tests the **field name** instead, walking JSON at any depth and form-encoded
parameters, with a pattern fallback for XML and plain text. A body with a credential field is
refused **whole**, not redacted: partial redaction of a structure the class only partly understands
is a guess, and a wrong guess here discloses a credential. Personal data (`ssn`, `cardNumber`,
`dateOfBirth`, …) is refused on the same reasoning — it is a real customer's, and unlike a password
it will still be valid next year. The scenario survives; only its body is dropped, and the finding
names the field, never the value.

**The filter that has to exist.** A capture calls analytics, CDNs and payment widgets. This platform
drops the host from every request and sends every scenario to the one registered target — so an
unfiltered capture would have aimed Google Analytics' `/collect` at the team's own service and
reported the result as their latency. The **dominant host** (by request count, taken after assets
and failures are filtered) is kept and every other host is dropped and named. Frequency rather than
first-seen, because the first entry of a session is often a redirect from an identity provider.

Also removed: cookies (counted, never read); `Authorization` and friends via `SecretValueDetector`;
CSRF and session headers by name — not credentials in that class's sense, but minted for one session
and rejected on replay; credential-shaped query parameters; static assets (extension or response
content type); requests that **failed when captured**, since replaying a known failure adds it to
the run's error rate; multipart bodies, which captures store truncated so what was recorded is not
what was sent; and HTTP/2 pseudo-headers and browser negotiation headers.

**The subtle one is conditional headers.** Replaying `If-None-Match` makes the target answer 304
without doing any work, and the run reports excellent latency for a service that did nothing. Same
family as the synthetic-run problem: a wrong answer rather than a failure.

### Journeys: grouped, and honest about what that means

Entries are grouped by the capture's own `pages`, and scenarios are named `<journey>-<call>` —
`checkout-api-basket`, `checkout-post-api-orders`. That is the most this model can carry: **a
`ScenarioSpec` is one request**, and the load model runs scenarios independently in weighted
proportion, so a captured sequence does not survive as a sequence. `import.har.journeys.notOrdered`
says so plainly, as a warning: order and correlation are not preserved, and a request needing an id
from an earlier response will not have one. Multi-step scenarios would be a change to the
specification model, and inventing the appearance of one here would describe a test that does not
exist.

### Verified over HTTP

A capture containing a live cookie, a JWT, a password and a beacon produced three scenarios and
eleven findings, and **grep found no byte of the password, the token or the cookie anywhere in the
response**.

---

## 3j. Increment 2e — the Postman importer

`platform-importers/importer-postman`. Collection **v2.0 and v2.1**; a v1 export is refused by name
with the way out, because Postman itself converts them and maintaining a second reader for a format
nothing has exported since 2016 is the wrong trade.

**With this, every importer CLAUDE.md §23 lists exists** — OpenAPI, Postman, HAR, cURL and manual
entry. (Playwright import is Increment 3, and is a source *reference* rather than a document.)

### A collection is the friendliest source, and it hides two things

It is the closest thing to a test that already exists: somebody curated these requests, **named them
in words a person chose**, and arranged them into folders that usually correspond to real journeys.
So names come from the author — folder path plus request name, kebab-cased — rather than being
derived from paths as the other importers must. That is the single biggest quality difference in the
drafts this produces.

**It is the only source that carries executable code.** Pre-request and test scripts are flagged and
never evaluated: the control plane does not run code that arrived in a document. It does not
*summarise* them either — a description of an unevaluated script would be a guess presented as fact.
One finding per script naming what it belongs to, at collection, folder and request level, plus a
summary spelling out the failure this exists to prevent: **a collection whose scripts perform the
sign-in imports perfectly and then fails on every request.** Someone who is told that checks; someone
who is not loses an afternoon. A test asserts no script's text reaches a scenario or a finding.

**It leaks credentials more quietly than a HAR does.** Everyone knows a capture is sensitive. Nobody
thinks that about a collection they send a teammate — yet an export ships its variable values, and a
variable named `accessToken` usually holds a working one. `CollectionVariables` substitutes a
placeholder only when it passes both tests the platform already applies: the **name** is not one
`SensitiveFieldDetector` recognises, and the **value** is not one `SecretValueDetector` recognises.
A variable failing either is left as its placeholder and named in a finding that recommends rotation.

### Three ways a placeholder fails to resolve, and they are different problems

Refused (the collection states a credential), unknown (the value lives in a Postman *environment*,
which exports separately and was not uploaded), and dynamic (`{{$guid}}` — the platform does not
evaluate them, and a dataset is the equivalent). Each gets its own finding, because the remedy
differs. In a **path** any of them is an **error**, on the same reasoning as the OpenAPI importer:
`/orders/{{orderId}}` sent literally 404s on every request, and a run made of 404s reports fast,
healthy-looking latency for a target that did no work. Postman's `:name` path syntax resolves from
the request's own `url.variable` list and is treated identically when it does not.

All five body modes are handled: `raw` (media type from the declared language), `urlencoded`
(assembled from enabled fields, credential-checked **on the names before encoding**, since
percent-encoding would hide `client_secret` from a scan of the serialized body), `graphql`
(assembled into the JSON body a server actually receives), and `formdata`/`file` refused — a
collection stores a path on the author's machine, not the contents, so there is nothing to send.

### One refactor, at the moment the second consumer appeared

2d's `CapturedBody` — the field-name policy that catches a plain-text password no value check could
recognise — moved into `platform-domain` as **`SensitiveFieldDetector`**, beside `SecretValueDetector`
where it belongs: it is a rule about what a specification may contain, not about HAR. The domain
holds no serialization library, so JSON keys are matched with a regular expression rather than
walked with Jackson; only field *names* are ever needed, it cannot be defeated by nesting depth or by
a document that does not quite parse, and over-matching is the safe direction. HAR's 39 tests passed
unchanged across the move, which is what says it was behaviour-preserving.

### Verified over HTTP

A collection with a bearer auth block, a credential variable, an `Authorization` header, an
unresolved `{{orderId}}` in a path, a pre-request script and a saved sign-in body produced three
scenarios and ten findings — and **grep found no byte of the password, the token or the script text
anywhere in the response**.

---

## 3k. Increment 2f — importing from the console

Four importers existed and were reachable only over HTTP, which is no use to the people they were
built for. `platform-ui/src/v2/ImportPanel.tsx` and `scenarioYaml.ts`, opened from a button inside
the test editor — importing is a step in describing a test, not a destination of its own.

Paste or choose a file → **Read it** → the journeys as a table, and the findings in full underneath.

### The findings get more room than the journeys

Deliberate, and the whole reason the panel is shaped this way. Every importer removes far more than
it keeps, and what it *could not* import is what stands between a draft and a test that measures
something. They render with severity, message **and remediation**, sorted errors-first, and they
**stay on screen after the journeys are inserted** — the alternative to reading them is a wall of
401s at run time.

### The splice validates itself against the server

"Use these journeys" replaces the document's `scenarios:` block. Replacing part of a YAML document by
manipulating text is only safe when it is checked — so `spliceScenarios` refuses unless there is
exactly **one** `scenarios:` key, and the result is then **posted to `/validate` before it is
accepted**. An edit that does not round-trip is discarded, not handed back looking fine. The platform
already owns a validator; using it here turns an operation that could not otherwise be trusted into
one that is proven every time.

That safety net immediately earned itself. **The demo template cannot be imported into**, because its
objectives are scoped to individual journeys (`demo-load/browse-users`) and replacing the journeys
leaves them pointing at names that no longer exist. The validator refuses, nothing changes, and the
message says so specifically — telling someone to paste the journeys manually would have been wrong,
since that hits the identical wall. The **Blank starter**, whose objectives cover the whole workload,
splices cleanly. That is not a defect in either template: an objective about a journey that is not in
the test is a question nobody can answer, and only a person can decide what it should say instead.

### Verified in a browser, not asserted

cURL with an `Authorization` header and a cookie → one journey, credentials stripped and reported.
HAR with a live cookie, a plain-text password, a CDN script and an analytics beacon → two journeys,
ten findings. Splice into the Blank starter → correct indentation, quoted values, headers and query
maps → **Save test** → `Created "checkout-baseline" version 1`. Console clean apart from V1's
pre-existing `Error fetching teams: 403`.

### Not done

Dataset partitioning; dry-run preflight; baseline comparisons; Jenkins shared library.
`NEXT-SESSION.md` §2 has the next step.

### The gap worth knowing about

**`platform-ui` has no test runner at all** — no vitest, no jest, nothing in `package.json`. So
`scenarioYaml.ts`, the one piece of this increment with real logic worth pinning (indentation
detection, block extent, YAML escaping), is covered only by the browser walkthrough above and by the
server-side validation round-trip. Adding vitest is a CLAUDE.md §31 pause condition and was not taken
unilaterally. It is the single most valuable thing to add before the UI grows further.

---

## 3l. Increment 2g — dataset binding and partitioning

**This is half of datasets, deliberately.** A dataset can now be declared, bound to by a journey, and
split so no two generators send the same row. What it cannot yet do is *arrive*: uploading a file,
storing it team-scoped and delivering each shard its partition is 2h, and until that lands a
specification can declare a dataset the platform will not find. The split was made because the
storage half needs a team-scoped artifact port that does not exist — `ArtifactStore` is run-scoped
all the way down to `ArtifactDescriptor` — and bolting that on alongside the domain work would have
produced a half-built production path.

### `DatasetPartitioner` — the piece that gates sharding

`platform-domain/dataset/`. Round-robin, not contiguous blocks: datasets arrive sorted far more often
than anyone intends — by created date, by customer, by region — and contiguous slices would hand
shard 0 every January record and shard 9 every December one, so a per-shard latency difference would
say more about the data than about the system. Every partition keeps the header, so each is a valid
CSV in its own right. Deterministic, so a run is reproducible from its stored inputs. With fewer rows
than shards the later shards get **nothing** rather than everyone getting a duplicate of row 1 — a
duplicate is a generator sending traffic somebody else is already sending.

This is what CLAUDE.md §15 requires of every shard and why shard count is pinned at 1. It is now
implemented and tested; raising the count still waits on calibration and on 2h.

### `${column}` binding, and why an unresolved one is an error

A journey says `path: /orders/${orderId}` and each iteration takes the next row. `DatasetBinding`
finds every reference across path, query, headers and body; `SemanticValidator` checks each against
the columns the datasets declare.

**Unresolved is an ERROR, not a warning**, and it is the same failure mode the platform keeps
guarding against from a new direction: an unfilled reference is not left empty, it is sent
*literally*. `/orders/${orderId}` goes out with those characters in the path, every request 404s, and
the run reports fast healthy-looking latency for a target that did no work. A misspelled column name
is a far easier mistake than a missing import — **verified live: `${orderID}` against a dataset
declaring `orderId` is refused before any load is generated.** Where no dataset declares columns at
all, references are reported as *unverifiable* (warning) rather than wrong.

`${name}` is JMeter's own variable syntax, so a bound value passes through the renderer untouched.
That is a convenience the JMeter engine happens to give, not a contract — and every reference is
still resolved against declared columns rather than passed through unread, so a specification cannot
smuggle in engine functions. **A test caught that this was not true**: `${__threadNum}` matched the
first pattern, because it allowed a leading underscore. Every JMeter function begins with a double
underscore, so that is now the line drawn.

### Columns are declared *and* verified

`DatasetSpec.columns` is new, and in the canonical form — so it is in the digest, and two versions
expecting different columns are different tests. It is a declaration rather than a second source of
truth: validation is side-effect-free and has to work before any file exists, so it checks against
what the test *expects*, and 2h compares the file's actual header against it before the run starts.
The same contract-and-verification shape as the plan digest.

**Consequence worth knowing:** the canonical form changed, so a specification containing a dataset
digests differently than it did before this increment. Stored versions keep their stored digest and
nothing recomputes one, so nothing breaks — but a document re-validated today will not match a digest
recorded yesterday.

### JMeter rendering

One plan-level `CSVDataSet` per dataset, reading `{name}.csv` from the runner's working directory —
a plain file name, never an absolute path, because a path made on the control plane means nothing
inside a runner Pod. `shareMode.all` so a row is used once per shard rather than once per thread
group. **`recycle: false`, `stopThread: true`**: recycling would restart the file under load, the
target would begin answering the same requests from cache, and the run would report excellent latency
for work it had stopped doing.

### Delivered by 2h

Everything listed here as missing — uploading, team-scoped storage, slicing at preparation, header
verification and delivery — now exists. See §3m.

---

## 3m. Increment 2h — dataset storage and delivery

**A dataset now arrives.** Uploaded per team, stored immutably per version, verified against the
columns the test declares, sliced so no two shards send the same row, and delivered to the shard
that sends it. This closes the last half-built thing in the tree.

**Verified with real load, not asserted.** A dataset of 200 rows, a journey whose path is
`/api/${resource}`, and the demo service: **200 requests, 1 error (0.5%), p95 34 ms, objective
passed.** That is the decisive check available — an unbound `${resource}` is sent as those literal
characters, so every request would have 404'd and the error rate would have been 100%. The stored
`DATASET` artifact downloads as the 200-row partition and the generated JMX names
`demo-resources.csv`. The refusal path was exercised the same way: a specification pinned to
`dataset://demo-resources/9`, which was never uploaded, failed with `PROVISIONING_FAILED` **while
still queued** — before a generator existed.

### The port decision `ROADMAP.md` §1 left open: a sibling, not a widening

`ArtifactStore` is run-scoped to its foundations — `ArtifactDescriptor` *requires* a `TestRunId` and
every key sits under `runs/{runId}/`. That is what makes a run's evidence retrievable, authorized and
reclaimable as one unit. A dataset has none of those properties: it exists before any run, is read by
many, and outlives all of them. Widening would have meant either a nullable run identifier on the
descriptor — quietly weakening the invariant that every artifact belongs to a run — or a second key
layout inside a class whose javadoc promises one.

So: **`DatasetStore`** (content) and **`DatasetCatalog`** (the record), the same split as
`ArtifactStore`/`ResultCatalog`, with the same team-first key layout
`teams/{teamId}/datasets/{name}/{version}/data.csv`. Filesystem and S3 implementations, chosen by the
*artifact* store's own setting rather than a switch of their own — a deployment with artifacts in
object storage and datasets on a pod-local disk would fail in the least obvious way available: runs
would provision, and every shard would start without the data it was supposed to send.

### `dataset://{name}/{version}`, and the version is not optional

A specification names *which data*, never where it lives — exactly as it names no host. `dataset://`
resolves against the caller's own team, so a document cannot reach another team's rows.

**The version is required**, and this is the decision to defend. `dataset://orders` reads naturally
and means "whatever orders holds today", so two runs of one immutable definition version would read
different rows while carrying the same specification digest. That is the same defect as a floating
runner image tag, which 1E already refuses at startup. It is refused at validation with the fix
named, and refused separately from an unparseable reference, because they are different mistakes:
one needs a version, the other needs an upload.

Consequence: the shipped `api-only.yaml` example changed from `artifact://datasets/proposal-clients.csv`
to `dataset://proposal-clients/1`, and its digest with it.

### Column names are the guard, because a value check cannot help here

Every other door into this platform is watched — importers refuse credential-shaped values, headers
named as credentials, bodies with a `password` field. A CSV upload is the remaining one, and it is
now the *easiest*: a spreadsheet of sign-in data is precisely what somebody reaches for after being
told the importer would not keep their password.

`SensitiveFieldDetector.scanNames` over the header, and the upload is refused **whole** — nothing
stored, not a redacted file with a warning on top. Dropping the column silently would leave a test
bound to `${password}` that resolves to nothing and sends the literal text. The finding names the
column and **never a value**; a test pins that, as does the controller test, because the response is
returned over HTTP and written to a log. What this cannot see is said plainly rather than implied
away: `dataset.contents.notInspected` is emitted on every accepted upload, because `customerId` is an
ordinary column name and whether the rows are real customers is a governance question, not a parsing
one.

### Versions are allocated, never chosen — enforced by the database

The uploader names a dataset; the platform picks the version. Immutability rests on
`uq_datasets_team_name_version`, not on a prior read: two people uploading `orders` at the same moment
would both see no version 4 and both write, and only the database can decide which wins. The loser is
told to retry, which is the truthful answer — their rows are not what version 4 holds. PostgreSQL also
carries a trigger refusing any `UPDATE`, the same shape as `test_definition_versions`; H2 does not, so
that assertion runs against Testcontainers.

### The declaration became a contract at the moment it could be checked

2g introduced `spec.datasets[].columns` as a declaration, because validation is side-effect-free and
runs before any file exists. `prepareShard` now compares it against the file's actual header and
**fails the run** on a mismatch. That is what turns it from a second source of truth into the same
contract-and-verification shape as the plan digest: a dataset re-exported with `order_id` where the
test expects `orderId` passes validation — validation ran against the declaration — and would
otherwise have sent the literal text at full load. Extra columns in the file are fine and deliberately
unreported: a dataset legitimately serves more than one test.

### Slicing happens in the control plane

`prepareShard` reads the dataset, calls `DatasetPartitioner`, and stores each shard's slice as a
`DATASET` artifact of the run. A runner taking every *n*th row itself would be untestable without an
image rebuild, and what each generator sent would exist only inside a pod that no longer exists.
Storing the slice makes a run's **inputs** as recoverable as its outputs — "which orders did shard 3
submit?" has an answer. The cost is that the control plane reads the whole file, so
`platform.orchestration.datasets.max-size` caps it at 16 MiB and the refusal says why the cap exists.

### The collision that would have been silent

A dataset partition is a CSV. So is a JMeter results file. `FileSystemArtifactStore.typeForFileName`
maps `.csv` to `RESULTS`, and adoption from object storage derives type from the key — so a run's own
input would have been adopted as its output and thousands of order identifiers parsed as samples,
contributing to the latency and error figures a verdict is drawn from, with nothing looking wrong.
Stored objects therefore carry a `dataset-` prefix (`ArtifactStore.DATASET_OBJECT_PREFIX`), checked
*before* the results rule; the engine still receives the file under its own plain name. Pinned by a
MinIO test that stores both in one shard prefix and asserts the two types come back distinct.

### Two contracts that could have drifted, and now cannot

- **The delivered file name comes from the engine.** `ExecutionEngineProvider.datasetFileName`
  defaults to `{name}.csv` and JMeter's implementation delegates to the renderer that writes the name
  into the plan. A name decided in orchestration and a different one written by a renderer is a run
  that starts with the file present under a name JMeter never looks for.
- **`PERF_DATASETS`** carries `fileName=objectKey` pairs to a Kubernetes runner — keys, never content,
  because a Job manifest is readable by anyone with cluster access and a dataset is business data. The
  entrypoint fetches each into the workspace, refuses a name containing a separator rather than
  sanitising it, and treats a failed fetch as fatal. It also now `cd`s to `PERF_WORKSPACE`, since a
  `CSVDataSet` names a plain file resolved against the working directory.

### Not done

- **The runner-image change is unverified on a cluster.** The entrypoint's dataset fetch is new and
  the kind procedure in `docs/operations/local-kubernetes-run.md` has not been re-run since. The local
  provider path *is* verified with real load. **Re-run the kind procedure before trusting the
  Kubernetes path with a dataset.**
- **No console UI for uploading.** Datasets are API-only. The import panel is where it belongs, and
  `platform-ui` still has no test runner (§3k), which is the thing to fix before the UI grows.
- **Sharding remains at 1**, so the partitioner's multi-shard path is exercised by tests rather than
  by a real run. Raising the count still waits on calibration.
- **`inline` dataset sources are still accepted by the schema and not delivered.** Only
  `object-storage` with a `dataset://` uri resolves; an inline source now fails validation through
  the uri check rather than through a rule of its own.

---

## 3n. S1 — the studio

**A test can now be described by clicking, and the document it produces is the one the platform
already speaks.** Verified end to end in a browser against a running control plane: pick *Test an
API* → paste a cURL command → review the journeys → set load → set objectives → the platform
validates it live → **Save** → `studio-smoke-test` v1, digest `4c39a061…`, `executable: true`, with a
**Run this** button.

### The emitter is the increment, not the screens

`studio/specification.ts` is a pure function from the studio's state to the canonical YAML, and it
was finished and tested before any component called it. CLAUDE.md §32's "one specification, three
channels" is easy to satisfy on paper and easy to lose in practice — the studio grows a field, the
emitter grows a line, and the channels drift into describing subtly different tests. A byte-for-byte
test against a hand-written document is what makes that drift impossible to miss.

**Verified against the server, not only against an expectation.** The first emit came back with one
warning — no `maxDuration` — so the studio now states its own governance ceiling: the stages plus ten
minutes, matching the platform's execution grace, rounded up to a whole minute. A warning a consumer
cannot act on is worse than no warning; they either learn to ignore findings, which is the one habit
this platform cannot afford, or they go hunting for a governance concept no screen showed them.

### Consumer language is structural, not a style choice

A load shape is **peak users plus three durations**, never a stage list. Somebody who has never run a
performance test knows how many people they expect; they do not know that the last stage has to
target zero users. The translation into the platform's vocabulary happens in exactly one place, on
the way out.

`steps.ts` decides whether someone can move on **and** says what is missing, and the two agree by
construction — a test pins that `isSaveable` and the emitter never disagree, because a saveable draft
the emitter refuses is a Save button that throws, and the reverse is a button disabled for no visible
reason. Another test pins that no blocker is phrased as the rule that was broken.

Objectives are deliberately **not required**: demanding a threshold before a first run produces a
number somebody invented and then trusts.

### Two defects the browser found that tests did not

1. **Every status surface lost its background.** Channel triples were comma-separated, so
   `rgb(var(--pf-hue-fail) / 15%)` expanded to `rgb(239, 68, 68 / 15%)` — legacy and modern colour
   syntax mixed, which fails to parse. **CSS drops the declaration silently**: every pill, banner and
   the synthetic-run disclosure went transparent and fell back to `currentColor` for its border.
   Nothing threw, and the token test was happy because it only checked that literals were *absent*.
   Found by diffing computed styles against the pre-migration values; now pinned by two tests, one on
   the tokens and one on the override example in the comment, since that example is what a re-brand
   copies.
2. **The Load summary read "about 165s".** The specification's exactness rule — the number someone
   typed is how long the test runs — had leaked into a human sentence beginning with the word
   "about". Two different jobs, now two functions: `humanDuration` for the document,
   `approximateDuration` for the reader.

### Also

- **Design tokens** (§4.4 of `PLAN.md`): primitives a brand replaces, semantics components consume,
  and `tokens.test.ts` fails the build on a colour literal in any other stylesheet — proven by
  injecting one. Overriding three declarations moves the button and the load bar; the breach and pass
  pills do not move, because a palette that rendered a breach in a reassuring green would make the
  studio lie in its own house style.
- **vitest**, approved by the user, clearing the §31 pause. The S1 UI-test count started at 88,
  where there were none before it; later increments increased it.
  Tests get their own `tsconfig.test.json` in the build: test code needs Node's types and application
  code must not have them.
- The **YAML editor stays**, behind *Write it as YAML*. Removing it would turn the studio's gaps into
  walls.
- A run's credentials never reach the screen: a cURL command carrying a bearer token and a session
  cookie imported as one journey with two findings, and **grep found no byte of either in the
  rendered page**.

### Not done, and owned by later increments

- **The team name is asked for rather than filled in.** Nothing reports the caller's own team —
  `TeamDirectory` maps name → id and not back, and no endpoint exposes it. S5 closed credentials and
  sessions; this now belongs with the later identity/V1-removal audit.
- **The status page is unchanged.** Elapsed, current stage and a progress bar are S2, along with the
  report's novice rewrite.
- **`web-experience` was choosable but not runnable in S1.** S6 added the browser-only Playwright
  engine foundation and Core Web Vitals objective support.

---

## 3o. S2 — the report a novice can read, and run progress

**The report now opens with the answer, and a running test says how much longer.** Verified against a
real JMeter run of 2,221 requests, not a fixture.

### The answer, in a sentence

> **This test did not pass: 2 of 2 targets were missed.**
> Error rate · s2-report-check-load: 1.485% of 2221 requests failed, against a ceiling of 1%.
> Response time · s2-report-check-load: p95 was 584ms against a ceiling of 400ms.
> 2,221 requests were sent, reaching 5 users at once. 1.49% failed.
> The slowest journey was s2-report-check-load/submit-payment, at 584 ms.

Everything below it is unchanged — the expert report is still the one that settles arguments.
`narrative.ts` is a pure function, so what it will say is testable without rendering anything, and
the tests pin the property that matters: **it can never read as more reassuring than the data.** A
synthetic run says so first and quotes no figure at all; an indeterminate verdict never becomes a
pass; a run missing a shard says that before any verdict.

### Two things a real run found that the tests did not

1. **An error-rate breach would have read "1485.00% of requests failed".** `ObjectiveOutcome`'s
   `observed` and `threshold` are **scaled per metric** — an error rate arrives in thousandths, so
   1.485% is the number 1485 against 1000 for a 1% ceiling. Formatting them client-side meant
   knowing each metric's scale and keeping that knowledge current as metrics are added. Fixed by
   using the server's own `detail` string, which already words it correctly: the server computes the
   verdict and the sentence explaining it in one place, so using both makes them impossible to
   disagree.
2. **Two different p95s appeared on one screen.** The summary quoted the whole-test aggregate
   (533 ms) directly above an objective quoting 584 ms. Both are right — a workload-scoped objective
   takes the **highest** percentile among its journeys, so a workload passes only if every journey
   would have (`ObjectiveEvaluator.combine`, argued in its own javadoc) — but a reader who cannot
   reconcile two numbers stops trusting both. The summary now omits its latency clause when an
   objective already reports one. **Each number appears in exactly one place.**

### Progress, without a runner change

`progress.ts` derives where a run is from the plan's stage arithmetic and the recorded `startedAt` —
data the platform already holds. Verified live mid-run: *"Holding steady — 5 users · about 20 seconds
to go · 50 seconds elapsed of a planned 1 minute 10 seconds"*, `aria-valuenow="71"`, and
`aria-valuetext` carrying the whole sentence rather than a bare percentage.

**It stops claiming a percentage when the percentage stops meaning anything.** A plan knows its
stages; it does not know about image pulls or a target that will not answer. Once a run outlasts its
plan the bar holds and the label changes to *"Taking longer than planned"* — a progress bar that lies
at the end is worse than none. Clock skew between the run's recorded start and the browser is floored
at zero, so a negative-width bar is impossible.

Live throughput, latency and error rate are a different thing and need a runner that publishes
progress. That was **S7**, which changed the runner image — see §3w.

### Explanations where the numbers are

Every column of the latency table carries what it means — "nineteen in twenty requests were faster
than this" rather than "the 95th percentile", available on hover and to a screen reader. A glossary
is a page nobody opens, and the moment somebody needs to know what a 95th percentile is, is the
moment they are looking at one.

### Not done

- **No baseline comparison yet.** The report's structure is shaped to take one (S12).
- **The Browser pane's screenshot capture stopped responding** during verification. Every claim above
  is from reading the rendered DOM, which is stronger evidence anyway; there are no screenshots of
  the final report.

---

## 3p. S3 — journeys and correlation

**A test can now sign in.** A scenario used to be one request, so there was nowhere to put the second
call and no way to hand it what the first returned. `ScenarioSpec` now carries ordered `steps`, each
with its own request, extractions, expectations and think time.

### Proven with real load, both ways round

Two journeys against the demo service, identical except for one JSON path:

| | Samples | Errors | Outcome |
|---|---|---|---|
| `$.status` — matches | 108 | 0 (0%) | **COMPLETED** |
| `$.noSuchField` — never matches | 106 | 53, **100% of the extracting step** | **FAILED**, `OBJECTIVE_BREACHED` |

The second is the one that matters. Without the sentinel guard it would have reported **0% errors
and 30 ms p95** — the demo service answers 200 to `/api/users?echo=PERF_NOT_EXTRACTED_token` exactly
as happily as to a real value. A perfectly healthy-looking test of a system that was never
authenticated. Note where the failure lands: on `sign-in`, the step that failed to extract, not on
the downstream step that received the garbage.

### The sentinel is not optional and not configurable

When a pattern does not match, JMeter substitutes the post-processor's default and **sends the next
request anyway**. So every extraction renders two elements, never one: the post-processor with a
sentinel default (`PERF_NOT_EXTRACTED_{name}`), and a `ResponseAssertion` in variable scope with
`test_type` 12 — EQUALS plus NOT — that fails the sample when the sentinel survived. An extraction
whose failure is silent is worse than no extraction at all, because the run still produces numbers.

### Two defects found by using it rather than by testing it

1. **Every journey rule was dead code.** An early return for journeys sat *above* the call that ran
   them, so a journey validated clean whatever it contained — including a step carrying an
   `Authorization` header. Found by validating a document that should have been refused and watching
   it pass. Steps were a new way to write a request, and a new way to write a request is a new way
   past a guard that only knew about the old one. Now pinned by `JourneyValidationTest`.
2. **The credential guard made correlation useless for its main purpose.** `Authorization` is refused
   by name whatever its value — but `Bearer ${session}` stores no secret. It is a placeholder for a
   value extracted during the run, and the document is exactly as safe to store with it as without.
   The guard now permits a credential-named header whose value is a reference to something the
   platform supplies, and only that: the reference must resolve (enforced separately), and the text
   around it is still scanned, so `Bearer eyJ…${session}` is still refused. A reference does not
   launder a secret beside it.

### The rules

- A step may use only what an **earlier** step extracted, or a dataset column. A forward reference is
  an ERROR, verified live: removing an extraction makes the later `${basketId}` refuse with
  *"This step uses ${basketId}, which nothing has provided yet."*
- An extraction name colliding with a dataset column is an ERROR — two sources for one reference
  would make which value a request used depend on evaluation order.
- A body pattern must capture **exactly one** group. Zero extracts nothing; two is ambiguous and the
  engine would silently pick one.
- A scenario stating both a single request and steps is refused rather than resolved by precedence.

### Digests are preserved

Steps are written to the canonical form **only when present**, a deliberate departure from the
`.count`-always convention. There is no ambiguity to guard against here, and the payoff is that every
specification written before journeys existed keeps its digest — unlike 2g, which changed all of
them. Two documents differing only by a step digest differently, verified.

### Closed by later S3 surfaces

- ~~**The HAR importer still emits sibling scenarios, not ordered steps.**~~ Closed in §3q. A capture
  now becomes one ordered journey per page.
- ~~**No studio Rules screen.**~~ Closed in §3r. Journeys can now be reviewed by clicking, with
  correlation suggestions accepted or rejected explicitly.

### A gap this uncovered, pre-existing and not fixed here

`SecretValueDetector`'s credential-name list is header-shaped: `x-api-key` is refused but `api_key`,
`access_token` and `token` are not, so `?api_key=short-value` passes as a query parameter. A query
string reaches every access log between the runner and the target, so the list arguably wants the
query forms too. Left alone deliberately — widening it is a change to what every existing document
validates as, not a side effect of adding journeys.

---

## 3q. S3 — the HAR importer builds journeys

**A capture becomes one journey per page, steps in the order the session made them.** Verified over
HTTP with a capture carrying a plaintext password, a bearer token, a session cookie and an analytics
beacon: one journey named `checkout`, three ordered steps, and **grep found no byte of any secret in
the response**.

```
journey: checkout  weight=100  steps=3
   post-api-login   POST  /api/login    headers=[Content-Type]  body=no     ← password refused whole
   api-basket       GET   /api/basket   headers=[Accept]                    ← Authorization, Cookie stripped
   post-api-orders  POST  /api/orders   headers=[Content-Type]  body=yes
```

### Two warnings inverted in place, never deleted

- `import.har.journeys.notOrdered` → **`import.har.journeys.correlationNeeded`**. It used to say
  order was not preserved, which was true when a scenario was one request. Order is preserved now.
  What is not is **correlation**: the importer can see a response carried an identifier and a later
  request used one, but not that they are the same thing, and guessing would wire the journey to the
  wrong value. So no step ever arrives with an extraction the importer invented — pinned by a test.
- `import.har.duplicates.collapsed` is gone, because nothing collapses any more.

### Repeats are kept, and that is a correction

Sibling scenarios collapsed repeats and raised a weight instead, because independent scenarios needed
the ratio to live somewhere. **A sequence carries its own ratio**: the basket was fetched twice — once
before signing in and once after — so the journey fetches it twice. Collapsing it back would both
change what runs and quietly halve the traffic the capture observed for that call.

The second gets a distinct name (`api-basket-2`) because a step name becomes a result label
(`workload/scenario/step`). Two steps sharing one would have their samples merged, and a cold fetch
and a signed-in fetch are worth telling apart.

### Weights stopped coming from observed frequency, deliberately

`import.har.weights.observed` → **`import.har.weights.equal`**. A capture cannot say how often each
*journey* ran — one session usually visits each page once — and a page that happens to make twenty
calls is a heavy page, not a popular one. Weighting it twenty times higher would describe traffic
nobody observed. The per-request frequency is not lost; it moved into the sequence, which is a more
faithful place for it.

### Truncation changed direction

Was "keep the most frequently observed", which is right for independent scenarios and wrong for a
sequence: dropping requests out of the middle leaves a journey that never happened, and the steps
around the gap may depend on what was removed. It now keeps the head in order — a shorter journey
that is at least a real prefix of the session.

### The API boundary

`ImportResponse.ImportedScenario` gained `steps`. Without it a journey would have arrived at the
studio as a scenario with no method and no path — the same mistake `operationId` nearly made in 2c,
and harder to spot here because the object would still have looked valid. A journey also reports a
null method rather than the `GET` default, which would describe a request it does not make.

### Closed by §3r

The studio now has a Rules screen. Journeys are importable, expressible and editable by clicking:
correlation suggestions show evidence, and verification plus think time can be set per step.

## 3r. S3 — the studio Rules screen

**Correlation is now proposed, never silently applied.** A HAR import returns correlation suggestions
beside its ordered journeys. Each suggestion names the journey, the proposed variable, the source
response that produced the value, the extraction expression and every later request location where
the captured value was reused.

The studio's Rules screen shows that evidence and requires a person to **Accept** or **Reject** each
proposal. Pending and rejected suggestions are kept as review state but do not alter the generated
specification. Only accepted suggestions materialize as step `extract` rules and `${variable}`
replacements in later paths, query parameters, headers or bodies.

### Why the importer still does not wire correlations itself

The importer can prove only that the same observed value appeared in an earlier response and a later
request. It cannot prove that a future response will provide the same semantic value, and it cannot
know whether a person wants that value correlated or deliberately fixed. The evidence is therefore
surfaced for review, not converted into executable behavior behind the user's back.

### What the Rules screen also covers

- **Verification** — each imported step can add an expected status assertion.
- **Think time** — each step can carry a fixed pause in seconds.
- **Journey preview** — the Refine step now distinguishes imported ordered journeys from old
  single-request scenarios, and the YAML/specification emitters preserve those ordered steps.

### Guardrails

- Token- and credential-shaped response values are not proposed as suggestions, and credential-named
  response headers are skipped.
- Response bodies used for evidence are read only to discover candidate scalar values; they are not
  imported into the specification.
- The UI applies no correlation while a suggestion remains pending.

### Deliberately left for later

- **Parameterization-by-clicking** is still not in the studio. Dataset binding already exists in the
  API/YAML path; choosing a request value and binding it to a dataset column remains a studio UX
  follow-up.
- ~~**The Validator is still next.**~~ Closed in §3s. The studio now runs one live protocol
  iteration and shows whether correlations and assertions actually resolve.

## 3s. S4 — the Validator

**The studio can now try one real protocol iteration before saving a test.** `POST
/api/v2/test-definitions/validator` validates the submitted specification, authorizes the caller for
the specification's team, resolves the target through the same server-side `TargetResolver` used by
runs, and sends one ordered pass through each protocol journey.

This is deliberately not a run:

- No test definition, run, shard, artifact or result row is created.
- No admission slot is consumed.
- No execution provider or runner Job is provisioned.
- The response is direct evidence for the current draft only.

### What it reports

Each journey returns every executed step with:

- the final request URL and body preview;
- response status, headers and body preview;
- extraction outcomes, including whether a JSON/header/cookie/status/regex extraction resolved;
- assertion outcomes, including observed status/body evidence;
- the failing step's message when validation stops early.

Only protocol workloads execute in S4. Browser validation waits for S6, when browser execution and
Core Web Vitals exist.

### Guardrails

- The endpoint creates nothing and runs only after structural and semantic validation pass.
- A caller needs `runs:execute` for the owning team because the Validator makes real requests.
- Target resolution stays server-side; a specification still cannot choose its own host.
- Per-team requests are rate-limited in memory to prevent rapid repeat clicks from becoming a small
  load test.
- Evidence previews redact credential-shaped values rather than echoing complete tokens.

### Studio surface

The wizard now has a **Validate** step between Objectives and Save. It runs the endpoint, renders the
journey/step evidence, and stores the result against the exact generated YAML. The Save button is
disabled until the current document has a successful Validator result, so changing a rule or journey
after validation requires another live check.

### Deliberately left for S6

- Runtime credential references (`run://`, `env://`) and session pools are closed in §3t.
- Browser journey validation is S6.
- The full reactor could not be re-run in the current dirty worktree because unrelated student-demo
  changes modified `specification/examples/valid/demo-service.yaml` to `/api/students/1` while
  `SpecificationValidationServiceTest.preservesManualRequests` still expects `/api/users` and
  `/api/payments`. Focused S4 backend/UI checks are green.

---

## 3t. S5 — targets, credentials, sessions

**S5 is complete through the production session-delivery slice.** The earlier S5 commit added
self-service target registration and runtime credential management. The follow-up S5c–S5f work added
secure production delivery for `run://session`, lifecycle cleanup, a real provider adapter seam and
run-facing operational documentation.

### What shipped

- `run://session` no longer requires a runtime-supplied credential in production. The trigger
  references only the immutable definition version; the configured provider acquires leases.
- `SessionAcquisitionProvider` is the seam for real login/refresh acquisition, with
  `ExternalSessionBrokerSessionAcquisitionProvider` as the approved production implementation.
- `SecretResolver` resolves provider client credentials by reference. The bundled resolver supports
  mounted Kubernetes Secret files such as `k8s://session-broker-client/token`.
- The control plane partitions acquired sessions per shard and delivers them as `sessions.csv` via
  run-scoped Kubernetes Secret volumes mounted read-only into `/workspace`.
- Runtime credential Secrets and session-file Secrets are deleted on terminal observation or
  cancellation, and the Spring lifecycle sweeper deletes runner-managed Secrets whose explicit
  delete-after annotation has elapsed.
- `ArtifactStore.delete(...)` gives production cleanup an object-store port instead of relying on
  provider-specific side effects.
- Helm exposes lifecycle cleanup, session acquisition, mounted Secret resolver settings and
  namespace-scoped runner RBAC for ephemeral Secrets.
- Run-facing docs now describe broker-backed `run://session`, mounted Secret layout, Helm production
  values and broker request/response contracts.

### Operational docs and examples

- `docs/operations/run-session-acquisition.md` — operator flow and mounted Secret layout.
- `docs/operations/session-broker-contract.md` — acquire/refresh request and response contract.
- `helm/performance-platform/examples/session-broker-production-values.yaml` — production-like
  values overlay with references and names only.
- `specification/examples/valid/session-broker-api.yaml` — consumer-facing YAML example.

### Verification

- `mvn test -pl platform-orchestration -am` — passed during S5e.
- `mvn clean test -pl platform-core -am -Dtest=ProductionSafetyGuardTest -Dsurefire.failIfNoSpecifiedTests=false` — passed during S5e.
- `mvn test -pl runners/runner-kubernetes -am -Dtest=KubernetesExecutionProviderTest -Dsurefire.failIfNoSpecifiedTests=false` — passed; the first sandboxed attempt failed because Fabric8's mock server could not bind a local socket, then passed with approved local socket permission.
- `mvn test -pl platform-api -am -Dtest='SpecificationValidationServiceTest$ValidExamples#validExamplesValidate' -Dsurefire.failIfNoSpecifiedTests=false` — passed, including `session-broker-api.yaml`.
- `helm template perf helm/performance-platform -f helm/performance-platform/examples/session-broker-production-values.yaml` — passed.
- `mvn test-compile` — passed across all modules.
- `git diff --check` — passed.

### Deliberately left for S6 and later

- Browser execution and Core Web Vitals were picked up in S6. Browser dry-run validation remains
  future work.
- Corporate OIDC and non-Kubernetes secret managers remain pause-condition decisions.
- A production broker and real broker client Secret were not deployed; S5 proves the adapter
  contract, mounted reference path and Helm wiring.

---

## 3u. S6a — broker path production-readiness validation

**S6a is complete as a production-readiness validation slice for broker-backed `run://session`.**
It did not implement the browser engine; it closed the operational validation gap before S6.

### What shipped

- `docs/operations/kind-session-broker-smoke.md` — a repeatable kind-level smoke procedure that adds
  a stub broker Service, a mounted broker-client Secret, a broker-backed Helm override, a short
  authenticated smoke specification and checks for runner session Secret creation and cleanup.
- `docs/operations/run-session-acquisition.md` now links that smoke and includes an operational
  runbook for failed acquisition: provider settings, mounted Secret resolution, broker reachability
  and contract errors, runner session Secret delivery and lifecycle cleanup.
- `docs/operations/local-kubernetes-run.md` now points operators from the base Kubernetes smoke to
  the broker-specific smoke when changing session acquisition or runner Secret lifecycle code.

### Acceptance coverage

- **Stub broker Service:** the kind smoke defines an in-cluster `session-broker` Service that checks
  the broker-client bearer credential, logs operation/run/correlation evidence and returns valid
  session leases.
- **Mounted client Secret:** the smoke creates `session-broker-client` out of band and installs the
  chart with `secretResolver.mountedSecrets` plus `clientCredentialSecretRef:
  k8s://session-broker-client/token`.
- **Runner session Secret creation:** the smoke checks for `perf-<run-prefix>-0-sessions`, its
  delete-after annotation and the Job's read-only `/workspace/sessions.csv` mount.
- **Cleanup:** the smoke checks normal terminal cleanup and documents how to force the TTL sweeper
  path by scaling the control plane down until the delete-after timestamp has passed.
- **No secret leakage:** the smoke explicitly greps Deployment and Job YAML for the synthetic broker
  token and synthetic session value; both are expected to be absent.

### Verification

- `JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home mvn -B -f pom.xml test-compile` — passed.
- Focused broker/orchestration and runner tests:
  `mvn -B -f pom.xml test -pl platform-orchestration,runners/runner-kubernetes,platform-core -am -Dtest=ExternalSessionBrokerSessionAcquisitionProviderTest,MountedFileSecretResolverTest,RunReconcilerTest,KubernetesExecutionProviderTest,RunnerJobManifestTest,KubernetesEphemeralSecretCleanerTest,ProductionSafetyGuardTest,RunnerLifecycleCleanupSchedulerTest -Dsurefire.failIfNoSpecifiedTests=false`.
  First sandbox run failed because Fabric8's mock server could not bind a socket. Rerun with approved
  local socket access passed orchestration and runner tests; `ProductionSafetyGuardTest` then hit
  stale bytecode and passed after a clean focused run.
- `mvn -B -f pom.xml clean test -pl platform-core -am -Dtest=ProductionSafetyGuardTest -Dsurefire.failIfNoSpecifiedTests=false` — passed.
- `helm template perf helm/performance-platform -f helm/performance-platform/examples/session-broker-production-values.yaml` — passed.
- `helm template perf helm/performance-platform -f /tmp/perf-session-broker-values.yaml --show-only templates/deployment.yaml` — passed for the exact kind smoke values from the new guide.

### Not run

- The full kind smoke was not executed in this session. Docker is available, but no kind cluster
  existed; creating the cluster, building/loading images and deploying support services remains the
  operator-level procedure documented in `docs/operations/local-kubernetes-run.md` and
  `docs/operations/kind-session-broker-smoke.md`.

### Closed by S6

- Browser execution and Core Web Vitals were picked up in §3v.
- No real enterprise broker, real broker client credential or production target was introduced.

---

## 3v. S6 — browser engine and Core Web Vitals

**S6 is complete for browser-only Playwright execution behind the engine SPI and Core Web Vitals
objective evaluation.** It keeps release 1 to one workload, one engine and one plan; mixed-engine
hybrid execution is now rejected explicitly until the S9/S11 multi-plan work.

### What shipped

- `engines/engine-playwright` adds `PlaywrightEngineProvider` with the pinned
  `mcr.microsoft.com/playwright:v1.50.0-noble` runner image, browser capabilities, browser-user
  policy validation, deterministic plan compilation and runner shard rendering.
- `PlaywrightResultParser` normalizes journey duration, step duration, error rows and web-vital
  samples from the runner CSV contract into `NormalizedRunResult`.
- `SourceSpec` now carries an optional immutable `sha256:` digest, and Playwright sources may be
  artifact-backed by `uri` plus digest instead of git coordinates. Git-pinned import stays S10.
- Browser workloads now carry `browserProfile` (`device`, `network`, `cpuThrottle`), canonicalized
  into the specification digest and validated semantically.
- `ObjectiveEvaluator`, schema examples and UI labels now understand browser journey/step metrics,
  `browser.error.rate`, `web.lcp`, `web.inp`, `web.cls`, `web.ttfb`, `web.tbt` and
  `web.lighthouse.score`. Score thresholds use the explicit `score` unit.
- `EngineDispatchingPlanCompiler` now refuses mixed-engine or hybrid specifications instead of
  silently compiling only the first engine.
- `runner-images/playwright-runner` defines the pinned Playwright/Lighthouse runner image and output
  contract. It emits `results.csv`, `runner-manifest.json` and a summary artifact shape; the material
  script execution wrapper still needs cluster calibration before production use.

### Verification

- `JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home mvn -B -pl platform-domain,platform-engine-spi,engines/engine-playwright,platform-results,platform-orchestration,platform-api,platform-core -am test-compile` — passed.
- `mvn -B -pl platform-api -am -Dtest=SpecificationValidationServiceTest,SchemaConformanceTest -Dsurefire.failIfNoSpecifiedTests=false test` — passed, 79 parser/schema tests.
- `mvn -B -pl platform-domain,engines/engine-playwright,platform-results,platform-orchestration,platform-core -am -Dtest=SpecValueObjectsTest,SemanticValidatorTest,PlaywrightEngineProviderTest,RunAnalysisTest,EngineDispatchingPlanCompilerTest,ModuleDependencyRulesTest,ModuleWiringTest -Dsurefire.failIfNoSpecifiedTests=false test` — passed.
- `yarn test` in `platform-ui` — passed, 133 tests.

### Not run

- A broad affected `mvn test` was attempted and reached known environment limits in this sandbox:
  Mockito inline cannot self-attach to the Homebrew JDK 21 VM, and dry-run tests that start local
  sockets fail with `Socket Operation not permitted`.
- The Playwright runner image was not built, loaded into kind or run against a live browser target in
  this session.

### Deliberately left for S7 and later

- ~~Live progress snapshots from runners are S7.~~ Built — §3w.
- ~~Arrival-rate sizing is S8.~~ Built — §3x.
- ~~Multi-plan hybrid orchestration is S9/S11.~~ Built — §3y and §3ad.
- Git-pinned Playwright import and the recorder runtime bridge remain S10.

---

## 3w. S7 — live monitoring

**S7 is complete.** A run that is still executing now shows throughput, latency, error rate, a
per-journey breakdown, each load generator's own last word, its objectives judged live, and
rule-based observations that each carry the measurement behind them. Before this a run showed a
state and nothing else: `Running` was true for twenty minutes and said nothing about whether the
error rate was climbing or whether the generators were still alive.

**The full contract is [`docs/operations/live-monitoring.md`](docs/operations/live-monitoring.md).**
What follows is why it is shaped this way.

### The runner publishes a file; nothing calls back

Every five seconds each runner reduces what it has produced so far and PUTs `progress.json` under its
own shard prefix. The reconciler lists, decodes, verifies and merges those on each pass.

A callback would have been less code. It was rejected because a runner holds **no control-plane
credential and no database access, by design** — a provider able to write run state could report its
own failure as a success (§3e). It already holds object-storage credentials, so publishing there
costs nothing new and keeps that property intact.

The consequence is three implementations of one document: Python in the JMeter image, Node in the
Playwright image, and Java in the local development provider, which owns its results file rather than
a pod. That cost is paid deliberately and pinned: the JMeter reducer's real output is committed
verbatim as a decoder fixture (`platform-orchestration/src/test/resources/progress/`), so the two
halves are tied to each other even though they have not yet run together on a cluster.

### A runner never reports a percentile

It reports latency **bucket counts**. Percentiles do not merge — the p95 of two shards is neither the
mean nor the larger of their p95s — so a shard reporting one would force the control plane either to
average them, producing a number no sample supports, or to discard the other shard. Bucket counts
add exactly.

So a live percentile is an **upper bound**: the top of the bucket the sample fell in, never
interpolated. `LatencyHistogram` says so, the API says so, and the console's tile says so. This is
the same refusal as everywhere else in the platform — a plausible number nothing supports is worse
than a coarser one that is true.

The bucket edges travel inside the document rather than being assumed, because runner images are
released on their own schedule and a decoder that assumed the current edges would silently misread an
older image's snapshot and report confident, wrong latencies. Mismatched edges are refused for
merging, never re-bucketed.

### The per-generator block exists because merged totals cannot show a loss

Two shards' totals added together look identical whether both are running or one died three minutes
ago — the survivor keeps adding to them, so a run that lost half its load generation reads as a run
that is merely slower. Only the per-shard capture time exposes it, so it survives the merge, reaches
the API, and gets its own table and its own `RUNNER_SILENT` observation.

### Live objectives use the same evaluator as the verdict

`RunAnalyzer.judge(...)` was added so the live view and the final verdict share one definition of a
breach. A live warning computed by a looser rule would eventually disagree with the run's own verdict
and destroy the credibility of both. They are labelled `provisionalObjectives`, and the caveat is
repeated inside each outcome's detail text because a screenshot of one row is what actually travels.

### Nothing about live monitoring can fail a run

Storage unreadable, an object caught mid-overwrite, a document from an unknown image — each costs a
point on a chart, and none may stop a run reaching a terminal state. A run that could not finish
because a chart could not be drawn would be a strictly worse platform than one with no chart. This is
asserted directly (`RunReconcilerTest.LiveProgressCollection.unreadableSnapshotDoesNotStopTheRun`).

### The interpretation layer is rules, never a model

Eleven codes, each an explicit threshold over an observed value, each carrying that value. The
audience is somebody who has never run a load test and cannot tell a confident wrong statement from
a right one, so the platform must not be capable of making one. It does not predict, extrapolate to a
finish, or estimate whether a run will pass — a live view exists so a person can decide whether to
keep watching, and a forecast would replace their decision with arithmetic that has no basis in how
the system behaves at the next stage.

`ERRORS_RISING` earns its place: cumulative error rate is slow to move, so a system that started
failing outright a minute ago still shows 3% overall while it fails 40% of what it is currently
asked. Every rule is pinned by a test naming its observation.

### Two pre-existing failures found and fixed

Neither is S7 work; both blocked verifying S7, and both had been invisible.

**The Spring context could not start.** Two beans matched `TargetResolver` — the explicit one and the
registry store, since `TargetRegistry extends TargetResolver` — so `platform-core` refused to start
and `./scripts/start-platform.sh` failed at the control plane. Introduced when S5 added the registry;
never noticed because no session started the application afterwards. Fixed by marking the explicit
bean `@Primary`.

**Seventeen end-to-end tests were failing underneath it.** The context failure masked them. Since S5
made target registration mandatory, `RunLifecycleEndToEndTest` had been provisioning runs for a team
with no registered target and failing with `PROVISIONING_FAILED` — the platform behaving correctly.
The test now registers one explicitly rather than relying on YAML rows the truncate removes.

Together these are why the branch got its first fully green `mvn clean verify` at S7: **1,058 tests,
0 failures, 0 skipped**, across all 15 modules with Testcontainers.

### Verified with real load

`local-jmeter` against the bundled demo service, 105-second runs: samples, error rate, throughput,
per-journey breakdown and active users (read from JMeter's `allThreads`, so it reports what is
actually applying load rather than what the stage asked for) all arrive and advance. The rules fired
on real behaviour — the demo service fails its `update-student` path, which produced
`ERROR_RATE_CRITICAL` and `OBJECTIVE_AT_RISK` with correct evidence.

**Freshness: 11–14 seconds**, against the plan's ~15-second target. Getting there needed the
collection call to happen twice per pass — once before the shards are inspected, so a pass that finds
them all finished still merges the final snapshot, and once after, because the local provider
publishes from inside `inspect`. Recording is idempotent by capture instant, so for a Kubernetes
runner the second call is a no-op.

The console was verified in a browser against a live run: the Monitor renders, the freshness badge
reads `Live`, sparklines draw once the series has points, and the observations render at their
severities.

### Deliberately not done

- **Server-Sent Events.** Polling for now; the merged snapshots are durable either way, so the
  transport can change without altering what a client can ask for or resume from.
- **Retention for `run_progress`.** Rows accumulate at ~360 per hour per active run and nothing prunes
  them. `ProgressCatalog.clear(runId)` exists; nothing calls it on a schedule. Recorded in
  `live-monitoring.md` §7.
- **Cluster verification.** The Python and Node reducers and both entrypoint loops have not run in a
  pod. `docs/operations/local-kubernetes-run.md` §7 is the procedure, including the two-shard
  generator-kill check that is the only real test of `RUNNER_SILENT`.

---

## 3x. S8 — load profiles: rate as well as users

**S8 is complete.** A test can now hold a number of **requests per second** instead of a number of
users, and staged concurrency **glides** through its profile instead of stepping at every boundary.
Both were verified against a real JMeter with the plugin installed, through the local provider, with
real load.

**The contract is [`docs/operations/load-models.md`](docs/operations/load-models.md).** What follows
is why it is shaped this way.

### Two models, because they answer different questions

A closed model (`stages`) holds users, and throughput is an *output*: if the system slows down the
users send less, so the applied load falls with the system's health. An open model (`arrival-rate`)
holds a rate, and throughput is an *input*. Only the second can ask "can it sustain 400 a second?",
and only the second shows a stall for what it was — the closed model's well-known blind spot is that
a stalled system receives less traffic during the stall and the test under-reports it.

A stage sets the field its model reads and **not the other one**, refused rather than ignored.
Whoever writes `targetRate: 400` under a staged model believes the run will send 400 a second; it
will send whatever 50 users produce, and they would read the report against an expectation the
platform never agreed to.

### `maxConcurrency` is mandatory, and admission reads it rather than the rate

In an open model a stalled target accumulates in-flight requests without bound: at 400/s, ten seconds
of stall is four thousand requests. The generator exhausts its threads or heap, dies, and the run
reports the generator's failure as the target's. There is no safe default — the right ceiling depends
on the rate and on how slow the target may get — so a specification without one is refused.

Admission budgets against the ceiling because **a rate says nothing about how much of a generator a
test occupies**: 500/s against a 20 ms endpoint is about ten concurrent requests, and against a 2 s
endpoint it is a thousand. Budgeting against the rate would let a heavy test through as free and hold
a light one back as enormous.

Validation warns when the ceiling implies a response-time budget under 100 ms (Little's law:
`C / R` seconds), stating the figure rather than prescribing a ceiling — only the author knows how
fast their endpoint is. A warning and not an error, because throttling deliberately is legitimate.

### Both models render through the Custom Thread Groups plugin

Closed models became one vanilla thread group per stage, each starting from zero, so concurrency
stepped at every boundary. They now become **one Ultimate Thread Group** whose layers sum to the
requested curve ({@code ConcurrencyProfile}); open models become **one Free-Form Arrivals Thread
Group** whose schedule rows map one-to-one onto the stages.

The decomposition walks the stages carrying a stack of layers: a rise pushes the difference, a fall
pops newest-first and splits the top layer when it holds more than the fall removes, and whatever is
still standing at the end stops immediately. Newest-first is about the story rather than the
arithmetic — closing the oldest layer gives the right total at every instant while claiming the users
who arrived for a spike were the ones that stayed.

**This changed what existing numbers mean at stage transitions**, which the plan required be said out
loud. Steady-state figures are unaffected; runs either side of this change are not directly
comparable while load is moving.

### The plugin is now a hard requirement, and the control plane refuses to start without it

`jpgc-casutg` was already in the runner image and unused (the outstanding `ROADMAP.md` item, now
closed). A local development JMeter usually does not have it, so `./scripts/start-platform.sh`
installs it, and `LocalJmeterExecutionProvider.requireCustomThreadGroups` refuses startup otherwise.

A refusal rather than a warning because **JMeter does not fail on an element it cannot resolve** — it
logs, skips the thread group, and exits successfully. A missing plugin would present as a run that
completed having generated no load, which is the single worst outcome this platform can produce. The
check searches both `lib/ext` and `libexec/lib/ext` after resolving symlinks, because a package
manager's `jmeter` is usually a wrapper somewhere else entirely (found the hard way: the first
version refused to start on a machine where the plugin was correctly installed).

### A throughput objective was unwritable, and now is not

`http.throughput` has been an evaluated metric since 1D, but `Threshold` accepted only `ms`, `s`, `%`
and `score` — so `380/s` was refused and the studio's own throughput objective produced an invalid
document. `Unit.PER_SECOND` fixes it. This stops being optional in an open model: when the rate is an
input, failing to *achieve* it is the finding, and an objective is the only place a run can say so.

### Extending the canonical form without invalidating every stored digest

The open model's two fields are emitted into the canonical form **only for an open model**, so a
closed-model document canonicalizes byte-for-byte as before and every stored definition version still
verifies against its recorded digest. Safe against collision because the discriminator is inside the
canonical form one line above: a reader knows from `.model.type` which fields to expect. Conditional
on the *type*, never on whether a value is present — that distinction is what keeps "absent" and
"empty" from colliding everywhere else in that encoding.

### Verified with real load

- **Open model**, demo service, 20s→20/s, 40s→60/s, 15s→0: **2,239 samples against ~2,250
  specified** (0.5%), with the live rate climbing 17 → 23 → 34.6 → 46.1 → 51.9/s and the throughput
  objective passing at 30/s against a 25/s floor.
- **Closed model**, the shipped demo specification: active users climbed **1 → 3 → 4 → 5 → 6 → 7 → 8
  → 9** and back down. The old rendering would have jumped 5 → 10 at the boundary. The glide is
  visible in S7's live view, which is a pleasant accident of doing them in this order.
- Both thread-group elements were validated by running candidate plans through JMeter and checking
  observed behaviour — arrival counts and the active-thread curve — rather than by reading plugin
  documentation.

### Deliberately not done

- **Arrival rate for browser workloads.** A browser cohort is a fixed number of real browsers
  measuring the experience; a rate of browser journeys would be a request for a number of machines.
- **Per-scenario rates.** A rate applies to the workload and scenarios divide it by weight.
- **Cluster verification.** As with S7, the runner image path is unexercised.

---

## 3y. S9 — one run, many workloads

**S9 is complete for the structural change.** A run now compiles **one plan per workload**, provisions
shards per workload, and analyses every workload's results together — so a specification declaring a
protocol load and a browser cohort produces two plans for two engines instead of being refused.

### The defect this closes, in three stages

Before S6, a multi-engine specification had its later workloads **silently dropped**: the run
executed the first workload's engine, reported success, and nothing anywhere said half the test had
not run. S6 stopped the silence by refusing such a document at compile time — inside the reconciler,
as a failed run, minutes after the trigger and long after the version had been stored as immutable.
S9 makes it work, and moves the remaining refusal to the earliest possible moment.

**The acceptance criterion** — a specification whose second workload names an engine this deployment
does not have is refused at *validation* — is met by a new fourth validation layer,
`EngineCapabilityValidator`, reached through the `EngineCatalog` port. It is a deployment fact rather
than a property of the document, which is why it is its own layer and why the validator is told the
answer rather than reaching for a registry.

### What changed structurally

- **`ExecutionPlan` gains `workloadName`**, and it is part of a plan's identity rather than a label:
  it tells two plans of one run apart, names the shards that execute it, and scopes the samples it
  produces.
- **`execution_plans` gains `workload_name` and `run_id`** (Flyway `V9`), unique on
  `(run_id, workload_name)`. That key is what makes provisioning idempotent — a reconciler that
  recompiled after a crash cannot create a second plan for a workload that already has one, and
  therefore cannot start a second set of runners.
- **`runner_shards` gains `workload_name`**, and its unique key moves from `(run_id, shard_index)` to
  `(run_id, workload_name, shard_index)`. The old key is precisely what would have refused a hybrid
  run's second workload, silently reducing it to one.
- **The SPI compiles a named workload** (`compile(specification, workloadName)`). The specification
  is still passed whole, because a workload's scenarios reference sources, datasets and
  authentication declared elsewhere in it.
- **`ExecutionPlanRepository.findByRun`** is the authority on what a run executes. A set carried on
  the run as well would diverge the first time a pass was interrupted between writing them.
- **Analysis merges across engines.** Each workload's results are parsed by its own engine and the
  samples are merged before objectives are judged — which works because a browser journey duration
  and an HTTP response time are both a labelled latency by the time they leave a parser. That is
  what `Sample` was designed for, and this is the first thing to rely on it.

The alternative — analysing each workload separately and merging verdicts — was rejected because an
objective scoped to the whole test (`scope: *`) would then have no single result to be judged
against, and the platform would have had to invent a rule for combining two verdicts.

### Decisions worth not relitigating

**A stored result names every engine behind it** (`jmeter+playwright`), not the first. Read months
later, a result naming only one would say a browser cohort had never been part of the run.

**A manifest's digest must match one of the run's plans**, not the one. Membership rather than
equality, because a hybrid run's manifests come from different runners executing different plans —
the check is otherwise unchanged, and a manifest matching none of them still describes a test this
run did not commission.

**One failed workload fails the whole run.** A run half of whose load never started measures a test
nobody described, and reporting its numbers would state a result for a smaller test than the one
that was asked for.

**The deadline is the longest plan's, not the first.** Workloads run concurrently with different
durations — a browser cohort measuring a five-minute protocol load runs for a fraction of it — so
stopping at the shortest would kill a run doing exactly what it was asked to.

**`test_runs.execution_plan_id` is kept**, pointing at the first plan. Not the authority on anything;
it stays because the V2 API has always exposed `executionPlanId`, and a run whose plans existed while
that field stayed null would be a silent break for anything already reading it.

### Consequence worth knowing: more runners

Two protocol workloads on the same engine now compile to two plans and therefore two runners, where
before they shared one. That is the point — separately provisioned workloads are what S11's start
conditions need — but it is a real change in pod count for any specification with more than one
workload.

### Deliberately not done

- ~~**The browser-session budget.**~~ Built in S11 at trigger time, with a default ceiling of 20
  browser sessions per run.
- **A hybrid report.** The report still describes the first plan's execution profile. Nothing
  regresses for a single-workload run; describing several workloads in one coherent report remains
  later report work.
- ~~**Coordinated start.**~~ Built in S11: `startWhen` resolves named stages and provisions delayed
  browser shards only after protocol load is running and producing samples.

---

## 3z. S10 gate — recorder security review

**The required S10 security review is complete, but the live recorder runtime is not built.** The
review lives in [`docs/architecture/recorder-security-review.md`](docs/architecture/recorder-security-review.md)
and is now the binding gate for S10 implementation.

The main decision: a server-side recording browser is an interactive network client in the runner
network, so it is an internal open proxy unless constrained at two layers. S10 must implement both a
browser-level route guard and an enforced network layer (`NetworkPolicy` where CIDRs are enough, or
an egress proxy when hostname policy is required). Target locking must come from the registered
target and explicitly declared asset/login domains; redirects, iframes, popups, workers, fetch,
WebSockets and EventSource all need the same decision.

The second decision: a human SSO session captured during recording is authoring evidence only. It
must not be promoted into material run credentials. Real authenticated runs continue to use
`run://session`, scoped service identities or another approved test-account/session provider.

The required S10 build order is:

1. Recorder security foundation: target allowlists, address policy, route guard, enforced egress,
   recorder pod manifest contract and audit events.
2. Capture sanitizer: request/response capture, draft artifact storage, generated Playwright script
   stripping and non-promotion of human sessions.
3. CDP bridge and UI.
4. Git-pinned Playwright import.

Do not add a clickable recorder button until steps 1 and 2 are test-covered.

---

## 3aa. S10a — recorder route-policy foundation

**S10a is complete as a domain-only security foundation slice.** It does not create recorder pods,
does not expose CDP, does not add UI and does not persist captured content. It gives the rest of S10
a pure, testable policy decision for "may this recording browser contact this destination?"

### What changed

- Added `com.perfplatform.domain.recorder`:
  - `RecorderSessionPolicy` freezes the registered target origin plus explicitly declared asset and
    login origins for one recorder session.
  - `RecorderRouteGuard` evaluates every browser request surface — navigation, subresource, popup,
    iframe, worker, fetch, WebSocket and EventSource — against that frozen policy.
  - `RecorderAddressPolicy` blocks recorder-specific prohibited addresses: loopback, link-local,
    metadata endpoints, wildcard, multicast and deployment-configured CIDR ranges.
  - `CidrBlock` provides standard-library-only CIDR matching for the future egress policy/proxy
    renderer.
  - `RecorderOrigin`, `RecorderRequestKind` and `RecorderDecision` carry normalized origins, request
    surfaces and auditable allow/block decisions.
- Added recorder scopes to `Scope`: `recordings:create`, `recordings:read`, `recordings:cancel`.

### Decisions

**Recorder loopback is blocked even though ordinary load-test loopback remains allowed.** `TargetPolicy`
continues to permit loopback for local development and the demo service. The recorder policy is
stricter because a user-driven browser hitting loopback reaches services in the recorder pod, not a
team application.

**Private target IPs are allowed only through registration.** `10.x` application addresses are not
globally blocked, because lower environments are internal. The control is that they must be the
registered target or an explicitly declared asset/login origin with frozen resolved addresses.

**Raw IP origins are allowed only for the registered target.** Asset and login origins must be
named hosts. This keeps "just browse to this internal IP" from becoming the escape hatch around
target ownership.

**Hostname policy and network policy are deliberately separated.** The domain guard decides by
origin and frozen resolved address. S10b renders matching network enforcement; browser code alone is
not enough.

### Tests added

- `CidrBlockTest` — exact and prefix matching.
- `RecorderAddressPolicyTest` — approved private target, loopback, metadata literal/alias,
  link-local, Kubernetes-style prohibited CIDR and unresolved destinations.
- `RecorderRouteGuardTest` — registered target, asset/login allowlists, redirects/subresources
  outside the allowlist, popups/iframes/workers/fetch/WebSockets/EventSource, raw IP rules, blocked
  local schemes and embedded URL credentials.
- `ScopeTest` — recorder scopes stay distinct from `runs:execute`.

### Verification

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  mvn -B -pl platform-domain test
```

Result: **350 tests, 0 failures, 0 skipped**.

Full reactor verification also passed after rerunning outside the sandbox because the sandbox denied
Mockito self-attach and test socket binding:

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  mvn -B -f pom.xml clean verify
```

Result: **BUILD SUCCESS** across all 15 modules.

### Still not done

- Audit event model for blocked destinations.
- Capture sanitization.
- CDP bridge and UI.
- Git-pinned Playwright importer.

---

## 3ab. S10b — recorder pod and egress-rendering foundation

**S10b is complete as a Kubernetes manifest and startup-safety slice.** It still does not expose a
recorder API, CDP bridge, UI button or capture pipeline. It gives the next S10 slice a tested
recorder pod contract and an enforced egress object to create when a session starts.

### What changed

- Added `RecorderSessionId`.
- Added `RecorderCellSettings`, `RecorderSessionRequest` and `RecorderSessionManifests` in
  `runner-kubernetes`.
- The recorder Job contract is separate from load runners:
  - `backoffLimit: 0`, `restartPolicy: Never`, `activeDeadlineSeconds` and finished-Job TTL.
  - Dedicated recorder service account.
  - `automountServiceAccountToken: false`.
  - Non-root pod/container, runtime-default seccomp, read-only root filesystem, no privilege
    escalation and all capabilities dropped.
  - Size-limited `emptyDir` workspace.
  - Identifiers and an allowlist digest in metadata/env; no credential material.
- When network enforcement is enabled, the renderer emits a per-session Kubernetes `NetworkPolicy`
  selecting only that recorder Pod and allowing egress only to the policy's frozen destination IPs
  and ports.
- Added `platform.orchestration.recorder.network-enforcement.enabled`, default `true`.
- Local administrators may set it `false` for development clusters without a NetworkPolicy
  controller. Production, `prod` and `shared-runner` profiles refuse to start when it is false.
- Helm passes the setting into the control plane and grants the control plane `networkpolicies`
  create/get/list/watch/delete in the runner namespace.

### Decisions

**The local bypass is explicit and noisy.** Disabling network enforcement is not hidden behind test
code. It is the deployment property
`platform.orchestration.recorder.network-enforcement.enabled=false`, and startup logs that the
recorder has only browser-layer route guards.

**Production still fails closed.** The user approved a local testing escape hatch, not a production
policy relaxation. Any production-like profile with enforcement disabled fails before a recorder
endpoint can be exposed.

**NetworkPolicy is CIDR/IP based.** Kubernetes cannot enforce hostnames, so S10a's frozen
`RecorderSessionPolicy` remains the authority. The renderer consumes resolved addresses only; it
does not perform DNS resolution or expand redirects. The eventual recorder runtime must either use
the same frozen host-to-address mapping when launching the browser or add an explicit, reviewed DNS
egress exception.

### Tests added

- `RecorderSessionManifestsTest` — recorder Job confinement, no retries, deadlines, resource
  bounds, no service-account token, no obvious credential material, per-session egress policy from
  frozen IPs and disabled local mode with no policy.
- `ProductionSafetyGuardTest` — recorder network enforcement is enabled by default, can be disabled
  locally and is refused under production.

### Verification

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  mvn -B -pl platform-core -Dtest=ProductionSafetyGuardTest test
```

Result: **28 tests, 0 failures, 0 skipped**.

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  mvn -B -pl platform-domain,runners/runner-kubernetes,platform-core test
```

Result: the new recorder manifest tests passed, but the sandboxed run stopped on existing Fabric8
mock-server tests because the sandbox denied local socket binding. Full reactor verification was
rerun outside the sandbox.

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  mvn -B -f pom.xml clean verify
```

Result: **BUILD SUCCESS** across all 15 modules, **1,115 tests, 0 failures, 0 skipped**.

### Still not done

- CDP bridge and live recorder browser stream.
- Draft artifact persistence from sanitized captures.
- Git-pinned Playwright importer.

## 3ac. S10c — recorder audit, capture sanitizer and safe console entry

**S10c is complete as the recorder audit/sanitization and safe-entry slice.** It still does not
create a CDP bridge or stream a live browser. That is deliberate: the console button now creates a
governed session that is target-bound and auditable, but reports `PENDING_BROWSER_BRIDGE` until the
guarded runtime bridge is built.

Implemented:

- Added `RecorderAuditEvent`, `RecorderAuditEventType` and `RecorderAuditLog`.
- Added durable `recorder_audit_events` migrations for PostgreSQL and H2; PostgreSQL rows are
  append-only with update/delete triggers.
- Added `RecorderCaptureSanitizer`, which removes credential headers, token-shaped header values,
  credentials embedded in URLs, browser storage state, and whole bodies when field names indicate
  credentials or personal data.
- Added `/api/v2/recordings`, `/api/v2/recordings/{sessionId}/sanitize` and
  `/api/v2/recordings/{sessionId}/events`.
- Added `recordings:create/read/cancel` to the development caller provider only. Production still
  refuses that provider.
- Added a console recorder strip that starts from registered targets only and displays the pending
  session status.

Decision recorded: **lower-environment credentials are still removed from captures**. They may be
acceptable in local secret stores, but they do not belong in versioned authoring material, API
responses, logs, audit details or generated draft specs. The sanitizer therefore removes them before
anything can be rendered or converted.

Tests added:

- `RecorderCaptureSanitizerTest`
- `RecorderAuditEventTest`
- `ManageRecordingsUseCaseTest`
- `RecorderControllerTest`

Verification:

- `mvn -B -f pom.xml -DskipTests package` — passed across all 15 modules.
- `mvn -B -pl platform-domain,platform-orchestration -am test -DskipITs` — passed, including the
  recorder sanitizer/audit/use-case tests and the hybrid reconciler/budget tests.
- `yarn --cwd platform-ui test` — passed, 158 tests.
- `yarn --cwd platform-ui build` — passed.
- Full `mvn -B -f pom.xml clean verify` — **BUILD SUCCESS** across all 15 modules, **1,148 tests, 0
  failures, 0 skipped**, Testcontainers included.

## 3ag. S10d — the guarded CDP bridge

**S10d is complete.** A recorder session now attaches a real browser, streams it to the console and
accepts interaction. `PENDING_BROWSER_BRIDGE` stops being the only outcome and becomes what it
always should have meant: *this deployment has no browser configured*, with the reason attached.

### The shape

One port, `RecorderBrowser`, with two intended adapters — exactly the split CLAUDE.md §15 requires
of JMeter. `LocalChromeRecorderBrowser` is the development one and is refused under a production
profile. The recorder-cell adapter reuses the port; nothing in orchestration knows which it has.

`UnavailableRecorderBrowser` is the fallback rather than null, so "no browser" is a describable
state and the console can say *why* instead of showing a wait that never ends.

### Why the user cannot reach the protocol

`RecorderInputKind` is a closed enum of ten interactions. `ChromeInputCommands` is a pure function
from that enum to a DevTools call, and `ChromeInputCommandsTest` asserts that the image of that
function is a five-element set — no `Runtime.evaluate`, no file access, no target attachment. An
unknown `kind` fails Bean Validation at the boundary. Going back is a history lookup rather than
`history.back()` precisely so that no brokered input becomes script execution.

### Three independent egress controls, and why

1. **Chrome's own resolver** is pinned: `--host-resolver-rules` maps each allowed host to the address
   frozen at session start and everything else to `~NOTFOUND`, with `--no-proxy-server` so nothing
   resolves names on the browser's behalf. This is the DNS-rebinding control (review §4.2).
2. **The route guard** pauses every request through `Fetch.requestPaused` and refuses what is outside
   the allowlist, which is what makes a block *visible* to the user.
3. **NetworkPolicy**, in a cell, from the same frozen addresses — the one that holds if the adapter
   has a bug.

Known gap, stated rather than hidden: the `Fetch` domain does not pause WebSocket handshakes, so a
WebSocket to an unlisted origin is caught by (1) and (3) but produces no blocked-destination event.

### The loopback allowance — a real relaxation, deliberately visible

`RecorderAddressPolicy` denies loopback, which made the recorder impossible to exercise against the
demo service on `http://localhost:8081`. Rather than weaken the floor, S10d adds
`RecorderAddressAllowances` — a value object, so a relaxation is a thing you can see and test rather
than a missing check. It permits loopback and nothing else; metadata, link-local, wildcard, multicast
and prohibited ranges stay denied, and `RecorderAddressAllowancesTest` pins that. Production profiles
refuse it, the session's audit detail records `addressFloor=relaxed-local-development`, and startup
logs a warning.

### Two threading traps, both real

- **`WebSocket.sendText` may not overlap.** A user's input and a page's routing decision are
  concurrent in the normal case, so sends are serialized through one thread in
  `ChromeDevToolsClient`.
- **A blocking `send` from an event handler deadlocks**, because the reply is delivered by the thread
  that is waiting. Routing decisions and screencast acks use `fire` (no reply); reading a response
  body hands off to an executor. `LiveRecorderSession` closes its handle *outside* its own lock for
  the same class of reason.

### Orphaned browsers

Found by killing the control plane during verification: the JVM died and Chrome did not. Two halves
now cover it — a `@PreDestroy` closes live sessions on orderly shutdown, and the adapter arms its own
deadline *inside* the session so a `kill -9` bounds the orphan to the session deadline rather than
forever.

### Streaming

Polling, not SSE. `EventSource` cannot set headers, so an SSE stream would carry the API key in the
query string. Frames are sequenced so a poll is resumable, and only the newest frame is returned —
showing someone the past while they click the present is worse than dropping frames.

Frames and captured bodies are **never durable**: they live in bounded in-memory buffers and are
dropped with the session (review §5). The audit trail is what survives. Blocked destinations are
audited **by origin, deduplicated by origin-and-reason** — a page retrying a blocked asset sixty
times is one security fact, and a blocked SSO callback carries a grant code in its query string.

### Files

- `platform-domain` — `RecorderSessionState`, `RecorderInputKind`, `RecorderInputEvent`,
  `RecorderMouseButton`, `RecorderViewport`, `RecorderScreencastFrame`,
  `RecorderBlockedDestination`, `RecorderInputRateLimiter`, `RecorderAddressAllowances`;
  ports `RecorderBrowser`, `RecorderBrowserListener`, `HostAddressResolver`.
- `platform-orchestration` — `LiveRecorderSession`, `RecorderSessionRegistry`,
  `RecorderBridgeSettings`, `UnavailableRecorderBrowser`; `ManageRecordingsUseCase` rewritten.
- `runners/runner-chrome` — **new module**. `LocalChromeRecorderBrowser`, `ChromeDevToolsClient`,
  `ChromeProcess`, `ChromeCommandLine`, `ChromeInputCommands`, `ChromeResourceTypes`,
  `ChromeRecorderSettings`. No new dependency: the WebSocket is `java.net.http`, Jackson was already
  in the reactor.
- `platform-api` — `GET /{id}`, `GET /{id}/live`, `POST /{id}/input`, `POST /{id}/stop`; new problem
  types for a refused target (422), refused input (409) and exhausted capacity (429).
- `platform-core` — `recorder.local-browser.*` properties, bean wiring, two production guards,
  `RecorderSessionScheduler`, `RecorderSessionShutdown`.
- `platform-ui` — `RecorderPanel.tsx` plus client methods and styles.

### Verification

Full `mvn -B -f pom.xml clean verify` — **BUILD SUCCESS**, 16 modules, **1,264 tests, 0 failures**.
One skip, and it is not a false green: `LocalChromeRecorderBrowserLiveTest` is `@EnabledIfSystemProperty`
and was run separately —

```bash
mvn -B -pl runners/runner-chrome -am test -Drecorder.live=true
```

— 35 green, driving a real Chrome against a real HTTP server: screencast frames, the page's own
`fetch` captured, and both refusals (raw-IP and outside-allowlist) observed through Chrome's
interception. That test caught two defects that unit tests could not: `Target.createTarget` rejects
width/height without a new window, and the raw-IP rule fires before the allowlist rule.

`platform-ui`: 168 tests, build clean, lint at the documented 3-errors/3-warnings baseline (all
pre-existing in V1's `App.tsx`).

End to end against the running platform, which is the check that matters: session `LIVE` not pending,
frames arriving, `GET /` and `GET /api/users` captured, `https://example.com` refused with 409 and
audited as `origin=https://example.com` with no query string, `kind: "Runtime.evaluate"` refused 400,
`stop` returning `CANCELLED` and leaving no Chrome behind. Confirmed in the console too.

## 3ah. S10e — a recording becomes a draft test

**S10e is complete.** The captures a recording produces now become draft scenarios in the studio.
That closes the gap S10d left: the bridge worked, but a recording rendered as a list and nothing
promoted it.

### The whole increment is a format conversion, and that is the point

A recording **is** a capture, and HAR is the format captures come in. So `RecorderHarRenderer` turns
a session's sanitized exchanges into a HAR 1.2 document and hands it to the ordinary `HarImporter`.
What that buys, none of it written twice:

- static-asset stripping, third-party host removal, failed-exchange dropping
- negotiated and session-header removal (including the conditional headers that would make a target
  answer 304 and report excellent latency for doing nothing)
- journey grouping, with weights derived from observed frequency
- correlation suggestions with evidence, feeding the Rules screen S3 built

A second importer reading captures directly would have had to reproduce all of it and would have
drifted from it the first time either changed. The conversion is a dozen field mappings.

### Two fields had to survive the sanitizer

`RecorderCapture` and `RecorderSanitizedCapture` now carry `status` and `journeyRef`. Neither is
sensitive and both are load-bearing: one decides whether an exchange is worth replaying, the other
groups requests into journeys. The Chrome adapter records the status from `Network.responseReceived`
and increments a journey counter on each **main-frame** `Page.frameNavigated` — an iframe is not a
new journey. The journey is stamped when the request *starts*, so a slow call is not filed under
whichever page happened to be current when it returned.

### A status of 0 is not a success

A recorded status of zero means the browser never reported one. Rendering it as 200 would smuggle an
exchange nobody observed complete into the draft; it stays zero and the importer drops it. Pinned by
a test in both the renderer and the round trip.

### Journeys are named for what they did

The renderer titles each HAR page with that journey's first URL. The importer already strips scheme
and host from a title — real captures title pages with URLs — so `page-2` becomes `api-students`.
Without it every draft arrived as `page-1`, `page-2`, `page-3` for a person to rename by hand.

### Truncation is reported, not hidden

The capture buffer drops oldest-first, and a recording's oldest requests are its sign-in. When it has
bitten, the draft carries a `recorder.draft.truncated` warning saying how many are missing and that
the sign-in is the likely casualty. A draft silently missing the journey someone cared about would
look complete.

### The studio opens on Refine

`Studio` takes an optional `seed`. With one it skips the start screen and the sources screen, because
both questions are already answered — the person chose what to record and then recorded it. Asking
them to pick a test type and a source afterwards would be asking them to repeat themselves.

Drafting creates nothing and works on a **live** session: someone who has finished the journey they
care about should not have to stop the browser to see what the platform made of it.

### Files

- `platform-domain` — `RecorderCapture`, `RecorderSanitizedCapture` (+`status`, +`journeyRef`),
  `RecorderCaptureSanitizer` passes both through.
- `platform-orchestration` — **`RecorderHarRenderer`**; `ManageRecordingsUseCase.draft`;
  `LiveRecorderSession.allCaptures()` and `droppedCaptures()`.
- `runners/runner-chrome` — main-frame navigation tracking and response status.
- `platform-api` — `GET /api/v2/recordings/{id}/draft`, returning the same `ImportResponse` a HAR
  import returns.
- `platform-core` — the HAR importer selected from the beans Spring already has, and refused loudly
  if absent.
- `platform-ui` — `recordingDraft`, a *Turn this into a test* action, `Studio`'s `seed` prop.
- `docs/operations/recording-locally.md` — the whole local procedure.

### Verification

Full `mvn -B -f pom.xml clean verify` — **BUILD SUCCESS**, 16 modules, **1,291 tests, 0 failures**
(one skip: the opt-in live Chrome test, §3ag). UI 171 tests, build clean, lint at the documented
baseline.

`RecordingToDraftTest` in the composition root is the test that matters: a real recording through the
**real** `HarImporter`, asserting ordered journeys, preserved query strings, dropped assets, dropped
failures, dropped unobserved statuses, no host, no credentials and no `page-N` names. The
orchestration tests check what is handed *over*; this checks what comes *back*, and it is what would
fail if renderer and importer ever drifted.

End to end against the running platform: recorded `/api/students`, `/api/students?page=1` and
`/actuator/health`, drafted to two journeys named `api-students` and `actuator-health` with the query
preserved, no host in the scenarios, and a `DRAFT_CREATED` audit row carrying counts only.

**A defect the live run found, and it was not ours.** The first attempt drafted nothing: the demo
service returns 500 on `/`, `/api/users` and `/api/courses` — its real endpoint is `/api/students`.
The pipeline correctly refused all four failed exchanges and said so. Worth knowing when
demonstrating this.

## 3ai. S10f — four defects found by actually using it

Everything below was found by running the recorder against a real target, not by testing. Recorded
here because each one passed its unit tests.

### The console hammered a dead session

`RecorderPanel`'s poll loop rescheduled at 120ms unconditionally on failure and called `onError`
every time. When a session ended and was released, that became roughly **eight 404s a second,
forever**, each re-raising the error banner. It now backs off exponentially, gives up after five
consecutive failures, and treats a 404 as "this recording ended" rather than as a retryable error —
because it is not retryable, and saying so is both truthful and what stops the storm.

### An ended session answered 404 instead of saying it expired

`sweep()` evicted terminal sessions immediately, so a client polling one moment was told the session
never existed the next. Terminal sessions are now retained for ten minutes, so the last poll returns
`EXPIRED` or `CANCELLED` **with its reason**. The eviction still happens; it just waits until nobody
could still be looking.

### A dev server on `[::1]` was unreachable

`--host-resolver-rules` `MAP` takes one replacement, so a host resolving to several addresses could
only be pinned to one — and `localhost` resolves to both families. Chrome was pinned to
`127.0.0.1` while Vite listens on `[::1]` only, so every recording of the student app died with
`ERR_CONNECTION_REFUSED` while `curl` succeeded.

A loopback host under the **relaxed** floor is now `EXCLUDE`d from remapping instead, letting Chrome
resolve it normally and try both families. That is not a hole: it is emitted only when the allowances
already permit loopback and every resolved address is loopback, both of which the address floor has
already accepted. The general single-address limitation is now documented on the method rather than
left to be rediscovered.

### The demo service reported every 404 as a 500

`@ExceptionHandler(Exception.class)` caught Spring's own `NoResourceFoundException` and
`HttpRequestMethodNotSupportedException`, so an ordinary unmapped path came back 500. That made the
platform look broken while it was reporting accurately — and worse, a draft **drops failed
exchanges**, so recording a healthy service produced nothing. The catch-all now honours any
exception that implements `ErrorResponse` and is otherwise unchanged.

The demo service is also API-only, so a recording opened on a bare error page. It now serves a small
landing page at `/` with links, because a recorder needs something to click.

### And the thing that was not a defect

`start-platform.sh` never started `demo-student-ui`, so a target pointing at `:5174` refused the
connection — correctly. It now starts it alongside the rest. The student app is the better recording
subject anyway: a real browser client whose clicks produce exactly the API calls a journey is made
of. Readiness for the demo service also moved from `/api/users`, which has never existed, to
`/actuator/health`.

### Verification

Full `mvn -B -f pom.xml clean verify` — **BUILD SUCCESS**, **1,300 tests, 0 failures** (one skip: the
opt-in live Chrome test). UI 173 tests.

End to end, which is the only reason any of this was found: recorded the student app at `:5174`,
saw it render with real data, captured `GET /` and two `GET /api/students`, drafted two journeys
(`home`, `api-students`) and watched the studio open on **Review journeys** with both loaded.

## 3aj. S10g — the live view was a busy poll, and the page could not scroll

Both found by a person using it, not by a test.

### Two thousand requests to say nothing

The panel polled `/live` every 120ms whatever was happening. Chrome emits a screencast frame **only
when the page repaints**, so someone reading a static page produces none — and the client then spent
the whole session asking for them. Measured: **68 requests in 10 seconds on an idle page, every one
returning no frame**; roughly two thousand over a five-minute recording.

It also made the recorder look broken in two directions at once. The network tab showed a runaway
loop, and the surface looked frozen when it was simply a page that was not repainting.

`/live` now **long-polls**: `awaitUpdate` blocks on the session monitor until there is a frame, a
block, a capture or a terminal state, bounded by `waitMillis` and capped at 20s by the API layer.
`wait()` releases the monitor, which is the whole point — the browser's callback threads need it to
deliver the very frame the request is waiting for.

Measured after: **1 request in the same 10 seconds**, and a wait released **0.09s** after a reload.
A client that is already behind is answered immediately rather than made to wait, so catching up
stays fast.

### The recorded page could not be scrolled

`MOUSE_WHEEL` was in the vocabulary, `ChromeInputCommands` mapped it to
`Input.dispatchMouseEvent`, and both were tested. The panel simply never sent one — there was no
wheel handler. Everything below the fold was unreachable.

The listener has to be attached **natively with `passive: false`**. React registers wheel handlers
passively, where `preventDefault` is ignored, so the console would have scrolled behind the surface
while the recorded page stayed put. Line- and page-mode deltas are converted to pixels, because a
trackpad does not always report pixels and Chrome's input domain expects them.

Verified against real Chrome: six wheel events moved the student list from the top to student #24,
and frames advanced 7 → 33 as it repainted.

### "Turn this into a test" looked like it did nothing

It opens the studio — which renders **above** the recorder panel, so from where the button is, the
page did not visibly change. The studio is now scrolled into view, and the notice says what is
actually true: nothing has been created, review it and press **Save test**, after which it appears
under *Your tests* with a Run button.

That gap was the documentation's too, and `docs/operations/recording-locally.md` now has a
"Drive the browser" section covering scrolling and repaint behaviour, and answers "then how do I run
it?" explicitly.

### Verification

`mvn -B -f pom.xml clean verify` — **BUILD SUCCESS**, **1,304 tests, 0 failures** (one skip: the
opt-in live Chrome test). UI **176 tests**.

Four new orchestration tests pin the wait: it blocks until a frame arrives, gives up on time rather
than hanging, is released immediately when the session ends, and does not make an already-behind
client wait. Three UI tests pin the wheel, including that the event is actually `preventDefault`ed —
"a handler ran" would have passed while the feature stayed broken.

## 3ad. S11 — web performance under load

**S11 is complete.** Hybrid web performance now coordinates a browser workload under an active
protocol workload instead of provisioning every workload immediately.

Implemented:

- `LoadStage` has an optional `name`, parsed from YAML/JSON, included in canonical digests and
  published in the schema.
- The hybrid example names `ramp-up`, `steady-state` and `ramp-down`; `startWhen.stage` is validated
  against those names.
- Playwright no longer rejects `startWhen`; it carries start-condition metadata into its compiled
  plan.
- `RunReconciler` creates delayed browser shards as durable `PENDING` rows and provisions them only
  when the depended workload's shards are `RUNNING`, the named stage boundary has elapsed, and live
  progress shows samples.
- `WORKLOAD_DEFERRED` and `WORKLOAD_START_ELIGIBLE` run events make hybrid timing auditable.
- Trigger-time browser-session budget enforcement rejects over-budget browser cohorts before queueing;
  default ceiling is `platform.orchestration.capacity.max-browser-sessions-per-run=20`.

Tests added:

- Hybrid coordination coverage in `RunReconcilerTest`.
- Browser-session budget coverage in `TriggerTestRunUseCaseTest`.

Verification: same S10c/S11 verification set above.

---

## 3ae. Downloading the generated specification

The Validate step offers **Download YAML** beside *Run Validator*, and shows the same collapsed
read-only preview the Review step has. The studio is one of three ways to author one document
(CLAUDE.md §32); this is what lets someone who described a test by clicking take the result to Git or
to a colleague who works in YAML.

What downloads is `toSpecification(draft)` — the same text the studio would POST, not a second
rendering. A file that differed from what was saved would be worse than no file, because the
difference would be invisible.

- `platform-ui/src/v2/studio/download.ts` — `specificationFileName` and `downloadSpecification`.
  `window.document`, not a bare `document`: the step components bind a local `document` to the
  generated specification, and the global would be shadowed at the call site.
- Falls back to `performance-test.yaml`, because Validate is reachable before Review asks for a name
  and a bare `.yaml` is a hidden file.

Tests: `download.test.ts` (3). Verified in the browser — the click produced an anchor with
`download="performance-test.yaml"` and a blob URL, no console errors.

**Known limitation, and it is not this button's:** on a `web-experience` draft the download is still a
`type: protocol` / `engine: jmeter` document, because `toSpecification` hard-codes those. See §3af.

## 3af. The studio cannot author a browser test — open gap

**`toSpecification` always emits `type: protocol` and `engine: jmeter`, whatever `draft.testType`
says.** The *Test web performance* path changes step labels and the Load step's wording
(`StepScreens.tsx` — `const browser = draft.testType === 'web-experience'`), and nothing else. A test
described through it saves as a JMeter protocol test.

The backend is not the blocker — S6 built browser execution, `browser-only.yaml` parses, Core Web
Vitals evaluate. Three things are missing above it:

1. `toSpecification` has no `type: browser` branch: no `browserUsers`, `iterationsPerUser`,
   `browserProfile`, no `sourceRef` to a Playwright source.
2. `ObjectiveMetric` in `draft.ts` is only the three HTTP metrics. No `web.lcp`, `web.cls`,
   `browser.journey.duration`.
3. **No ingestion path for a Playwright script.** `SourceType.PLAYWRIGHT` exists and `SourceSpec`
   carries `uri` + `digest`, but `/api/v2/imports` handles only curl, openapi, har and postman, and
   there is no artifact-upload endpoint. `artifact://playwright-sources/...` in the example refers to
   something no API can currently create — so the YAML path cannot author a browser test either.

Until (3) is closed, browser testing is reachable only by placing an artifact in the store out of
band. This is the S6-UI slice that S6 deferred, and it should be sized as an increment of its own.

---

## 3ak. S10h — the Validator can now prove a recording's correlations

**The recorder's authoring loop used to end in an unchecked claim.** The importer proposes a
correlation with evidence (§3r), a person accepts it, the draft is saved — and nothing ever checked
the accepted rule. S4's Validator ran the journey, watched the extraction produce a value, watched
every assertion pass, and reported success.

**It would have reported exactly the same thing had the rule been applied to nothing.** The literal
the rule was meant to replace is usually still valid a few minutes after it was recorded: a session
token, an order id nothing has cleaned up yet. So a journey that still hard-codes the captured value
gets a clean 200 from every step. The rule is then discovered at load, as an error rate, by somebody
reading a report — the failure mode CLAUDE.md guards against in six other places, arriving from a
seventh direction.

### The three questions, all answerable from one iteration

| Question | No | Yes |
|---|---|---|
| Did a value come out? | `not-extracted` — the step already fails | ↓ |
| Does any later step send it? | `unused` — **ERROR**, the rule is dead | ↓ |
| Is it different from what the recording captured? | `unproven` — WARNING | `proven` |

Without a recording to compare against the verdict stops at **`used`**, which is the whole truth
available and no more. That is what a hand-written definition reports, and it is why the ordinary
Validator's behaviour is unchanged in tone.

**`unused` is an ERROR, deliberately.** There is no reading of an accepted rule that changes nothing
under which the journey is correct — accepting a suggestion is what rewrites the downstream steps,
so a rule sent by nothing means the rewrite did not happen. It is worth knowing this newly fails a
saved definition carrying a stray extraction. That is the defect being surfaced, not a regression.

**`unproven` is a WARNING, equally deliberately.** A value that genuinely never changes is a
legitimate reading, and failing Save on it would train people to ignore the panel.

### Where the baseline comes from

A specification no longer holds the literal a correlation replaced — accepting the rule is what
removed it. Only the recording still knows. `CorrelationBaseline.from(ImportResult)` recovers it
from the importer's correlation suggestions, reading `ReplacementEvidence.currentValue` rather than
`ExtractionEvidence.valuePreview`: the preview is truncated for display, while `currentValue` is the
exact string the studio rewrote to `${variable}`, so it is the one thing guaranteed to equal what the
journey used to send.

**Comparison happens on raw values inside the control plane; only a redacted preview is returned.** A
session token is precisely the value most worth proving, so redaction must not weaken the verdict.

### Endpoint

`POST /api/v2/recordings/{sessionId}/validator`. Same live iteration, same authorization, creates
nothing, books no admission slot.

An invalid specification is refused **before** the recording is read. The dry run refuses it anyway,
so fetching somebody's captured session on behalf of a request that was never going to execute would
be an authorized read with no work behind it — and would make a junk body a way to probe which
session ids exist. The guard is on *validity*, not merely on whether the document parsed: a
malformed document still maps to a specification carrying error findings.

### Files

- `platform-domain` — `StepSpec.referencedVariables()`: the `${name}` tokens a step actually sends,
  scanning path, query, headers and body. This is what separates a correlation that feeds the journey
  from one that resolves and is discarded.
- `platform-api/validator/CorrelationBaseline` — the literals, and how to recover them.
- `platform-api/validator/CorrelationProofEvaluator` — the verdict logic, package-private and
  testable without HTTP.
- `platform-api/validator/SpecificationDryRunService` — a second `validate` overload taking a
  baseline; the single-argument form delegates with `CorrelationBaseline.none()`.
- `platform-api/RecordingValidatorController` — the endpoint.
- `platform-ui/studio/CorrelationProofs.tsx` — the panel, in its own file with its own tests.
- `docs/operations/recording-locally.md` §4, `docs/api/v2-examples.md`.

### A pre-existing test defect this uncovered

`TestDefinitionValidatorControllerTest.validDocument()` emitted `type: staged`, which is not a
supported load model — the supported values are `stages` and `arrival-rate`. The test passed anyway
because that controller does not gate on validity and its dry run is a mock. A fixture named
`validDocument` that is not valid is a trap; both fixtures now say `stages`.

### Verification

`mvn -B -f pom.xml clean verify` — **BUILD SUCCESS**, **1,312 tests, 0 failures**, one skip (the
opt-in live Chrome test, as always). UI **184 tests**, lint 3 errors / 3 warnings, all pre-existing
in V1's `App.tsx`.

Thirteen new tests. The one that matters most asserts the combination that was previously invisible:
every step passes, `journey.passed` is `true`, and the run is nonetheless not `valid` because the
correlation was sent by nothing.

### Deliberately not done

- **Not verified against a live recording in a browser.** The logic is covered by tests at every
  layer, but the end-to-end path — record, accept a rule, validate — has not been walked on this
  machine. Worth doing before trusting the verdicts on real material.
- **The studio surface is written and green but deliberately uncommitted.** `api.ts`,
  `StepScreens.tsx`, `Studio.tsx` and `PerformanceConsole.tsx` already carried unrelated in-progress
  work — an **About** step moving name/team/application/environment ahead of Validate, because the
  Validator resolves its target from those and was failing with `metadata.team is required` — and the
  user asked for that to stay in the working tree. The UI half of this increment touches the same
  files, so **this commit is backend and documentation only**; every UI file involved
  (`api.ts`, `StepScreens.tsx`, `Studio.tsx`, `PerformanceConsole.tsx`, `draft.ts`, `studio.css`,
  `CorrelationProofs.tsx`, `CorrelationProofs.test.tsx`) stays uncommitted and lands with the About
  work as one studio commit. Splitting them the other way would have committed a tree that does not
  typecheck, since `CorrelationProofs.tsx` imports types defined in `api.ts`.

  **This is the one increment on this branch that lands API-only in git**, against PLAN.md's rule.
  The screen exists and passes its tests in the working tree; it simply is not in this commit.
- Browser journeys still do not validate; that remains where S4 left it.

---

## 3al. S10i — a git-pinned Playwright source reaches the runner

**S10's last open piece.** The domain already modelled it: `SourceSpec.GitSource` existed,
`SemanticValidator` refused a mutable ref and a `..` path, the JSON schema described the block, and
`mutable-git-ref.yaml` was a published invalid fixture. Two things were missing, and the second was
a defect rather than an absence.

### The defect: validated, accepted, then dropped

`PlaywrightEngineProvider.compile` wrote `sourceUri` and `sourceDigest` and **nothing else**. A
git-pinned source passed validation and compiled into a plan carrying two empty strings. The runner
received a browser workload with no script to run.

That is the silent-drop failure S9 removed for engines, arriving from a different direction, and it
is worse here because everything upstream looks correct: the coordinates were checked, the ref was
proven immutable, the path was proven safe, and the plan is digested and stored as though it
described a test. Compiling is a claim. A workload whose source carries neither an artifact nor git
coordinates is now **refused at compile** rather than emitted empty, and the git coordinates are
part of the plan — and therefore part of the plan digest, so the commit a run executed is
recoverable from the stored definition version. That is the whole point of refusing a mutable ref,
and until now the pinning went no further than validation.

### The absence: nothing constrained the repository

A git source is the **one field in a specification that names a host the platform will contact and
then execute whatever comes back**. Everywhere else the author says *what* and the platform decides
*where* — a test cannot choose its own target host, which is what stops it being pointed somewhere
it should not go (CLAUDE.md §20). A repository URL taken at face value is a hole straight through
that property.

`GitSourceAllowlist` closes it. An operator declares
`platform.orchestration.playwright.allowed-repositories`; a specification chooses among them.

**Empty means nothing is permitted.** This is the opposite of the usual default and it is
deliberate: an empty allowlist is "this deployment has not decided", and the honest answer to a
fetch request under those conditions is no. The finding names the property, so the refusal reads as
a configuration gap rather than as a rejected test. `ProductionSafetyGuardTest` pins the default,
because the mistake would otherwise be invisible until somebody pointed a definition at a repository
nobody had reviewed.

### Credentials in the URL

`https://x-access-token:ghp_live@github.com/org/repo` is a working clone URL and a secret written
into a versioned, digested, findings-echoing document — the one thing CLAUDE.md §20 forbids without
qualification. Refused **before** the allowlist is consulted, so an otherwise-permitted repository
cannot carry one in.

The rule is scheme-aware, and it has to be. Over ssh a bare username is how everybody clones, so
only userinfo carrying a password is a credential; over https there is no legitimate username, so a
lone `ghp_...@github.com` is a token — exactly the shape worth catching. A first version treated any
`@` as a credential and refused `ssh://git@host/...`, the ordinary case, which is how you teach
people the check is wrong.

### The runner

`fetch_git_source` in the Playwright image's entrypoint: shallow-fetch the SHA, check out, then
**verify the resolved `HEAD` equals the SHA it asked for**. `git fetch <sha>` asks a remote for a
commit and what arrives is whatever the remote chose to send; without that check pinning is a
comment. It also refuses when the path does not exist at that commit — a test that silently ran
nothing would report a browser cohort that measured nothing.

`GIT_TERMINAL_PROMPT=0` and `GIT_ASKPASS=/bin/true` before any git command, so a repository needing
a credential the platform did not supply fails in seconds rather than blocking until the Job's
`activeDeadlineSeconds` and reporting as a timeout somebody debugs as a slow target.

### A robustness defect an existing test caught

`SchemaConformanceTest` removes each schema-required field from a valid example and asserts the
server rejects the result. Removing `spec` made `GitSourceValidator` throw a `NullPointerException`:
policy validation runs *alongside* structural validation rather than after it, so it has to tolerate
a half-mapped specification and say nothing rather than throw. Fixed, and worth recording as the
kind of thing only that test would have found.

### Files

- `platform-domain/spec/GitSourceAllowlist` — the allowlist, with spelling normalization so `.git`,
  a trailing slash and host casing are one repository.
- `platform-domain/validation/GitSourceValidator` — policy, deliberately separate from
  `SemanticValidator`: the latter answers "is this document coherent" from the document alone, which
  is what lets it run in a pull request with nothing configured.
- `platform-api/spec/SpecificationValidationService`, `api/v2/config/V2ApiConfiguration` — merged
  into both validation entry points; the fail-closed bean is the default.
- `platform-core/OrchestrationProperties`, `OrchestrationConfiguration` — the property, and a
  startup log for **both** states, because "no test named a repository" and "every test naming one
  was refused" are different situations an operator should not have to guess between.
- `engines/engine-playwright/PlaywrightEngineProvider` — carry-through and the refusal.
- `runner-images/playwright-runner/{Dockerfile,entrypoint.sh,README.md}` — git, and the fetch.
- `specification/examples/valid/browser-git-pinned.yaml`, the JSON schema, `docs/api/v2-examples.md`.

### Verification

`mvn -B -f pom.xml clean verify` — **BUILD SUCCESS**, **1,331 tests, 0 failures**, one skip (the
opt-in live Chrome test). Nineteen new tests across the allowlist, the policy validator, the
validation service and the engine.

### Deliberately not done — read this before calling S10 finished

- **Credentials are not wired.** Only a repository the runner can read anonymously, or through a
  credential already present in its cell, will fetch. A private repository needs a git credential
  resolved through `SecretResolver` (which S5 built) and mounted into the Job. That is the next
  slice and it is not large, but it is not done, so **a private repository does not work today**.
- **The runner path has never been executed.** The entrypoint change shares the caveat every runner
  image change on this branch carries: it has not been built or run on a cluster. The fetch logic is
  shell and is covered by no test.
- **No studio surface**, and this one is blocked rather than skipped. `HANDOFF.md` §3af records that
  `toSpecification` always emits `type: protocol` and `engine: jmeter`, so **the studio cannot
  author a browser test at all** — a git-source form would have nowhere to live. This is the second
  increment in a row to land without its screen, and the browser-authoring gap (§3af) is now the
  thing blocking two increments rather than one. It should be sized next.
- The allowlist matches whole repositories only. No wildcards, no org-level entries. Add them when
  somebody asks, with the prefix-matching bug that usually accompanies them in mind —
  `perf-journeys` must not match `perf-journeys-evil`, which is pinned by a test.

---

## 3am. S10j — a browser journey actually runs, from the console

**Before this, no browser workload could execute anywhere.** Not a gap in one adapter — a gap in all
of them. Triggering a Playwright test from the studio ended in `PROVISIONING_FAILED` with a message
telling the user to use the Kubernetes provider, and the Kubernetes provider could not run one
either: `runner-images/playwright-runner/entrypoint.sh` was an explicit placeholder that wrote a
summary JSON and an empty `results.csv` and never invoked `npx playwright test`. So the platform
compiled browser plans, digested them, admitted them, reserved capacity for them and stored their
artifacts, and there was nothing at the end of the pipe.

### Three defects, one of them platform-wide

**1. One provider for the whole deployment.** `OrchestrationConfiguration.executionProvider` chose a
single `ExecutionProvider` bean, which made *which engine a shard runs* — a property of the shard —
into a property of the deployment. `LocalJmeterExecutionProvider` then rejected everything that was
not JMeter. A hybrid run is the case that proves this cannot hold: CLAUDE.md §21 describes one run
where a JMeter workload holds steady state while a browser cohort walks the UI, and those two shards
cannot both be executed by one engine-specific adapter.

`EngineRoutingExecutionProvider` (`platform-orchestration`) now dispatches by engine. It holds no
policy — admission, quotas and team ownership are all settled before a `ProvisionRequest` exists.
`inspect` and `cancel` resolve through `ExecutionHandle.providerId()` rather than through current
configuration, so a handle read from the database after a control-plane restart goes back to the
adapter that created it; inferring it would let an adapter report `NOT_FOUND` for a browser cohort
that is still open and release its capacity slot while the load continued. A handle whose adapter is
no longer configured reports `UNKNOWN`, never a terminal phase.

**2. `CanonicalJson` emitted invalid JSON.** This is the serious one. `member()` cleared
`needsComma` after writing a key, so *every field after the first was written with no separator* —
`{"a":"1""b":"2"}`. Every execution plan and every rendered workload this platform has ever produced
was unparseable, and every plan digest was taken over a malformed document.

It survived because nothing had ever parsed one. JMeter executes a rendered JMX file and never reads
the plan JSON; the browser runner is the **first consumer that parses it**, and it failed on the
first field. The class had **no test at all** — `CanonicalJsonTest` now covers separators, nesting, a
field following a closed array, escaping, the null-string rule the digest depends on, and
determinism. Worth remembering as a pattern: a serializer with no consumer that parses its output
has no feedback loop, and "it produces a stable digest" is true of malformed bytes too.

**3. `repeatEach` is total, not per worker.** Playwright's `repeatEach` is how many times each *test*
runs, not how many each worker runs. Setting it to `iterationsPerUser` would schedule 2 journeys
across 5 workers: three browsers would open, do nothing and close, and the run would report a cohort
of five having measured two journeys. It is `browserUsers * iterationsPerUser`. (The same mistake is
in `demo-student-ui/playwright.load.config.ts`, whose comment claims 20 journeys from
`workers: 10, repeatEach: 2` — that config produces 2.)

### What now exists

`LocalPlaywrightExecutionProvider` (`engines/engine-playwright`), development-only for the same
reason the local JMeter adapter is, with a lower ceiling because real browsers are heavier than a
protocol generator. It ships two Node scripts as jar resources, copied per shard:

- `perf-runner.mjs` — reads the rendered plan, resolves the source, generates a config, runs
  `npx playwright test`. Node rather than Java because the plan is JSON and Node parses JSON with no
  dependency, keeping a JSON library off the control plane's execution path; it also keeps this file
  recognizably the same procedure as the container entrypoint.
- `perf-reporter.mjs` — a Playwright *reporter*, not a post-processor of the JSON report. The report
  format is Playwright's own and changes between versions; the reporter API is documented. A result
  format that quietly stopped parsing after a dependency bump would present as a run that measured
  nothing.

**Operation labels are load-bearing.** A completed journey is recorded under the workload name
exactly (`browser-experience`); steps go underneath (`browser-experience/<step>`). `ObjectiveEvaluator`
matches an exact scope before a `scope/` prefix, so a journey objective scoped to the workload sees
journey rows only. Label both the same and a journey threshold is judged against a mixture of whole
journeys and individual clicks.

**Source resolution: `workspace://` only.** A path in the runner's own checkout, resolved against
`platform.orchestration.playwright.workspace-root`, refused if it resolves outside that root (checked
via `realpath`, so a symlink cannot escape). It exists so a journey can be executed from the platform
while it is still being written — the alternative is committing and pushing before every trial run,
which is the friction that sends people back to running `npx playwright test` by hand. It is a
**warning** at validation (`spec.source.workspace.notReproducible`), not silent acceptance: the file
can change between two runs of the same immutable definition version, which breaks the promise the
digest makes.

A git-pinned source is **refused** by this adapter rather than fetched. §3al's verification — that
the commit the remote sent is the commit asked for — lives in the container runner. Doing the fetch
here without the verification would make pinning look implemented while it was decorative.

**A failing journey is a result, not a broken shard.** Playwright exits non-zero when an assertion
fails. Treating that as shard failure would discard the timings of every journey that *did* complete,
including the ones that explain the failure. The shard fails only if it recorded nothing; otherwise
the objectives decide the verdict.

### Verified end to end, from the console

`student-crud-browser-workspace` saved through **Write it as YAML** → **Run this**: 5 concurrent
Chromium instances × 2 iterations = **10 journeys, 126 samples, 1/1 shards, `synthetic=false`**.
Generated config carried `baseURL: http://localhost:5174` and
`X-Performance-Test-Run-Id: <runId>` as `extraHTTPHeaders`. The run ended
**Failed / OBJECTIVE_BREACHED** — p95 journey duration 30,082 ms against an 8 s ceiling, because one
journey of ten hit Playwright's 30 s default timeout under a 5-browser cohort. That is a real
finding, not a platform fault, and it is the platform doing its job.

`config/teams/local-dev.yml`: the `student-app` target now points at the UI on **5174**, not the API
on 8081. A browser workload loads pages; a target resolving to a JSON endpoint gives the cohort
nothing to render.

### Known gaps

- **Web vitals are not captured.** `web.lcp`, `web.cls` and friends need an observer injected before
  page load, which a reporter cannot do without changing what the reviewed test runs under. They
  report **INDETERMINATE** — nothing measured them. Deliberate: a value synthesized from step timings
  would look like a web vital, pass a threshold and mean nothing. Capturing them properly needs a
  platform-supplied fixture, which is a decision about altering the test under review.
- **No live progress.** The JMeter adapter publishes a `ShardProgress` snapshot every five seconds;
  this one does not, so a browser run's live view stays empty until it finishes. The reducer is
  engine-neutral, so this is wiring rather than design.
- **The container runner is still a placeholder.** Everything above is the *local* adapter.
  `runner-images/playwright-runner/entrypoint.sh` still does not run Playwright, so **browser
  workloads cannot execute in production**. The `perf-runner.mjs`/`perf-reporter.mjs` pair is written
  to be the thing that image runs; porting it is the obvious next slice.
- **Definitions do not survive a control-plane restart** in this local setup — unrelated to this
  work, but it is why the verification above recreates the definition after each restart.
- **Still no studio surface** (§3af). This is the third increment to land without one, and it is now
  blocking three. `toSpecification` always emits `type: protocol` / `engine: jmeter`, so the studio
  cannot author a browser test — the YAML path is the only way in.

---

## 4. Design decisions made in 1B and 1C (do not relitigate)

### From 1B
- **Domain has no Jackson.** Parsing lives in `platform-api` as explicit `JsonNode` traversal.
- **Unknown fields are rejected**, so a typo cannot run a test at a load nobody chose.
- **No JSON Schema validator library** — decided, not deferred. Closed with `SchemaConformanceTest`.
- **Canonical form is hand-written, not JSON.** Digest input must not depend on a library's formatting.
- **`validate` returns 200 even when invalid.** The findings are the answer.
- **Thresholds require an explicit unit.** Finding codes are a public contract.
- **Immutability is enforced three times**; only the PostgreSQL trigger survives a direct SQL write.

### From 1C
- **Ports live in `platform-domain`, not orchestration.** CLAUDE.md §9 restricts persistence to
  depending on the domain, so a port in orchestration could not be implemented by persistence without
  breaking the rule. Repository interfaces in the domain is standard hexagonal layering anyway.
- **`DRAFT` is not a run state.** It belongs to definitions. Documented in `RunState`'s javadoc, as
  CLAUDE.md §11 requires.
- **Admission happens at claim time, not trigger time.** Capacity at submission says nothing about
  capacity when a slot frees. Trigger-time checks are for what does not change.
- **The global ceiling is serialized by a lock row** (`orchestration_locks`), not a PostgreSQL
  advisory lock, so the same code path works on H2 for local dev. Without serializing, four
  reconcilers each observing three free slots each admit three and the platform runs twelve.
- **`RunTransition` pairs a run with its event.** Transitions return nothing else, so "every
  transition creates an event" is structural rather than a convention someone remembers.
- **V1's table was renamed to `v1_test_runs`** so V2 could take the `test_runs` name CLAUDE.md §12
  specifies. Rename, not reshape. See `docs/migration/v1-to-v2.md` §3.
- **`execution_plans.plan_json` is TEXT, not JSONB.** JSONB reorders object keys, so the text read
  back is not the text written — and the plan digest is taken over those exact bytes. This was caught
  by an integration test; the same latent issue exists for `test_definition_versions.specification_json`
  (1B) but is harmless there today because nothing recomputes that digest. **If anything ever
  recomputes a digest from stored JSON, fix that column first.**
- **`ADMISSION_DEFERRED` is recorded once per run, not once per pass.** A run waiting forty minutes
  would otherwise bury every event that matters. The live reason comes from the status endpoint.
- **Dedicated exceptions, not `IllegalStateException`, for storage rules.** Spring's persistence
  exception translation rewrites `IllegalStateException` into a data-access exception, so the rule
  would reach callers as an internal error. Hence `ImmutablePlanException`, `PlanAlreadyAttachedException`.
- **The dev caller-identity provider maps an API key to its owning team.** Strictly better than V1
  (any valid key authorized every team) without touching V1's own endpoints. Refused under a
  production profile.

---

## 5. Verification commands

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home mvn -B -f pom.xml clean verify
```

```bash
cd platform-ui && npm run lint && npm run build
```

```bash
docker compose -f docker/docker-compose.yml config && helm lint helm/performance-platform
```

Boot check — must serve the 8 V1 paths plus `/api/v2/test-definitions/validate` and the 4 run paths:

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home java -jar platform-core/target/platform-core-1.0.0.jar --server.port=18081
```

**Known-correct oddities — do not "fix" these:**
- `/actuator/health` returns **503** (mail health indicator cannot reach SMTP). Defect **D6**, owned by 1E.
- Unauthenticated calls return **403**, not 401. Defect **D7**, owned by Increment 4. V2 inherits it from the shared filter chain; `RunLifecycleEndToEndTest.v2RequiresAuthentication` asserts it so the gap stays visible.
- A V1 request that throws (e.g. `POST /api/v1/tests/run` with an unknown team) also returns **403**, because the `/error` forward is not in V1's permit-list. Same D7 family.
- UI lint reports **3 errors, 3 warnings** — pre-existing at baseline, all in V1's `App.tsx`, deliberately not fixed.
- The V1 tab logs `Error fetching teams: 403` in the browser console before its own API key is entered. Pre-existing V1 behavior, same D7 family.
- `helm lint` advises `icon is recommended` — pre-existing.
- Startup logs two loud warnings, by design: the **FAKE execution provider** generates no load, and the **DEVELOPMENT caller-identity provider** derives identity from V1 API keys. Both refuse to start under a production profile.
- **A local run ends in `Failed` / `NO_LOAD_GENERATED`, not `Passed`.** This is correct and deliberate — see §3b. Do not "fix" it by making the fake provider claim to generate load.
- UI lint reports **3 errors, 3 warnings**, all pre-existing, all in V1's `App.tsx` (three `any`s and three exhaustive-deps warnings). A previous session recorded 10 errors; that was measured against a **drifted `node_modules`**, not the committed lockfile. Established at the studio increment by `npm ci` in a clean worktree at HEAD. If you see a different number, reinstall before believing it.
- **A background `java -jar` survives `TaskStop`/`preview_stop`.** The shell dies, the JVM does not, and the next start fails with `Port 8080 was already in use` — while `curl` happily answers from the *stale* jar. `lsof -ti:8080 | xargs kill -9` before restarting, or you will verify the wrong build.

---

## 6. Next

**Next:** finish the guarded recorder runtime bridge and sanitized draft persistence, then the
git-pinned Playwright importer. S11's hybrid coordination and browser budget are now built.

Before trusting authenticated production Kubernetes runs, execute the new broker smoke:
`docs/operations/kind-session-broker-smoke.md`. The procedure is documented and the Helm/static
pieces render, but the full kind cluster run was not executed in S6a.

Already done, so **do not** repeat from earlier plans: the report endpoint, artifact download with
per-request authorization and digest verification, persisted run metrics, the Kubernetes provider,
the S3 artifact store, importers, datasets, the studio screens through S6, broker-backed
`run://session` delivery, S6a's production-readiness runbook, browser-only Playwright/Core Web
Vitals support, **S7's live monitoring** — runner snapshots, the merge, the series, the progress
endpoint and the Monitor screen — **S8's load models**: the arrival-rate model, its mandatory
concurrency ceiling, gliding staged concurrency, and the per-second threshold unit — and **S9's
per-workload plans**: `workload_name` on plans and shards, the engine-capability validation layer,
and analysis that merges sample sets across engines.

Also worth knowing: `platform-ui/src/App.css` is **not imported by anything** — `main.tsx` loads
only `index.css`. Rules added to App.css silently do nothing.

---

## 7. Hard rules for whoever continues

- **Stop after the current increment.** Produce the CLAUDE.md §30 completion report and stop.
- Do not push, tag, merge or publish without explicit user authorization.
- Preserve V1 behavior. `/api/v1/**` must keep working; the 8 paths above are the check.
- Tests named `KNOWN DEFECT` / `KNOWN GAP` / `KNOWN SECURITY GAP` assert behavior that is **wrong on purpose**. Invert them in place when the owning increment fixes them — never delete one to make a build pass.
- If an integration test reports `Skipped`, treat it as a failure to verify, not a pass.
- The **fake execution provider generates no load**, and the platform now enforces that: its runs are marked `synthetic`, report `INDETERMINATE` objectives and fail with `NO_LOAD_GENERATED`. Never weaken any part of that to make a demo look better — §3b explains why each piece exists.
- Pause and ask before: a new unapproved dependency, k6, a secret manager, an identity provider, a production S3 bucket, or real credentials/endpoints.

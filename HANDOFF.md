# Handoff — live working state

**Purpose:** If the current agent session ends, another agent picks up from here.
**Read first:** `NEXT-SESSION.md` for the immediate task and how to run it, then this file for state,
then `ROADMAP.md` for what is left and **where to stop**.
**Reference:** `CLAUDE.md`, then `docs/architecture/performance-testing-platform-target-architecture.md`.
**Last updated:** Increment 2h. Increments 0–2h are complete and committed.

**The foundation is complete** (CLAUDE.md §32), with one partial: team authorization is enforced
against a *development* identity provider until Increment 4. **The dataset loose thread is closed** —
a dataset is now uploaded, stored per team and per version, verified against the columns a test
declares, sliced per shard and delivered (§3m).

**A direction has been chosen: [`PLAN.md`](PLAN.md) — the Performance Studio.** One UI usable by
someone with **no performance testing experience**, covering **API load testing** and **web
performance testing**. Twelve increments, S1–S12, each shipping a backend capability *and* its studio
screen; **release 1 is S1–S6**. **`PLAN.md` supersedes `ROADMAP.md` §§2–5 as the statement of what
happens next**; `ROADMAP.md` remains the inventory of what exists. Nothing in `PLAN.md` is
implemented yet.

Scope decisions taken with the user, recorded so they are not relitigated:

- **vitest is approved** (§31 pause cleared) and lands in S1.
- **The recorder is deferred to S10.** Release 1 ships guided capture wizards instead: DevTools HAR
  for protocol journeys, `npx playwright codegen` for browser journeys. **A HAR cannot produce a
  Playwright script** — it records traffic, not user actions — so the two halves need separate
  sources.
- **Playwright import is in release 1**, as an uploaded or pasted `.spec.ts` stored immutably by
  content digest. Not a git clone: no credentials, no §31 pause. Git-pinned import is S10.
- **Live monitoring is deferred to S7** because it needs a runner-image change. Release 1 keeps the
  existing status page plus elapsed, stage and a progress bar, all derivable from persisted data.
- **Web performance is browser-only in release 1**, which needs no multi-plan refactor. Under-load
  web performance (the hybrid) is S11.

`NEXT-SESSION.md` §2 has the immediate task: **S1**.

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
| **S3 — HAR ordered steps, studio Rules screen** | **Not started** | [`PLAN.md`](PLAN.md) §11 |
| **S4–S6 — Performance Studio, rest of release 1** | **Planned, not started** | [`PLAN.md`](PLAN.md) §11 |
| **S7–S12 — release 2** | **Planned, not started** | [`PLAN.md`](PLAN.md) §11 |
| 4 — enterprise integration | **Not started** | `ROADMAP.md` §4 |

**931 tests green, 0 skipped** (272 at 1B, 486 at 1C, 551 at 1D, 591 after the demo path, 684 at 2b,
721 at 2c, 760 at 2d, 803 at 2e, 833 at 2g).

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

**Immediate next action: `PLAN.md`, increment S1** — the studio shell and the API path end to end.
Gated on a vitest decision (`PLAN.md` §12, R1): `platform-ui` has no test runner and S1 roughly
triples the UI, which is a CLAUDE.md §31 pause condition.

**Three structural facts S1 onward has to work around**, each found by reading the code and each with
an increment that fixes it:

1. **A journey is one request** — `ScenarioSpec` has one method and one path and no way to carry a
   value forward. No correlation, therefore no login. **S3.**
2. **`spec.authentication` is parsed, canonicalized, validated and used by nothing.** No
   `SecretResolver` exists anywhere in the tree. **S5.**
3. **A run has one plan with one engine.** `EngineDispatchingPlanCompiler.primaryEngine()` takes the
   first workload that declares an engine and **silently ignores the rest** — so triggering the
   shipped `specification/examples/valid/hybrid.yaml` compiles only JMeter and drops the browser
   workload with no error and no finding. **S7.**

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
misattribute; 3.5.3 does not). Verified across the full reactor: same 803 tests, 0 skipped, BUILD
SUCCESS, per-module totals byte-identical, and the 7 affected classes now report their own counts
with no class newly reporting 0. The 18 classes where *every* test is nested still report
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
- **vitest**, approved by the user, clearing the §31 pause. **88 UI tests** where there were none.
  Tests get their own `tsconfig.test.json` in the build: test code needs Node's types and application
  code must not have them.
- The **YAML editor stays**, behind *Write it as YAML*. Removing it would turn the studio's gaps into
  walls.
- A run's credentials never reach the screen: a cURL command carrying a bearer token and a session
  cookie imported as one journey with two findings, and **grep found no byte of either in the
  rendered page**.

### Not done, and owned by later increments

- **The team name is asked for rather than filled in.** Nothing reports the caller's own team —
  `TeamDirectory` maps name → id and not back, and no endpoint exposes it. S5's identity work fixes
  it; until then the studio asks, exactly as the YAML templates already did.
- **The status page is unchanged.** Elapsed, current stage and a progress bar are S2, along with the
  report's novice rewrite.
- **`web-experience` is choosable but not runnable.** The card, the steps and the browser-specific
  wording exist; the engine behind it is S6.

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
progress. That is **S7**, and it is the increment that changes the runner image.

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

### Not done, and still S3

- **The HAR importer still emits sibling scenarios, not ordered steps.** Its
  `import.har.journeys.notOrdered` warning therefore stands and must not be retired yet.
- **No studio Rules screen.** Journeys are expressible in YAML and through the API, not yet by
  clicking. The studio's Sources/Refine screens still produce single-request scenarios.

### A gap this uncovered, pre-existing and not fixed here

`SecretValueDetector`'s credential-name list is header-shaped: `x-api-key` is refused but `api_key`,
`access_token` and `token` are not, so `?api_key=short-value` passes as a query parameter. A query
string reaches every access log between the runner and the target, so the list arguably wants the
query forms too. Left alone deliberately — widening it is a change to what every existing document
validates as, not a side effect of adding journeys.

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

**Finish 1E's physical half first.** The control plane can create runner Jobs, but nothing has ever
run in one:

1. **Build the JMeter runner image.** `perfplatform/jmeter-runner:<tag>` — reads `PERF_*` from its
   environment, fetches the plan by `PERF_WORKLOAD_KEY`, runs JMeter, uploads `results.jtl`,
   `jmeter.log` and a `runner-manifest.json` echoing `PERF_PLAN_DIGEST`. Non-root, read-only root
   filesystem, everything written under `PERF_WORKSPACE`. Include `ConcurrencyThreadGroup` so load
   stages stop being stepwise.
2. **Run it once on kind or minikube.** Everything in 1E is verified against a mock API server and
   MinIO. That is the right way to test the contract and it is not the same as having run.
3. **Calibrate**, then raise shard count above 1. See `docs/operations/runner-calibration.md`.

**Then Increment 2 — importers.** OpenAPI, Postman, HAR, cURL and a manual builder. The manual case
now has its simplest form already (a scenario's `method` and `path`), so an importer's job is to
produce those plus headers, query parameters and bodies, which the specification still cannot express.

Already done, so **do not** repeat from earlier plans: the report endpoint, artifact download with
per-request authorization and digest verification, persisted run metrics, the Kubernetes provider,
the S3 artifact store, and `BaselineExecutionPlanCompiler`'s deletion.

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

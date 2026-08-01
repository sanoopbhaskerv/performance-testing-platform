# Handoff — live working state

**Purpose:** If the current agent session ends, another agent picks up from here.
**Read first:** `NEXT-SESSION.md` for the immediate task and build commands, then this file for state.
**Reference:** `CLAUDE.md`, then `docs/architecture/performance-testing-platform-target-architecture.md`.
**Last updated:** Increment 1E complete and proven on a real Kubernetes cluster (§3d–§3f).
**Increment 2 is in progress**: the request model and the cURL importer are done (§3g); the OpenAPI
importer is next. `NEXT-SESSION.md` §2 has the exact next step.

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
| 2 — request model + cURL importer | **In progress** `1b61d0e`, `9fcefe1` | see §3g |
| 2 — OpenAPI, HAR, Postman, datasets | **Not started** | `NEXT-SESSION.md` §2 |

**684 tests green, 0 skipped** (272 at 1B, 486 at 1C, 551 at 1D, 591 after the demo path).

**Nothing is pushed.** Pushing, tagging and opening a PR still require explicit authorization
(CLAUDE.md §6).

**PR #1 is open** and covers the four committed increments:
https://github.com/sanoopbhaskerv/performance-testing-platform/pull/1
Further commits on `feature/platform-v2-foundation` land in it automatically.

`v1-poc` tag exists on the remote at the baseline commit `9233d96` (created by the user).

**Immediate next action:** the **OpenAPI importer** (Increment 2c). `NEXT-SESSION.md` §2 lists the
four steps and the one real decision it needs. Everything it depends on is committed and green.

---

## 2. Environment gotchas (these will bite you)

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home mvn -B -f pom.xml clean verify
```

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

### Not done

The OpenAPI, HAR and Postman importers; dataset partitioning; dry-run preflight; baseline
comparisons; Jenkins shared library. **No UI path for importing** — the endpoint works, the console
has no button. `NEXT-SESSION.md` §2 has the exact next step and the one decision it needs.

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
- UI lint reports **10 errors, 3 warnings**, all pre-existing and none in files added since 1D. The count rose from the previously recorded 3 without any code changing: eslint's React rules began analysing `PerformanceConsole.tsx`, which has always had them. Verified by linting the committed file at HEAD.
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

# Handoff — live working state

**Purpose:** If the current agent session ends, another agent picks up from here.
**Read first:** `CLAUDE.md`, then `docs/architecture/performance-testing-platform-target-architecture.md`, then this file.
**Last updated:** Increment 1C complete and committed (`3290e75`). Increment 1D **partially complete and uncommitted** — see §1 and §6.

---

## 1. Where things stand

| Increment | Status | Evidence |
|---|---|---|
| 0 — baseline protection | **Complete** `4685388` | 53 characterization tests; `docs/migration/baseline-report.md` |
| 1A — modular skeleton | **Complete** `19f3c38` | 8 Maven modules |
| 1B — canonical specification | **Complete** `13fb724` | see §4 |
| 1B follow-up — schema conformance | **Complete** `9826a92` | `SchemaConformanceTest` |
| 1C — durable run orchestration | **Complete** `3290e75` | see §3 |
| 1D — JMeter engine SPI and local provider | **Part 1 of 2, uncommitted** | see §3a and §6 |
| 1E onward | **Not started** | — |

**549 tests green, 0 skipped** (272 at the end of 1B, 486 at the end of 1C).

**1C is committed but not pushed.** Pushing, tagging and opening a PR still require explicit
authorization (CLAUDE.md §6). **1D's work is uncommitted** — run `git status --short` first.

**PR #1 is open** and covers the four committed increments:
https://github.com/sanoopbhaskerv/performance-testing-platform/pull/1
Further commits on `feature/platform-v2-foundation` land in it automatically.

`v1-poc` tag exists on the remote at the baseline commit `9233d96` (created by the user).

**Immediate next action:** none outstanding. 1D is the next gated increment — do not start it without
the user asking. Read §6 first: 1D has a hard prerequisite.

---

## 2. Environment gotchas (these will bite you)

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home mvn -B -f pom.xml clean verify
```

- **`JAVA_HOME` is not set in the shell profile.** Every `mvn` call must supply it or Maven fails immediately.
- Only **JDK 21** is installed; the project targets **Java 17**. Building on 21 with `--release 17` is correct. **Do not change `java.version`** — CLAUDE.md §31 pause condition.
  - Consequence: **Java 21-only APIs do not compile.** `Math.clamp` was written and had to be reverted. Check the API's `@since` before using anything recent.
- The Bash tool's **working directory persists between calls**. A `cd` into a module (or `platform-ui`) silently breaks later commands. Always `cd` to the repo root first or use absolute paths. This has now bitten three times.
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
`domain/plan/` — `ExecutionPlan`, `ExecutionPlanCompiler`, `BaselineExecutionPlanCompiler`.
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

## 3a. Increment 1D — what is done, and what is not

### Done and tested

**Prerequisite — definitions are now reachable over HTTP.** `POST /api/v2/test-definitions`,
`POST /api/v2/test-definitions/{id}/versions`, `GET /{id}`, `GET /{id}/versions`
(`TestDefinitionController`, `AuthorTestDefinitionUseCase`, `TestDefinitionRepository` port +
`TestDefinitionStore`). Team and application are resolved from the document's metadata; names
resolve, they never create.

**Prerequisite — team onboarding.** `TeamRegistrySynchronizer` mirrors V1's team YAML into the V2
registry at startup (`TeamProvisioning` port + store). Non-production profiles only; refused under a
production profile like the other development-only components. This was the open question from 1C —
the chosen answer was the YAML sync, over admin endpoints (no caller holds `platform:admin`) and
auto-provisioning (a typo would manufacture a team with no owner or quota).

**The engine SPI is real.** `ExecutionEngineProvider` now has `validate`, `compile`, `render` and
`parse`. `EngineRegistry` + `EngineDispatchingPlanCompiler` replaced the 1C placeholder compiler and
**are wired** — the end-to-end test now compiles plans through `JmeterEngineProvider`.

**JMeter compile/render/parse, with four baseline defects fixed:**
- **D1** (numeric corruption at ≥1,000 — the platform's own stated maximum): `JmxWriter` appends
  numbers as numbers. No formatter in the path, so the defect class is unrepresentable, not patched.
- **D3** (endurance silently ran the load test's scenarios): the compiler reads the workload it was
  given.
- **D4** (scenario boundaries and weights discarded): each scenario becomes a weighted
  `ThroughputController`, and samplers are named for their scenario so per-scenario objectives work.
- **D5** (malformed rows skewed the error-rate denominator): `JtlParser` counts unreadable rows and
  the reducer keeps them in the denominator.

**Results and objectives.** `Sample`/`SampleSet`/`OperationMetrics`/`NormalizedRunResult` in the
domain; `RunResultReducer` and `ObjectiveEvaluator` in `platform-results`; `RunAnalyzer` port +
`DefaultRunAnalyzer`. An incomplete run or an unmeasured scope reports `INDETERMINATE` and can never
report a pass.

**Artifacts and schema.** `ArtifactStore` port + `FileSystemArtifactStore`; `ResultCatalog` port +
`ResultCatalogStore`; Flyway `V4__artifacts_and_objectives.sql` (both vendors).

**Target safety.** `TargetAddress`, `TargetPolicy` (metadata endpoints, link-local, multicast,
wildcard and unresolvable hosts all refused), `TargetResolver` port.

### Not done — 1D is not finished

1. **`LocalJmeterExecutionProvider` does not exist.** The fake provider is still the only execution
   path, so "a V2 API-only definition runs locally end to end" and "cancellation stops the local
   process" are **not** met.
2. **The reconciler has no analysis stage.** It still goes `ANALYZING → COMPLETED` immediately. It
   does not render workloads to artifacts, collect JTL, call the analyzer, or persist objective
   outcomes — so none of the results machinery above is reachable from a real run yet.
3. **`TargetResolver` has no implementation.** The port and the safety policy exist; nothing
   resolves a team+application+environment to an address. The intended source is V1 team YAML
   `targets[].baseUrl`, matching the team-sync adapter.
4. **No tests for the definition endpoints or the team synchronizer.**
5. **Docs not updated for 1D**, and `BaselineExecutionPlanCompiler` is now dead code (superseded by
   `EngineDispatchingPlanCompiler`) but still present with its tests.

### A gap worth knowing about before continuing

**A specification has no endpoints.** Scenarios name an `operationId` from an imported source, and
importers are Increment 2. Until then the renderer emits `GET /{operationId}` against the resolved
target. The pipeline is exercised end to end, but the requests do not reproduce a real request's
method, headers or body. This is visible in the generated plan rather than hidden, and it means 1D's
"runs locally end to end" is structurally true and semantically thin.

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
- UI lint reports **3 errors, 3 warnings** — pre-existing at baseline, deliberately not fixed.
- `helm lint` advises `icon is recommended` — pre-existing.
- Startup logs two loud warnings, by design: the **FAKE execution provider** generates no load, and the **DEVELOPMENT caller-identity provider** derives identity from V1 API keys. Both refuse to start under a production profile.

---

## 6. Next: finish 1D, then 1E

Finish 1D in this order — each step unblocks the next:

1. **`TargetResolver` implementation** in `platform-core`, reading V1 team YAML `targets[].baseUrl`,
   non-production only, calling `TargetPolicy.require` before returning. Nothing can render without it.
2. **Reconciler analysis stage.** Render the workload per shard → store via `ArtifactStore` → record
   via `ResultCatalog`; on `ANALYZING`, read the results artifacts → `engine.parse` →
   `RunAnalyzer.analyze` → persist outcomes → `COMPLETED` or `FAILED`. The reconciler's constructor
   is already 11 arguments; group the new collaborators rather than adding five more.
3. **`LocalJmeterExecutionProvider`.** `ProcessBuilder` with an argument list (never a shell string),
   a timeout from plan duration plus grace, `destroyForcibly` on cancel, sanitized stdout/stderr, and
   a dev-profile guard as loud as the fake provider's. CLAUDE.md §15.
4. **Tests** for the definition endpoints, the team synchronizer, the artifact store's key layout and
   traversal refusal, and an end-to-end run that reaches `COMPLETED` with objective outcomes stored.
5. **Delete `BaselineExecutionPlanCompiler`** and its test; fold anything still useful into the
   JMeter compiler's tests.
6. **Docs**: `docs/operations/runner-calibration.md`, and update `docs/api/v2-examples.md` with the
   definition endpoints.

### Then Increment 1E only

JMeter engine SPI and local provider (CLAUDE.md §28).

**Hard prerequisite, do this first.** `POST /api/v2/test-definitions` and
`.../{id}/versions` still do not exist. `TestDefinitionRegistry` can create definitions and append
versions, but no HTTP endpoint reaches it — so a definition version can only be created through the
storage layer, and the run endpoints delivered in 1C are **not reachable end to end by a consumer**.
1D's acceptance criterion ("a V2 API-only definition runs locally end to end") cannot be met without
them. Fold them into the front of 1D.

Open question they raise: teams and applications must exist in the V2 registry before a definition
can be created, and nothing onboards them today. Decide whether that is an admin endpoint, a
migration from team YAML, or auto-provisioning under an explicit non-production flag — and ask before
picking auto-provisioning.

Useful groundwork already in place: the `ExecutionPlanCompiler` port (swap
`BaselineExecutionPlanCompiler` for the JMeter one and nothing else changes), the plan digest contract
already verified end to end, `RunnerShard` carrying shard index/count for dataset partitioning, the
`Clock` bean for deterministic tests, and PostgreSQL Testcontainers proven for concurrency.

---

## 7. Hard rules for whoever continues

- **Stop after the current increment.** Produce the CLAUDE.md §30 completion report and stop.
- Do not push, tag, merge or publish without explicit user authorization.
- Preserve V1 behavior. `/api/v1/**` must keep working; the 8 paths above are the check.
- Tests named `KNOWN DEFECT` / `KNOWN GAP` / `KNOWN SECURITY GAP` assert behavior that is **wrong on purpose**. Invert them in place when the owning increment fixes them — never delete one to make a build pass.
- If an integration test reports `Skipped`, treat it as a failure to verify, not a pass.
- The **fake execution provider generates no load**. Never let a run reported as `COMPLETED` under it be presented as evidence a test ran.
- Pause and ask before: a new unapproved dependency, k6, a secret manager, an identity provider, a production S3 bucket, or real credentials/endpoints.

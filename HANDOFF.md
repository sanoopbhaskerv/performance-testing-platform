# Handoff — live working state

**Purpose:** If the current agent session ends, another agent picks up from here.
**Read first:** `CLAUDE.md`, then `docs/architecture/performance-testing-platform-target-architecture.md`, then this file.
**Last updated:** Increment 1B complete, committed and pushed. Working tree clean.

---

## 1. Where things stand

| Increment | Status | Evidence |
|---|---|---|
| 0 — baseline protection | **Complete** `4685388` | 53 characterization tests; `docs/migration/baseline-report.md` |
| 1A — modular skeleton | **Complete** `19f3c38` | 8 Maven modules |
| 1B — canonical specification | **Complete** `13fb724` | see §3 |
| 1B follow-up — schema conformance | **Complete** `9826a92` | `SchemaConformanceTest`; four divergences fixed |
| 1C onward | **Not started** | — |

**272 tests green, 0 skipped.** Working tree clean; branch in sync with `origin`.

**PR #1 is open and covers all four commits:**
https://github.com/sanoopbhaskerv/performance-testing-platform/pull/1
Further commits on `feature/platform-v2-foundation` land in it automatically.

`v1-poc` tag exists on the remote at the baseline commit `9233d96` (created by the user).

**Immediate next action:** nothing outstanding. 1C is the next gated increment — do not start it without the user asking.

**Known gap before 1C can be exercised end-to-end:** `TestDefinitionRegistry` can create definitions and append versions, but no HTTP endpoint reaches it — the only V2 path today is `/validate`. Since `POST /api/v2/test-runs` must reference an immutable definition version, `POST /api/v2/test-definitions` and `.../versions` need to exist first. Recommended: fold them into the front of 1C.

---

## 2. Environment gotchas (these will bite you)

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home mvn -B -f pom.xml clean verify
```

- **`JAVA_HOME` is not set in the shell profile.** Every `mvn` call must supply it or Maven fails immediately.
- Only **JDK 21** is installed; the project targets **Java 17**. Building on 21 with `--release 17` is correct. **Do not change `java.version`** — CLAUDE.md §31 pause condition.
- The Bash tool's **working directory persists between calls**. A `cd` into a module silently breaks later commands. Always `cd` to the repo root first or use absolute paths. This has bitten twice.
- Single test across the reactor: **`-Dsurefire.failIfNoSpecifiedTests=false`** (not `-DfailIfNoSpecifiedTests=false`).
- `-pl <module>` alone fails unless dependencies are installed in `~/.m2`; list them (`-pl platform-domain,platform-persistence`) or run the full reactor.
- **Docker 29 rejects API < 1.44.** Testcontainers' docker-java negotiates lower and then *silently skips* every integration test rather than failing. Fixed permanently via `docker.api.version` → surefire `api.version` system property in the root pom. **If integration tests ever report `Skipped`, that is a false green — investigate, do not accept it.**

---

## 3. Increment 1B — delivered

### Canonical specification (`platform-domain`)
`domain/spec/` — `PerformanceTestSpecification` with nested records (metadata, environment, sources, authentication, datasets, workloads, load stages, weighted scenarios, objectives, observability, governance), `HumanDuration`, `Threshold`, enums, `support/SpecEnums`, `SpecificationCanonicalizer`, `SpecificationDigest`.
`domain/validation/` — `Severity`, `ValidationFinding`, `ValidationResult`, `SecretValueDetector`, `SemanticValidator`.

### Schema and fixtures
`specification/schema/v1alpha1/performance-test.schema.json`; `specification/examples/valid/{api-only,browser-only,hybrid}.yaml`; seven `specification/examples/invalid/*.yaml`. Tests read the **shipped files**, so a stale example fails the build.

### API (`platform-api`)
`v2/spec/SpecificationParser` (explicit `JsonNode` traversal), `SpecificationValidationService`, `dto/ValidationResponse`, `TestDefinitionValidationController` (`POST /api/v2/test-definitions/validate`), `error/V2ProblemHandler` (RFC 7807, scoped to `com.perfplatform.api.v2` so V1 errors are untouched), `config/V2ApiConfiguration`.

### Persistence (`platform-persistence`)
Flyway migrations under `db/migration/{postgresql,h2}` — `V1__baseline_v1_test_runs.sql`, `V2__test_definitions.sql`. Entities `Team/Application/TestDefinition/TestDefinitionVersion`, Spring Data repositories, `TestDefinitionRegistry`. Test-only `PersistenceTestApplication` (the module has no production app class by design).

**`spring.jpa.hibernate.ddl-auto` changed `update` → `none`; Flyway now owns the whole schema**, including V1's `test_runs`.

### Test counts (all green, 0 skipped)
domain 97 · engine-spi 9 · results 12 · **persistence 15 (PostgreSQL Testcontainers)** · api 68 · engine-jmeter 7 · core 64 = **272**

---

## 4. Design decisions made in 1B (do not relitigate)

- **Domain has no Jackson.** Parsing lives in `platform-api` as explicit `JsonNode` traversal, not data binding — keeps serialization annotations out of the domain, gives every finding an exact JSON-pointer path, and turns an unknown field into a finding instead of a 500.
- **Unknown fields are rejected.** Silently ignoring `targetUser` (typo for `targetUsers`) would run a test at a load nobody chose.
- **No JSON Schema validator library — decided, not deferred by accident.** CLAUDE.md §31 makes a new unapproved dependency a pause condition, and the parser is not redundant with the schema anyway: something must map YAML to typed `Duration`/`Threshold` values regardless, and every safety-relevant rule lives in `SemanticValidator`, which JSON Schema cannot express. Drift is a DX problem, not a safety one. Closed instead with `SchemaConformanceTest` (enums, required fields, secret-scheme list). **Revisit only if** the schema grows rules the parser cannot mirror or teams need bit-identical answers — and then ask for **test scope first**.
- **Canonical form is hand-written, not JSON.** Digest input must not depend on a serialization library's formatting, or a library upgrade silently changes every stored digest. Version-tagged (`canonicalForm=v1alpha1`) and escaped so a value containing `=` or a newline cannot forge structure.
- **`validate` returns 200 even when invalid.** The findings are the answer. 400 is reserved for a malformed *request*, so CI can distinguish "your spec has errors" from "transport failure".
- **Thresholds require an explicit unit.** `750` is rejected rather than assumed to be ms — guessing silently changes pass/fail.
- **Finding codes are a public contract.** CI keys on `code`; never rename one once released. Messages may be reworded.
- **Immutability is enforced three times** (registry refuses, JPA `updatable=false`, PostgreSQL trigger). Only the trigger survives a direct SQL write, which is why that test must run on PostgreSQL and not H2.
- **Testcontainers 1.19.3 → 1.20.6**, test scope only. Forced by Docker 29; without it every integration test was skipped.

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

Boot check — must serve all 8 V1 paths plus the V2 validate path:

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home java -jar platform-core/target/platform-core-1.0.0.jar --server.port=18081
```

**Known-correct oddities — do not "fix" these:**
- `/actuator/health` returns **503** (mail health indicator cannot reach SMTP). Defect **D6**, owned by 1E.
- Unauthenticated calls return **403**, not 401. Defect **D7**, owned by Increment 4.
- UI lint reports **3 errors, 3 warnings** — pre-existing at baseline, deliberately not fixed.
- `helm lint` advises `icon is recommended` — pre-existing.

---

## 6. Hard rules for whoever continues

- **Stop after the current increment.** Produce the CLAUDE.md §30 completion report and stop.
- Do not push, tag, merge or publish without explicit user authorization.
- Preserve V1 behavior. `/api/v1/**` must keep working; the 8 paths above are the check.
- Tests named `KNOWN DEFECT` / `KNOWN GAP` / `KNOWN SECURITY GAP` assert behavior that is **wrong on purpose**. Invert them in place when the owning increment fixes them — never delete one to make a build pass.
- If an integration test reports `Skipped`, treat it as a failure to verify, not a pass.
- Pause and ask before: a new unapproved dependency, k6, a secret manager, an identity provider, a production S3 bucket, or real credentials/endpoints.

---

## 7. Next increment: 1C only

Durable run orchestration — run/shard/event tables, state-transition matrix, idempotent V2 trigger, queue claiming with per-team fairness, cancellation, fake execution provider, reconciliation tests including restart simulation.

Useful groundwork already in place: typed identifiers (`TestRunId`, `RunnerShardId`, `ExecutionPlanId`), the `Clock` bean (`TimeConfiguration`) for deterministic lease/heartbeat tests, Flyway owning the schema, and PostgreSQL Testcontainers proven working — 1C's concurrent-claim tests require real PostgreSQL, since H2 will not reproduce its locking semantics.

# V1 to V2 Migration Strategy

**Status:** Active — foundation phase
**Baseline:** `9233d962a24ac819224cbf4e213862b25cd65e2e`
**Branch:** `feature/platform-v2-foundation`
**Measured starting point:** [baseline-report.md](baseline-report.md)

This document defines how the proof of concept is evolved into the target architecture without a
rewrite and without an availability gap for existing consumers.

---

## 1. Principle

V1 is evolved in place. It is not discarded and it is not rewritten in one step. At every commit
on this branch the application builds, boots and serves the existing `/api/v1/**` surface.

The migration is **parallel**, not sequential: V2 is built alongside V1 and V1 is removed only
after V2 reaches functional parity and the removal is explicitly approved.

---

## 2. Branch and tag strategy

| Item | Value | Status |
|---|---|---|
| Working branch | `feature/platform-v2-foundation` | Created by the user; work continues here |
| POC tag | `v1-poc` at the baseline commit | **Not yet created** — requires explicit authorization |
| Remote push | — | **Not performed.** No push, PR, merge, tag or image publish has occurred |

The `v1-poc` tag is recommended so the proof of concept remains retrievable after V1 components
are eventually deleted. Creating it requires user authorization (CLAUDE.md §7, §31).

---

## 3. Compatibility contract during the migration

These hold for the entire foundation phase:

1. `/api/v1/**` stays operational and behaviorally unchanged until its V2 replacement is verified
   and removal is approved.
2. `/api/v2/**` is additive. It never reuses a V1 path.
3. The existing React UI and the static `index.html` continue to work against V1 endpoints until
   Increment 1F provides a migration path.
4. Team YAML in `config/teams/` keeps loading. It becomes an *importable source* for canonical
   specifications rather than the authoritative registry, but the loader is not deleted while V1
   endpoints depend on it.
5. No public API changes silently. A V1 response shape change requires an explicit decision.
6. The Java and Spring Boot baseline is not changed during the foundation refactor.
7. Maven coordinates and the `com.perfplatform` package root are not renamed during the
   architecture migration. Corporate naming is a separate, later decision.

---

## 4. Component disposition

How each V1 component is treated. "Isolate" means it keeps working but moves behind an interface
so production traffic can be routed elsewhere.

| V1 component | Disposition | Replacement | Increment |
|---|---|---|---|
| `TestRunService.executeAsync` | **Replace** | Durable PostgreSQL state machine + reconciler | 1C |
| `JmeterTestOrchestrator` (`ProcessBuilder`) | **Isolate** | `LocalJmeterExecutionProvider`, local/dev profile only | 1D |
| Production JMeter execution | **Replace** | `KubernetesJmeterExecutionProvider`, ephemeral Jobs | 1E |
| JMX Freemarker templates | **Evolve** | Inputs to the engine-SPI plan compiler; defects D1–D4 fixed | 1D |
| `SlaEvaluator` | **Preserve behind a new model** | Normalized result model + objective evaluation | 1D |
| `PercentileCalculator` (extracted 1A) | **Preserved** | Already moved to `platform-results` | 1A ✅ |
| `TeamConfig` / `TeamConfigLoader` | **Isolate** | Canonical `PerformanceTestSpecification`; YAML becomes an importer | 1B |
| `TestRun` JPA entity | **Replace** | Flyway-managed schema, typed domain aggregate, response DTOs | 1B/1C |
| `ApiKeyAuthFilter` | **Replace** | Team-bound caller model, OIDC + scoped service identities | 4 |
| Public report endpoints | **Replace** | Authorized, time-limited download grants | 1E |
| Local filesystem artifacts | **Replace** | `ArtifactStore` (filesystem for dev, S3-compatible for prod) | 1D/1E |
| `JvmDumpCollector` | **Isolate** | Explicit, separately authorized diagnostic providers | 4 |
| `ReportGenerator` | **Evolve** | Unified report over normalized results + Dynatrace enrichment | 1D/4 |
| `NotificationService` | **Preserve** | Unchanged in the foundation phase | — |
| Helm chart, CI templates, Compose | **Evolve** | Runner cell chart, control-plane chart, profiles | 1E |

---

## 5. Defect and gap register

The authoritative list — with evidence, thresholds and owning increment — is
[baseline-report.md](baseline-report.md) sections 3 and 4. Summary of ownership:

| Increment | Must fix |
|---|---|
| 1B | S7 (entity as response), `ddl-auto` → Flyway |
| 1C | S6 (no idempotency key), four-state model, no cancellation, no admission control |
| 1D | **D1 (numeric corruption at ≥1,000 — blocks the platform's stated maximum load)**, D2 (spike template missing), D3 (endurance uses load scenarios), D4 (scenario flattening), D5 (error-rate denominator) |
| 1E | S3, S4 (public artifacts), D6 (health coupled to optional SMTP), pod-local artifact paths |
| 4 | S1, S2 (cross-team authorization), S5 (plaintext keys in YAML), S8 (no target allowlist), D7 (403 vs 401) |

**D1 is the highest-severity functional defect.** V1 cannot execute a test at the 1,000
virtual-user ceiling the platform is specified for, nor a 60-minute endurance test at its
documented default, because the generated JMX carries a thousands separator.

---

## 6. How characterization tests govern the migration

`platform-core/src/test/java/com/perfplatform/characterization/` pins V1 behavior as it exists
today, including its defects.

- Tests **without** a `KNOWN` marker protect behavior that must survive the migration unchanged.
  If one breaks, the change is a regression until proven otherwise.
- Tests marked `KNOWN DEFECT`, `KNOWN GAP` or `KNOWN SECURITY GAP` assert behavior that is wrong.
  The owning increment **inverts the assertion in place and keeps the test**. It becomes the
  regression guard proving the fix landed.
- Deleting a `KNOWN` test is not an acceptable way to make a build pass. It destroys the only
  record that the defect existed and the only proof that it was fixed.

---

## 7. Cutover sequence

V1 removal is gated and is not part of the foundation phase.

1. V2 reaches functional parity for a capability.
2. Parity is demonstrated by tests, not asserted.
3. The V2 path is exercised in a real deployment.
4. Removal of the V1 component is **explicitly approved by the user** (CLAUDE.md §31).
5. The V1 component is removed and its characterization tests are retargeted or retired with a
   recorded rationale.

Until step 4, both paths coexist.

---

## 8. Open decisions blocking later increments

These do not block the foundation but must be resolved before the increment named.

| Decision | Blocks | Reference |
|---|---|---|
| Corporate identity provider and group/team claim mapping | Increment 4 | Architecture §18.1 |
| Approved enterprise secret manager | Increment 4 | Architecture §18.3 |
| Object-storage service, bucket/account and retention period | Increment 1E | Architecture §18.4 |
| Supported Java Kubernetes client | Increment 1E | CLAUDE.md §16 |
| Standalone-first vs shared control plane for the first release | Increment 4 | Architecture §18.2 |
| Rules for testing production and external targets | Increment 4 | Architecture §18.5 |
| Dynatrace API access and request-attribute configuration | Increment 4 | Architecture §18.8 |
| BrowserStack licence and acceptable concurrency | Increment 3 | Architecture §18.7 |

CLAUDE.md §31 requires stopping and asking before resolving any of these.

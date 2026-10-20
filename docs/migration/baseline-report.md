# V1 Baseline Report

**Baseline commit:** `9233d962a24ac819224cbf4e213862b25cd65e2e` ("first commit")
**Branch:** `feature/platform-v2-foundation`
**Recorded:** Increment 0, before any behavior change
**Purpose:** Establish a verified starting point so the V2 migration can be judged against measured facts rather than assumptions.

The repository matched the reviewed baseline exactly. The only untracked files were the two
handoff documents (`CLAUDE.md` and the target architecture), neither of which affects the build.

---

## 1. Environment used for verification

| Tool | Version |
|---|---|
| Maven | 3.9.11 |
| JDK | OpenJDK 21.0.11 (Homebrew, arm64) |
| Node | v25.2.1 |
| npm | 11.6.2 |
| Docker | 29.1.2 |
| Helm | v4.1.3 |

**JDK note.** Only JDK 21 is installed on this machine; the project targets Java 17 via the
Spring Boot parent's `java.version` property. The build therefore runs on JDK 21 compiling with
`--release 17`, which Maven reports as `javac [debug release 17]`. The declared Java baseline in
`pom.xml` is unchanged, as required by CLAUDE.md §8 and §31. `JAVA_HOME` is not set in the shell
profile, so Maven must be invoked with `JAVA_HOME` supplied explicitly.

---

## 2. Baseline command results

All commands were run at the baseline commit, before any source edit.

| Command | Result |
|---|---|
| `mvn clean verify` | **BUILD SUCCESS** — 2 modules, 15 source files compiled |
| `mvn` test phase | **No tests to run** — `platform-core/src/test` did not exist |
| `npm ci` (platform-ui) | Success — **10 vulnerabilities reported: 1 low, 1 moderate, 8 high** |
| `npm run lint` (platform-ui) | **FAILS — 3 errors, 3 warnings** |
| `npm run build` (platform-ui) | Success — `tsc -b && vite build`, 1779 modules |
| `docker compose -f docker/docker-compose.yml config` | Valid (exit 0) |
| `helm lint helm/performance-platform` | 0 charts failed (advisory: `icon is recommended`) |
| `git status --short` | Clean apart from the two untracked handoff documents |

### Pre-existing UI lint failures

These were present at the baseline and are **not** introduced by this work. They are left
unfixed because Increment 0 forbids unrelated behavior change.

```text
platform-ui/src/App.tsx
   51:6   warning  React Hook useEffect has a missing dependency: 'fetchTeams'
   60:6   warning  React Hook useEffect has a missing dependency: 'fetchHistory'
   73:6   warning  React Hook useEffect has a missing dependency: 'fetchHistory'
   84:19  error    Unexpected any. Specify a different type
  110:19  error    Unexpected any. Specify a different type
  140:19  error    Unexpected any. Specify a different type
```

The three `no-explicit-any` errors sit on the API response handling in `App.tsx`. They are
resolved naturally in Increment 1F, which introduces typed service clients generated from the V2
OpenAPI contract (CLAUDE.md §8).

### Build/reactor observations

- `platform-ui` is **not** a Maven module. The reactor contains only the root POM and
  `platform-core`, so `mvn verify` does not build, lint or test the UI. UI verification must be
  run separately until a frontend build plugin is introduced.
- Two UIs exist in the repository: the React application in `platform-ui/` and a separate static
  `platform-core/src/main/resources/static/index.html` served by the Spring Boot app. Their
  relationship is undocumented.
- `demo-service/` is present on disk but is not a module of the root reactor either.

---

## 3. Defects found at the baseline

These were discovered while writing characterization tests. Each is now pinned by a test so the
V2 work cannot silently change or silently preserve it.

### D1 — Numeric values of 1,000 or more corrupt every generated JMeter plan (MATERIAL)

Freemarker applies locale-aware digit grouping to numeric models. `${durationSeconds}` with a
value of 3600 renders as `3,600`, which JMeter cannot parse as a thread-group duration.

Confirmed by direct probe: `duration=[3,600] users=[20] steady=[100]`.

Affected fields and thresholds:

| Field | Breaks when | Notes |
|---|---|---|
| `ThreadGroup.duration` (endurance) | duration ≥ ~17 min | **The documented default is 60 minutes**, so the default endurance test is broken |
| `ThreadGroup.num_threads` | users ≥ 1,000 | **Exactly the platform's maximum supported virtual-user count** (CLAUDE.md §3) |
| `ThreadGroup.duration` (load) | steady state ≥ ~17 min | |
| `ThreadGroup.ramp_time` | ramp ≥ ~17 min | |
| Spike peak users | peak ≥ 1,000 | |

This defect means V1 cannot execute a test at the scale the platform is specified for. The fix is
`?c` (computer number format) on every numeric interpolation, or passing pre-formatted strings
from the compiler. **Owner: Increment 1D.** Pinned by
`JmxGenerationCharacterizationTest.numberFormattingCorruptsValuesAtOrAboveOneThousand`.

### D2 — The spike test type cannot run at all

`JmeterTestOrchestrator.generateJmx` selects `spike-test.jmx.ftl`, but only `load-test.jmx.ftl`
and `endurance-test.jmx.ftl` exist in `src/main/resources/templates/jmx/`. Any team with
`tests.spike.enabled: true` gets a render-time failure. `TestRun.TestType.SPIKE` and the spike
config block both exist, so the feature appears supported. **Owner: Increment 1D.** Pinned by
`JmxGenerationCharacterizationTest.spikeTemplateIsMissing`.

### D3 — Endurance runs silently execute the load test's endpoints

`buildTemplateModel` always reads `config.getTests().getLoad().getScenarios()` regardless of the
requested test type. An endurance run applies endurance thread settings but load scenarios.
**Owner: Increment 1D.** Pinned by `JmxGenerationCharacterizationTest.enduranceReusesLoadScenarios`.

### D4 — Scenario boundaries and weights are discarded

All scenarios are flattened into a single endpoint list in one thread group. Scenario names do
not appear in the generated plan and there is no weighting mechanism, so a realistic journey mix
cannot be expressed. **Owner: Increment 1D.** Pinned by
`JmxGenerationCharacterizationTest.flattensScenariosLosingBoundaries`.

### D5 — Malformed result rows skew the error-rate denominator

`SlaEvaluator` increments `totalRequests` before parsing the row, so a row with an unparseable
`elapsed` value counts as a request but contributes no latency sample and cannot count as an
error. **Owner: Increment 1D** (normalized result model). Pinned by
`SlaEvaluatorCharacterizationTest.malformedRowsCountAsRequestsButNotSamples`.

### D6 — Health reports DOWN whenever the optional SMTP host is unreachable

`/actuator/health` aggregates every indicator including the mail sender. With the default
`spring.mail.host=localhost`, health returns **503** in local development and CI
(`java.net.ConnectException: Connection refused`). An optional notification dependency can
therefore fail the platform's readiness probe and cause Kubernetes to restart or drop a healthy
control plane. CLAUDE.md §26 requires readiness to distinguish essential dependencies from
optional enrichment providers. **Owner: Increment 1E.** Recorded here rather than pinned as a
test assertion, because the status is environment-dependent; the characterization test asserts
only the stable property that health is reachable without a key.

### D7 — Unauthenticated API calls return 403, not 401

No `AuthenticationEntryPoint` is configured, so Spring Security applies access-denied handling to
the anonymous principal. Clients cannot distinguish "not authenticated" from "not permitted", and
CI integrations cannot reliably detect an expired credential. **Owner: Increment 4.** Pinned by
`ApiAuthorizationCharacterizationTest.rejectsMissingApiKey`.

---

## 4. Known security gaps at the baseline

Each is pinned by a passing test asserting today's behavior, named `KNOWN SECURITY GAP`, so the
build stays green while the gap stays visible. **Each of these tests must be inverted — not
deleted — by the increment named below.**

| # | Gap | Evidence | Fix owner |
|---|---|---|---|
| S1 | Any valid API key authenticates for **every** team. `ApiKeyAuthFilter` matches the key against all team configs and never binds the principal to a team. | `anyValidKeyReadsAnyTeamHistory` | Increment 4 |
| S2 | One team's key can trigger material load against another team's targets. | `anyValidKeyTriggersAnyTeamRun` | Increment 4 |
| S3 | `/api/v1/tests/*/report` is explicitly `permitAll()` — test evidence is readable with no credential. | `reportEndpointIsPublic` | Increment 1E |
| S4 | `/runs/**` serves the entire working directory as static assets with no authentication, exposing raw JTL results, JMeter logs and heap-dump output across teams. | `artifactTreeIsPublic` | Increment 1E |
| S5 | API keys are stored in plaintext in team YAML, which is committed to source control (`config/teams/example-team.yml` ships a placeholder key). | `config/teams/example-team.yml:14` | Increment 4 |
| S6 | The trigger endpoint accepts no idempotency key, so a retried request starts a second material load test. | `triggerHasNoIdempotencyKey` | Increment 1C |
| S7 | The JPA entity is serialized directly as the REST response, making the persistence schema the public contract and exposing internal paths. | `exposesJpaEntityAsResponse` | Increment 1B/1F |
| S8 | No target allowlist, no environment classification and no server-side user/duration ceiling. Any reachable URL in team YAML is a valid target. | `TeamConfig.TargetConfig` has no policy fields | Increment 4 |

`H2 console is enabled` (`spring.h2.console.enabled: true`) in the shipped `application.yml`. It
is not in the `permitAll` list so it requires a key, but it should not be enabled outside local
development.

---

## 5. Architectural gaps confirmed against CLAUDE.md §5

| Gap | Location | Fix owner |
|---|---|---|
| `@Async` holds the whole run lifecycle; a control-plane restart strands a run in `RUNNING` with no reconciliation | `TestRunService.executeAsync` | 1C |
| `ProcessBuilder` starts JMeter inside the control plane | `JmeterTestOrchestrator.run` | 1D/1E |
| Pod-local filesystem paths persisted as durable artifact references | `TestRun.reportPath` | 1E |
| Only four run states, no cancellation, no queue, no admission control | `TestRun.Status` | 1C |
| Team YAML held in memory is the authoritative definition registry | `TeamConfigLoader` | 1B |
| `ddl-auto: update` with no Flyway migrations | `application.yml:22` | 1B |
| JMeter-specific domain; no engine-neutral specification | `TeamConfig` | 1B |
| Heap/thread dump collection inline on the normal run path | `TestRunService.executeAsync` | 4 |
| `ProcessBuilder` also used for Eclipse MAT | `JvmDumpCollector.runMatAnalysis` | 4 |

---

## 6. Characterization coverage added in Increment 0

53 tests, all passing, in `platform-core/src/test/java/com/perfplatform/characterization/`.

| Test class | Tests | Behavior protected |
|---|---:|---|
| `SlaEvaluatorCharacterizationTest` | 12 | Percentile index maths, sorting, error rate, threshold exclusivity, breach message formatting, missing/empty/malformed input |
| `JmxGenerationCharacterizationTest` | 9 | Template selection, thread-group settings, domain derivation, sampler rendering, and defects D1–D4 |
| `TestRunLifecycleCharacterizationTest` | 13 | Trigger validation, PENDING-before-execute, success/breach/failure paths, notification on failure, opt-in JVM dumps, four-state model |
| `TestRunPersistenceCharacterizationTest` | 8 | UUID generation, defaults, outcome round-trip, string enums, history ordering and filtering, 10-run cap, missing concurrency columns |
| `ApiAuthorizationCharacterizationTest` | 11 | Authentication accept/reject, 202 trigger contract, and gaps S1–S4, S6, S7 |

**Reading the KNOWN markers.** A test named `KNOWN DEFECT`, `KNOWN GAP` or `KNOWN SECURITY GAP`
asserts behavior that is wrong and scheduled for replacement. When the owning increment fixes it,
invert the assertion in place and keep the test — that is the regression guard proving the fix
landed. Deleting one of these tests removes the only record that the defect existed.

---

## 7. What was deliberately not done

- No V1 behavior was changed. All defects above remain live in V1.
- The three pre-existing UI lint errors were not fixed.
- The 8 high-severity npm advisories were not remediated; dependency upgrades must not be
  combined with architecture refactoring (CLAUDE.md §6).
- No Kubernetes client, Playwright, Dynatrace, importer or new authentication was introduced.
- Nothing was pushed, tagged, merged or published.

# Claude Code Implementation Instructions

## Ameriprise Self-Service Performance Testing Platform V2

This file is the implementation contract for evolving:

`https://github.com/sanoopbhaskerv/performance-testing-platform`

Baseline reviewed commit:

`9233d962a24ac819224cbf4e213862b25cd65e2e`

The repository is a proof of concept. Evolve it in place through a controlled V2 re-platforming. Do not discard it and do not attempt a risky one-shot rewrite.

---

## 1. Mandatory reading order

Before editing any source file:

1. Read this `CLAUDE.md` completely.
2. Read `performance-testing-platform-target-architecture.md` completely.
3. Read the repository root `README.md`.
4. Inspect the root `pom.xml`, `platform-core/pom.xml`, `platform-ui/package.json`, Docker Compose files, Helm chart, CI templates and current tests.
5. Inspect at minimum:
   - `platform-core/src/main/java/com/perfplatform/engine/TestRunService.java`
   - `platform-core/src/main/java/com/perfplatform/engine/JmeterTestOrchestrator.java`
   - `platform-core/src/main/java/com/perfplatform/config/TeamConfig.java`
   - `platform-core/src/main/java/com/perfplatform/config/TeamConfigLoader.java`
   - `platform-core/src/main/java/com/perfplatform/storage/TestRun.java`
   - `platform-core/src/main/java/com/perfplatform/security/ApiKeyAuthFilter.java`
   - `platform-core/src/main/java/com/perfplatform/security/SecurityConfig.java`
   - JMX Freemarker templates
   - Helm JMeter Job template
6. Run the baseline build and tests before changing behavior.
7. Report baseline findings and the exact increment you intend to implement.

Do not begin with broad file movement. Establish the baseline first.

---

## 2. Instruction precedence

When instructions conflict, use this order:

1. The user's current explicit instruction.
2. `performance-testing-platform-target-architecture.md`.
3. This `CLAUDE.md`.
4. Existing repository documentation.
5. Existing implementation behavior.

If the architecture and current user instruction materially conflict, stop and ask for a decision. Do not silently reinterpret the architecture.

---

## 3. Product context and fixed constraints

This is an internal Ameriprise self-service performance engineering platform.

### Capacity

- Normal concurrency: 3–4 application teams testing simultaneously.
- Worst-case concurrency: 10 application teams testing simultaneously.
- Maximum protocol-level load per team run: approximately 1,000 virtual users.
- Theoretical aggregate maximum: approximately 10,000 protocol virtual users.
- Browser concurrency is separately governed and must not be equated with protocol virtual users.

### Consumer experiences

All capabilities must be available through:

- Web UI.
- REST API.
- YAML/GitOps.

All three must create or consume the same versioned, engine-neutral `PerformanceTestSpecification`. Do not build three separate domain models.

### MVP engines

- JMeter is the initial HTTP/API load engine.
- Playwright is the approved real-browser engine.
- BrowserStack is an optional Playwright execution provider.
- k6 is not approved and must not be introduced into the MVP.
- Preserve an engine SPI so k6 or another engine can be added later without changing the domain or orchestration layers.

### Input formats

The complete product must support:

- OpenAPI/Swagger.
- Postman Collection.
- HAR.
- cURL.
- Manual endpoint/workflow entry.
- Existing Playwright test imported using repository, immutable ref and path.

### Browser authoring

For the MVP, import an existing Playwright test. Do not attempt autonomous browser-script generation.

Later phases may combine:

- Existing Playwright code.
- Visual recording.
- Page models.
- Plain-language intent.
- AI-generated candidate scripts.

Generated scripts must always be reviewed and approved before material execution.

### Observability

- Dynatrace is the preferred application-side observability integration.
- Every workload must support injection of a run correlation header.
- Heap and thread dump collection must not remain coupled to the core execution lifecycle.
- Heap/thread diagnostics belong behind explicit, restricted diagnostic providers.

### Deployment

Support two profiles from the same codebase:

1. `standalone`: complete platform deployed in an application team's infrastructure.
2. `shared-runner`: centrally governed control plane with team-local runner cells.

The initial implementation may deliver standalone first, but domain and execution contracts must not prevent shared-runner federation later.

---

## 4. Non-negotiable architecture decisions

The following decisions are already made:

1. Separate control, execution and analysis responsibilities.
2. The Spring Boot API must not generate material load.
3. Production JMeter and Playwright execution must run in isolated, ephemeral Kubernetes Jobs or an equivalent approved runner provider.
4. PostgreSQL is the durable source of truth for definitions, runs, transitions, approvals and summaries.
5. Object storage is the durable store for JMX, JTL, reports, traces, screenshots and logs.
6. Pod-local files are temporary working files, never the system of record.
7. The platform must use a persisted, reconciled run state machine.
8. Do not introduce Kafka, Temporal or another workflow platform in the initial architecture.
9. Every run references one immutable test-definition version and execution-plan digest.
10. Every trigger is idempotent.
11. Every artifact and result is authorized by team.
12. User-submitted specifications contain secret references, never secret values.
13. The platform enforces quotas and target policies server-side.
14. Automatic Kubernetes retries must not accidentally replay material load.
15. Existing V1 behavior remains available until its V2 replacement is verified.

Do not substitute a microservice decomposition for the requested modular monolith. Initially deploy only:

- One control-plane application.
- An optional lightweight runner agent for remote cells.
- Ephemeral runner Jobs.

---

## 5. What to preserve from the current repository

Preserve and evolve:

- Repository and build history.
- Spring Boot backend.
- React/TypeScript UI.
- YAML self-service concept.
- JMeter Freemarker templates as migration inputs.
- SLA evaluation logic where it remains correct.
- Report-generation concepts.
- PostgreSQL support.
- Jenkins and GitHub Actions templates.
- Docker Compose developer workflow.
- Helm and Karpenter foundations.
- Load, spike and endurance concepts.

Do not preserve flawed coupling merely for compatibility.

### Components that must be replaced or isolated

- `TestRunService.executeAsync(...)` as the durable orchestration mechanism.
- Production use of `ProcessBuilder` inside `platform-core`.
- Local filesystem report paths persisted as durable artifact references.
- Team YAML API keys.
- Authentication that treats any valid key as authorized for every team.
- Public report endpoints.
- In-memory YAML as the authoritative test-definition registry.
- A JMeter-specific platform domain.
- Direct heap/thread dump work inside the normal run path.
- Flattening all scenarios into one endpoint list.

---

## 6. Working protocol

### Before each increment

1. Run `git status --short`.
2. Identify pre-existing user changes and preserve them.
3. State the increment, files likely to change and acceptance criteria.
4. Inspect relevant code before proposing edits.
5. Prefer the smallest coherent increment.

### During each increment

- Keep the build green as often as practical.
- Do not combine architecture refactoring with unrelated dependency upgrades.
- Do not silently change public APIs.
- Add characterization tests before moving behavior.
- Use compatibility adapters while V1 and V2 coexist.
- Do not write secrets, tokens, credentials or internal target URLs into source control.
- Do not use destructive Git commands.
- Do not push, open a pull request, merge or publish an image without explicit user authorization.
- Do not edit unrelated code to make a diff look cleaner.

### After each increment

Before reporting, update the handoff documents and commit the completed increment unless the user
explicitly says not to commit. The handoff set is:

1. `HANDOFF.md`.
2. `NEXT-SESSION.md`.
3. `ROADMAP.md`.
4. `PLAN.md`.

The commit must include the code and handoff documents together. Do not push, tag, merge or publish
without explicit user authorization.

Report:

1. Outcome.
2. Files created/changed.
3. Architecture decisions implemented.
4. Commands executed and results.
5. Tests added.
6. Known gaps or risks.
7. Exact next recommended increment.

Stop after the requested increment unless the user explicitly asked to continue.

---

## 7. Git and migration strategy

Recommended branch:

`feature/platform-v2-foundation`

Recommended POC tag:

`v1-poc`

Do not create or push the branch/tag without authorization. If already on a user-created branch, remain there unless instructed otherwise.

Use parallel migration:

- Keep `/api/v1/**` operational during the V2 foundation work.
- Introduce `/api/v2/**` for the new resource model.
- Keep the current local JMeter executor only as a local/development adapter.
- Route production execution through the new `ExecutionProvider`.
- Remove V1 components only after V2 functional parity and explicit approval.

Do not rename `com.perfplatform` or Maven coordinates in the same increment as the architecture migration. Corporate naming can be a separate decision.

---

## 8. Engineering standards

### Java and Spring Boot

- Preserve the current Java/Spring Boot baseline during the first structural increment.
- Do not combine Java/Spring upgrades with the initial module extraction.
- Use constructor injection.
- Keep the domain module free from Spring, JPA and Kubernetes types.
- Use immutable value objects or records where practical.
- Model state transitions explicitly.
- Use UTC `Instant` for persisted timestamps.
- Use UUIDs for externally visible resource IDs.
- Never expose JPA entities as REST responses.
- Use Jakarta Bean Validation at API boundaries.
- Use RFC 7807 problem responses.
- Use OpenAPI documentation for V2 APIs.
- Use Flyway migrations; disable `ddl-auto:update` outside local-only development.
- Use optimistic locking where concurrent state transitions are possible.

### React and TypeScript

- Preserve existing UI behavior during backend refactoring.
- Keep API access behind typed service clients.
- Generate or maintain types from the V2 OpenAPI contract when practical.
- Do not embed engine-specific JMeter terminology in general self-service screens.
- Add accessible labels, keyboard behavior and clear validation summaries.
- Do not add browser recording or AI generation UI in the foundation phase.

### General code quality

- Prefer focused classes and components.
- Avoid files that mix persistence, orchestration, engine compilation and HTTP concerns.
- Add JavaDoc/TSDoc where a contract or non-obvious invariant exists.
- Do not create generic `Utils` dumping grounds.
- Keep generated files clearly separated.
- Do not log request authorization headers, cookies, credentials, secret values or full sensitive payloads.
- Use structured log fields such as `runId`, `teamId`, `definitionId`, `shardId` and `engine`.

### Dependencies

- Reuse existing dependencies where suitable.
- Every new dependency needs a short justification.
- Prefer mature, approved libraries.
- Pin runner image versions.
- Do not use `latest` tags.
- Do not introduce k6.
- Keep cloud-specific libraries behind interfaces.

---

## 9. Target modular structure

Build a modular monolith. The exact extraction may be incremental, but dependencies must point inward.

```text
performance-testing-platform/
├── platform-domain/
├── platform-engine-spi/
├── platform-persistence/
├── platform-orchestration/
├── platform-results/
├── platform-api/
├── platform-core/                 # Spring Boot composition root
├── engines/
│   ├── engine-jmeter/
│   ├── engine-playwright/         # Later phase
│   └── engine-browserstack/       # Optional later phase
├── platform-importers/
│   ├── importer-openapi/
│   ├── importer-postman/
│   ├── importer-har/
│   ├── importer-curl/
│   └── importer-playwright/
├── runner-images/
│   ├── jmeter-runner/
│   └── playwright-runner/
├── specification/
│   ├── schema/
│   └── examples/
├── platform-ui/
├── helm/
│   ├── performance-control-plane/
│   └── performance-runner-cell/
└── docs/
    ├── architecture/
    ├── adr/
    ├── api/
    ├── operations/
    └── migration/
```

### Dependency rules

| Module | Allowed dependencies |
|---|---|
| `platform-domain` | Java standard library and narrow validation libraries only |
| `platform-engine-spi` | `platform-domain` |
| `platform-persistence` | `platform-domain`, Spring Data, Flyway |
| `platform-results` | `platform-domain`, result parsing libraries |
| `engine-jmeter` | `platform-domain`, `platform-engine-spi` |
| `platform-orchestration` | `platform-domain`, `platform-engine-spi` through interfaces |
| `platform-api` | `platform-domain`, orchestration use cases, Spring Web/Security |
| `platform-core` | All implementation modules; owns application bootstrapping |

Avoid:

- Domain depending on API DTOs.
- Domain depending on JPA annotations.
- Orchestration constructing JMeter commands directly.
- API controllers calling Spring Data repositories directly.
- Engine implementations changing run state directly in the database.

---

## 10. Core domain model

Implement an engine-neutral model.

### Main aggregates

- `Team`
- `Application`
- `TestDefinition`
- `TestDefinitionVersion`
- `PerformanceTestSpecification`
- `ExecutionPlan`
- `TestRun`
- `RunnerShard`
- `RunEvent`
- `ObjectiveResult`
- `ArtifactReference`
- `Approval`

### Required identifiers

- `TeamId`
- `ApplicationId`
- `TestDefinitionId`
- `TestDefinitionVersionId`
- `ExecutionPlanId`
- `TestRunId`
- `RunnerShardId`
- `ArtifactId`

Prefer strongly typed identifiers rather than passing arbitrary strings throughout the domain.

### Specification structure

The canonical model must support:

- `apiVersion`
- `kind`
- metadata: name, team, application, labels
- environment and runner-cell selection
- one or more imported sources
- authentication references
- datasets and shard-partitioning rules
- one or more protocol/browser workloads
- staged load model
- weighted scenarios
- objectives/thresholds
- observability settings
- governance settings

Publish:

`specification/schema/v1alpha1/performance-test.schema.json`

Provide at least:

- A valid API-only YAML example.
- A valid Playwright-only YAML example.
- A valid hybrid YAML example.
- Invalid fixtures for validation tests.

### Validation model

Validation findings contain:

- `code`
- `severity`: `INFO`, `WARNING`, `ERROR`
- `path`
- `message`
- optional remediation

Validation layers:

1. Schema.
2. Semantic relationships.
3. Policy.
4. Engine capability.
5. Connectivity preflight.
6. Dry run.

Do not mix network preflight into pure schema validation.

### Immutability and digest

- A test-definition version is immutable after creation.
- Canonicalize the specification before hashing.
- Store a SHA-256 digest.
- An `ExecutionPlan` references the specification digest.
- A `TestRun` references an immutable definition version and plan digest.
- Reject runner results whose plan digest does not match.

---

## 11. Durable run-state model

### States

Use explicit states:

- `DRAFT`
- `VALIDATING`
- `REJECTED`
- `AWAITING_APPROVAL`
- `QUEUED`
- `PROVISIONING`
- `RUNNING`
- `ANALYZING`
- `COMPLETED`
- `FAILED`
- `CANCELLED`
- `EXPIRED`

If `DRAFT` belongs only to definitions rather than runs, document that distinction and keep run states internally consistent.

### Allowed run transitions

At minimum:

| Current | Allowed next states |
|---|---|
| `VALIDATING` | `REJECTED`, `AWAITING_APPROVAL`, `QUEUED`, `CANCELLED` |
| `AWAITING_APPROVAL` | `QUEUED`, `REJECTED`, `CANCELLED`, `EXPIRED` |
| `QUEUED` | `PROVISIONING`, `CANCELLED`, `EXPIRED` |
| `PROVISIONING` | `RUNNING`, `FAILED`, `CANCELLED` |
| `RUNNING` | `ANALYZING`, `FAILED`, `CANCELLED` |
| `ANALYZING` | `COMPLETED`, `FAILED` |

Terminal states do not transition without an explicit administrative recovery workflow.

### State-transition requirements

- Centralize transition validation.
- Persist an append-only `RunEvent` for every transition.
- Use optimistic locking.
- Include actor/system identity and reason.
- Make cancellation idempotent.
- Do not infer completion only from controller memory.
- Reconciliation must recover after control-plane restart.

---

## 12. Persistence design

Use Flyway migrations.

### Minimum production tables

#### `teams`

- `id`
- `name`
- `status`
- audit timestamps

#### `applications`

- `id`
- `team_id`
- `name`
- environment/policy metadata
- audit timestamps

#### `test_definitions`

- `id`
- `team_id`
- `application_id`
- `name`
- `latest_version`
- audit metadata

#### `test_definition_versions`

- `id`
- `definition_id`
- `version_number`
- `specification_json` as JSONB
- `specification_digest`
- `validation_status`
- `approval_status`
- creator/approver metadata
- immutable creation timestamp

#### `execution_plans`

- `id`
- `definition_version_id`
- `engine`
- `plan_json`
- `plan_digest`
- capacity estimate
- creation timestamp

#### `test_runs`

- `id`
- `team_id`
- `definition_version_id`
- `execution_plan_id`
- `status`
- `environment`
- `idempotency_key`
- requested/queued/started/completed timestamps
- requested-by identity
- cancellation metadata
- failure code and sanitized reason
- optimistic-lock version

Unique constraint:

`(team_id, idempotency_key)`

#### `runner_shards`

- `id`
- `run_id`
- `engine`
- `shard_index`
- `shard_count`
- `status`
- external Job UID/name
- heartbeat timestamp
- started/completed timestamps
- exit code
- failure code
- optimistic-lock version

Unique constraint:

`(run_id, shard_index)`

#### `run_events`

- sequence ID
- run ID
- event type
- previous/new state
- actor
- sanitized detail JSON
- timestamp

Append-only.

#### `artifacts`

- `id`
- `run_id`
- optional shard ID
- artifact type
- object key/URI
- digest
- content type
- size
- retention/expiration timestamp

Do not store pre-signed URLs.

#### `objective_results`

- `id`
- `run_id`
- objective identifier
- observed value
- threshold
- pass/fail
- evaluation detail

### Migration rules

- Do not rely on Hibernate `ddl-auto:update` in production.
- Local H2 support may remain temporarily, but PostgreSQL/Testcontainers is the authoritative integration-test database.
- Avoid database-specific logic in domain services.
- If using PostgreSQL locking for queue claims, isolate it in the persistence adapter and test concurrent claims.

---

## 13. Orchestration and admission control

### Required ports

Define interfaces such as:

```java
public interface TestRunRepository {
    Optional<TestRun> find(TestRunId id);
    TestRun save(TestRun run);
    List<TestRun> claimQueuedRuns(int limit, Instant now);
}

public interface ExecutionProvider {
    ProvisioningResult provision(ExecutionPlan plan, TestRun run);
    ExecutionStatus inspect(ExecutionHandle handle);
    CancellationResult cancel(ExecutionHandle handle);
}

public interface ArtifactStore {
    ArtifactUploadGrant createUploadGrant(ArtifactDescriptor descriptor);
    ArtifactReference confirmUpload(ArtifactUploadReceipt receipt);
    AuthorizedDownloadGrant createDownloadGrant(ArtifactReference reference, CallerContext caller);
}

public interface AdmissionPolicy {
    AdmissionDecision evaluate(AdmissionRequest request, CapacitySnapshot capacity);
}
```

Exact names may vary; preserve separation.

### Queue claiming

For initial scale, use PostgreSQL-backed claiming rather than a new message broker.

Requirements:

- Multiple API replicas must not claim the same run.
- Claims must expire or be recoverable when a scheduler dies.
- A claimed run must have a durable owner/lease.
- Queue order should be deterministic and auditable.
- Per-team fairness must prevent one team from occupying all ten global slots.

Suggested policy:

- Global maximum: 10 material runs.
- Default per-team maximum: 1 material run.
- Policy-approved per-team maximum: 2.
- Normal operating target: 3–4.
- Browser slots counted separately.

### Reconciliation

The reconciler:

1. Claims eligible queued runs.
2. Compiles or loads the immutable execution plan.
3. Requests runner provisioning.
4. Persists external handles before moving to `RUNNING`.
5. Inspects active shards.
6. Detects stale heartbeats.
7. Moves completed execution to `ANALYZING`.
8. Normalizes artifacts and evaluates objectives.
9. Moves the run to a terminal state.

Reconciliation operations must be idempotent.

---

## 14. Engine SPI

Use engine capability contracts rather than engine-specific branching in controllers.

Minimum conceptual contract:

```java
public interface ExecutionEngineProvider {
    String engineId();
    EngineCapabilities capabilities();
    ValidationResult validate(PerformanceTestSpecification specification);
    CompiledWorkload compile(ExecutionPlan plan);
    List<RunnerShardPlan> shard(
        CompiledWorkload workload,
        CapacityProfile capacityProfile
    );
    NormalizedRunResult normalize(RunnerArtifactSet artifacts);
}
```

Capabilities should describe:

- Protocol vs browser.
- Supported source types.
- Supported workload models.
- Maximum configured concurrency.
- Correlation/extraction support.
- Distributed/sharded execution support.
- Required runner image.

Do not allow an engine provider to:

- Authorize a user.
- Select a team.
- Override global quotas.
- Mutate run state directly.
- Return local filesystem paths as durable artifact references.

---

## 15. JMeter implementation instructions

### Preserve local development

Create two execution adapters:

1. `LocalJmeterExecutionProvider`
2. `KubernetesJmeterExecutionProvider`

`LocalJmeterExecutionProvider` may use `ProcessBuilder` only under an explicit local/development profile.

Local execution requirements:

- Pass commands as an argument list, never a shell string.
- Apply a timeout based on plan duration plus grace.
- Capture and sanitize stdout/stderr.
- Destroy child processes on cancellation/shutdown.
- Never log secrets or full authorization headers.
- Store outputs through `ArtifactStore`.

### Correct the current compiler limitations

The current implementation flattens all scenarios and always reads load scenarios. Replace this behavior.

The JMeter compiler must preserve:

- Scenario boundaries.
- Scenario weights.
- Workload stages.
- Methods, paths, query parameters, headers and body templates.
- Correlation/extraction rules.
- Think time.
- Assertions.
- Dataset references.
- Shard index/count.
- Run correlation header.

### Sharding

Prefer autonomous JMeter Jobs over remote RMI engines.

Each shard receives:

- Run ID.
- Shard ID/index/count.
- Immutable plan URI or mounted plan.
- Plan digest.
- Non-overlapping dataset partition.
- Correlation header/value.
- Artifact upload configuration.
- Duration and emergency-stop policy.

Start with one shard by default. Increase shard count only through a calibrated capacity profile.

Do not assume one JMeter thread always represents one business user accurately. Document pacing and concurrency semantics.

### Runner outputs

Every JMeter shard must produce:

- `runner-manifest.json`
- `results.jtl` or approved CSV format
- `jmeter.log`
- `summary.json`
- generated JMX
- optional compressed JMeter HTML report

`runner-manifest.json` contains:

```json
{
  "runId": "uuid",
  "shardId": "uuid",
  "shardIndex": 0,
  "shardCount": 1,
  "engine": "jmeter",
  "planDigest": "sha256:...",
  "startedAt": "ISO-8601",
  "completedAt": "ISO-8601",
  "exitCode": 0,
  "status": "COMPLETED",
  "artifacts": []
}
```

The analysis layer verifies run, shard and plan identity before accepting results.

---

## 16. Kubernetes runner contract

Use a supported Java Kubernetes client behind the `ExecutionProvider`. If the approved client is not known, implement the interface and fake adapter first, then pause for the dependency decision.

### Job requirements

Every Job must include:

- Labels for run ID, team ID, engine, shard index and managed-by.
- An immutable plan digest annotation.
- Explicit CPU/memory requests and limits.
- A dedicated service account.
- `restartPolicy: Never`.
- Normally `backoffLimit: 0`.
- `activeDeadlineSeconds`.
- Finished-Job TTL.
- Non-root execution.
- Read-only root filesystem where compatible.
- `allowPrivilegeEscalation: false`.
- Dropped Linux capabilities.
- Runtime-default seccomp profile.
- Temporary writable workspace using `emptyDir`.
- No embedded secret values in labels, annotations or command arguments.

### Job naming

Names must be deterministic enough for reconciliation but safe for Kubernetes length limits. Include a short run identifier and shard index. Persist the returned Job UID.

### Retry rule

Never automatically replay a material performance test after a runner failure unless the execution policy explicitly allows it. A Kubernetes pod restart can produce duplicate load and invalid results.

### Cancellation

Cancellation must:

1. Persist desired cancellation.
2. Request Job deletion/termination.
3. Wait for observed termination or timeout.
4. Mark incomplete artifacts.
5. Produce a `CANCELLED` terminal event.

### Remote runner cells

Do not implement inbound control-plane access to team clusters in the first standalone increment.

Preserve a future outbound runner-agent protocol:

- Runner agent authenticates with a scoped service identity.
- Agent leases work for its cell.
- Agent submits local Jobs.
- Agent reports status/heartbeats outbound.
- Artifacts upload directly to approved object storage.

---

## 17. Artifact storage

Provide:

- `FileSystemArtifactStore` for local development.
- `S3ArtifactStore` or approved S3-compatible implementation for production.

Production requirements:

- Encrypted storage.
- Team/run/shard-prefixed immutable object keys.
- Digest and content-type verification.
- Retention metadata.
- Authorization on every download.
- Time-limited download grants.
- No permanent public URLs.
- No raw storage credentials in runner configuration.

Suggested key form:

```text
teams/{teamId}/runs/{runId}/
  plan/
  shards/{shardId}/
  analysis/
  report/
```

The database stores stable object references, not ephemeral signed URLs.

---

## 18. Results and objective evaluation

Normalize engine output into an engine-neutral model.

Minimum normalized metrics:

- Request count.
- Throughput.
- Error count/rate.
- Minimum/maximum/mean.
- p50, p90, p95 and p99 latency.
- Connect/TLS timing when available.
- Browser journey duration.
- Browser step duration.
- Web-vital-style metrics when reliably captured.

Objective evaluation must be:

- Deterministic.
- Re-runnable from stored artifacts.
- Scoped to workload/scenario/operation when configured.
- Explicit when data is incomplete.
- Unable to report `PASS` when required shards are missing.

Preserve useful code from `SlaEvaluator`, but move it behind the normalized result model and add characterization tests.

---

## 19. V2 REST API

Do not expose persistence entities.

### Initial endpoints

```text
POST   /api/v2/test-definitions/validate
POST   /api/v2/test-definitions
GET    /api/v2/test-definitions/{definitionId}
POST   /api/v2/test-definitions/{definitionId}/versions
POST   /api/v2/test-runs
GET    /api/v2/test-runs/{runId}
GET    /api/v2/test-runs/{runId}/events
POST   /api/v2/test-runs/{runId}/cancel
GET    /api/v2/test-runs/{runId}/report
GET    /api/v2/test-runs/{runId}/artifacts
```

### Trigger requirements

`POST /api/v2/test-runs`:

- Requires an idempotency key.
- References an immutable definition version.
- Accepts only permitted runtime overrides.
- Returns `202 Accepted`.
- Returns a `Location` header.
- Does not block until the test finishes.

### Error model

Use RFC 7807 problem details with:

- Stable error code.
- HTTP status.
- Human-readable detail.
- Correlation ID.
- Field validation findings where relevant.

Do not expose stack traces, SQL errors, Kubernetes objects or secret-provider details.

### Streaming status

Server-Sent Events is sufficient for initial live status.

- Resume using event ID where practical.
- Authorize the run before subscribing.
- Fall back to polling.
- Do not make SSE the durable event store.

---

## 20. Security and enterprise integration

### Authorization model

Create a caller model:

- Subject.
- Team memberships.
- Roles.
- Scopes.
- Service-vs-human identity.

Required scopes include concepts such as:

- `definitions:read`
- `definitions:write`
- `runs:execute`
- `runs:cancel`
- `runs:read`
- `approvals:grant`
- `platform:admin`

Authorization is based on both scope and team/resource ownership.

### Human and CI identities

Target:

- Corporate OIDC/OAuth2 for humans.
- Scoped service identities for Jenkins.

Do not make Jenkins reuse a person's credential.

If the exact identity provider is unresolved:

- Define the authorization domain and Spring Security integration seams.
- Provide a local-only development identity provider.
- Ensure production refuses insecure development authentication.
- Pause before wiring provider-specific claims.

### Secret references

Specifications contain values such as:

```yaml
secretRef: vault://performance/proposal/client
```

They never contain:

- OAuth secrets.
- Passwords.
- Cookies.
- API keys.
- BrowserStack credentials.
- Dynatrace tokens.

Create a `SecretResolver` port. Provider-specific implementations are later decisions.

### Target safety

Implement server-side controls:

- Hostname/CIDR allowlist.
- Environment classification.
- Maximum virtual users.
- Maximum duration.
- Global/team concurrency.
- Production approval rule.
- Emergency stop.
- DNS revalidation before execution.
- Blocking of metadata/link-local and prohibited ranges by default.

Do not trust values embedded in generated JMX or imported Playwright code to enforce quotas.

---

## 21. Playwright and hybrid execution

Implement after the API/JMeter foundation is stable.

### MVP import

Accept:

- Repository identifier.
- Immutable commit SHA.
- Test/config path.
- Optional project name.
- Approved runner image/version.

Reject:

- Unpinned moving branches for approved definitions.
- Arbitrary shell commands.
- User-provided runner images outside the allowlist.
- Paths that escape the checked-out workspace.

### Playwright runner

The runner must:

- Use a pinned approved Playwright image.
- Install dependencies deterministically.
- Inject environment/base URL through controlled inputs.
- Inject the performance run correlation ID.
- Capture HTML report.
- Capture traces according to policy.
- Capture screenshots on failure.
- Capture sanitized console/network evidence.
- Upload artifacts through the common artifact contract.

### Hybrid run

A hybrid run coordinates:

1. JMeter ramp-up.
2. Steady-state confirmation.
3. Small Playwright browser cohort.
4. Browser journey completion.
5. JMeter ramp-down.
6. Combined analysis.

Do not model 1,000 Playwright workers as 1,000 ordinary virtual users. Default browser concurrency is 5, with an initial policy maximum of 20.

### BrowserStack

Add BrowserStack as an optional provider behind the same Playwright execution contract. Local Playwright must remain functional without BrowserStack.

---

## 22. Dynatrace integration

Create an `ObservabilityProvider` abstraction.

For Dynatrace:

- Inject `X-Performance-Test-Run-Id` or the approved equivalent.
- Configure request-attribute capture outside source when required.
- Associate team, application, environment, definition version and stage.
- Query only the test time window plus a bounded lead/lag.
- Enrich reports with service latency, failure rate, CPU, memory, database time and downstream calls where available.
- Treat Dynatrace unavailability as degraded enrichment, not loss of raw performance results.
- Use timeouts, retries with bounds and sanitized errors.
- Resolve tokens through `SecretResolver`.

Do not implement direct heap dump collection in the normal test run.

If diagnostics are later required:

- `ThreadDumpDiagnosticProvider`
- `HeapDumpDiagnosticProvider`

Both require explicit target approval, separate authorization, encrypted storage and retention policy.

---

## 23. Importers

Every importer produces a draft canonical specification plus validation findings.

### OpenAPI

- Import operations, methods, paths, parameters and schemas.
- Do not invent valid business data silently.
- Allow user mapping of examples/datasets.
- Preserve operation IDs.

### Postman

- Import requests, variables, folders and basic test assertions where safe.
- Flag unsupported scripts.
- Do not execute arbitrary Postman scripts in the control plane.

### HAR

- Sanitize cookies, authorization and sensitive query parameters.
- Group requests into candidate journeys.
- Require review before load execution.

### cURL

- Parse arguments without shell execution.
- Strip or redact secrets.
- Produce one or more manual endpoint steps.

### Manual

- Typed builder for method, path, headers, query, body, extraction and assertions.

### Playwright

- Import metadata and source reference.
- Validate build/test discovery in a restricted runner.
- Store immutable source reference and validation result.

---

## 24. UI instructions

Do not redesign everything during backend foundation work.

Progressively introduce:

1. Definitions list.
2. Guided definition wizard.
3. Import-source selection.
4. Workload model.
5. Objectives.
6. Validation findings.
7. Execution-plan preview.
8. Run status/timeline.
9. Report and comparison.

The UI must use consumer language:

- Expected users.
- Requests/transactions per second.
- Ramp-up.
- Steady state.
- Journey mix.
- Response-time objective.
- Error-rate objective.

Avoid exposing:

- Thread Groups.
- JMX XML.
- Backend Listeners.
- JMeter controller details.
- Kubernetes Job configuration.

Provide an advanced read-only generated-plan view for experts.

---

## 25. Testing strategy

### Unit tests

At minimum:

- Specification parsing.
- Canonicalization/digest.
- Validation findings.
- State-transition matrix.
- Admission-policy limits.
- Idempotent trigger behavior.
- Scenario weighting.
- Shard/data partitioning.
- JMeter plan generation.
- SLA/objective normalization.
- Authorization decisions.

### Integration tests

Use PostgreSQL Testcontainers for:

- Flyway migrations.
- Definition/version persistence.
- Concurrent queue claiming.
- Idempotency constraint.
- Optimistic locking.
- Run-event append behavior.

Test the Kubernetes adapter using an approved mock server or a fake `ExecutionProvider` before requiring a real cluster.

### Contract tests

Contract-test:

- Runner Job manifest.
- Runner manifest/result schema.
- Artifact upload/confirmation.
- Execution provider SPI.
- V2 OpenAPI.

### End-to-end tests

Local E2E:

1. Create definition.
2. Validate.
3. Trigger with idempotency key.
4. Execute through fake or local JMeter provider.
5. Observe transitions.
6. Upload/normalize results.
7. Evaluate objectives.
8. Retrieve authorized report.
9. Cancel a second run.

Kubernetes E2E can run later using kind/minikube or an approved test cluster.

### Capacity validation

Do not generate 10,000 virtual users in ordinary CI.

Create a separate calibration plan:

- Determine safe users/transactions per runner pod.
- Measure generator CPU, memory, network and GC.
- Determine shard profile.
- Validate 3–4 normal and 10 peak concurrent runs in a controlled environment.
- Confirm the load generators are not the bottleneck.

---

## 26. Observability of the platform

Expose platform metrics such as:

- Runs requested/queued/running/completed/failed.
- Queue depth and age.
- Active runs by team and engine.
- Admission rejections.
- Provisioning duration.
- Runner heartbeat age.
- Artifact upload failures.
- Analysis duration.
- Objective pass/breach.
- Reconciliation errors.

Use bounded labels. Do not use run ID as an unbounded Prometheus label.

Structured logs may include run ID as a searchable field.

Add health indicators for:

- PostgreSQL.
- Artifact store.
- Kubernetes/runner provider.
- Dynatrace enrichment provider.

Readiness should distinguish essential dependencies from optional enrichment providers.

---

## 27. Documentation and ADRs

Create and maintain:

- `docs/adr/001-control-execution-separation.md`
- `docs/adr/002-postgresql-durable-orchestration.md`
- `docs/adr/003-engine-spi.md`
- `docs/adr/004-object-artifact-storage.md`
- `docs/adr/005-deployment-profiles.md`
- `docs/migration/v1-to-v2.md`
- `docs/operations/run-lifecycle.md`
- `docs/operations/cancellation-and-recovery.md`
- `docs/operations/runner-calibration.md`
- `docs/api/v2-examples.md`

Each ADR contains:

- Context.
- Decision.
- Alternatives considered.
- Consequences.
- Status.

Do not rewrite the target architecture into each ADR. Link to the relevant section.

---

## 28. Increment plan

Implement one gated increment at a time.

### Increment 0 — Baseline and protection

#### Tasks

- Verify baseline commit/branch.
- Run backend and UI builds/tests.
- Record current failures without hiding them.
- Add characterization tests around:
  - Triggering a V1 run.
  - Status persistence.
  - SLA evaluation.
  - JMX generation.
  - API-key team leakage behavior as a known failing security test or documented gap.
- Copy the target architecture into `docs/architecture/`.
- Add initial ADR directory.
- Document the migration branch and compatibility strategy.

#### Do not

- Move production classes.
- Change V1 behavior.
- Add Kubernetes client.
- Add Playwright.

#### Acceptance

- Baseline commands and results are documented.
- Existing behavior has enough tests to refactor safely.
- No unrelated behavior change.

### Increment 1A — Modular skeleton

#### Tasks

- Add Maven modules:
  - `platform-domain`
  - `platform-engine-spi`
  - `platform-persistence`
  - `platform-orchestration`
  - `platform-results`
  - `platform-api`
  - `engine-jmeter`
- Keep `platform-core` as the Spring Boot composition root.
- Establish dependency direction.
- Move no more behavior than necessary to prove module wiring.
- Add architecture/module tests where feasible.
- Keep the current application bootable.

#### Acceptance

- `mvn verify` succeeds.
- The application starts.
- Existing V1 endpoints remain available.
- Domain and SPI modules contain no Spring/JPA/Kubernetes dependencies.
- No circular Maven dependency exists.

### Increment 1B — Canonical specification

#### Tasks

- Implement V1-alpha canonical domain.
- Add JSON Schema and YAML examples.
- Add schema/semantic validation.
- Add canonical digest.
- Implement `/api/v2/test-definitions/validate`.
- Add definition/version persistence through Flyway.
- Reject secret values in known credential fields.

#### Acceptance

- Valid API, browser and hybrid examples parse.
- Invalid fixtures return stable findings.
- Identical canonical specifications produce identical digest.
- A version cannot be mutated after creation.
- V1 API remains functional.

### Increment 1C — Durable run orchestration

#### Tasks

- Implement run/shard/event tables.
- Implement state-transition rules.
- Add idempotent V2 trigger.
- Add queue claiming and global/team admission.
- Add cancellation.
- Implement fake execution provider.
- Add reconciliation tests including restart simulation.

#### Acceptance

- Duplicate idempotency key returns the same logical run.
- Two reconcilers cannot claim the same run.
- Invalid transitions fail.
- Every transition creates an event.
- Cancellation is idempotent.
- Ten queued team runs are admitted according to configured policy.

### Increment 1D — JMeter engine SPI and local provider

#### Tasks

- Move compilation behind engine SPI.
- Preserve scenarios and workload stages.
- Implement local-only provider.
- Normalize JMeter output.
- Route artifacts through local artifact-store abstraction.
- Adapt V2 execution to local provider.

#### Acceptance

- V2 API-only definition runs locally end to end.
- Generated plan carries run correlation ID.
- Current flattening defect is removed.
- Cancellation stops the local process.
- Objective evaluation is reproducible.

### Increment 1E — Kubernetes JMeter provider

#### Tasks

- Implement Kubernetes Job manifest builder/provider.
- Add resource/security/retry policy.
- Persist Job UID and shard handles.
- Implement observe/cancel/reconcile.
- Add S3-compatible artifact adapter or approved production stub.
- Add Helm settings and RBAC.

#### Acceptance

- Manifest contract tests pass.
- Fake-cluster integration verifies create/inspect/cancel.
- Control-plane pod restart does not lose active run state.
- No JMeter process runs inside the control-plane container in production profile.
- A failed Job is not silently replayed.

### Increment 1F — V2 UI foundation

#### Tasks

- Add definition list/wizard for manual API specifications.
- Add workload/objective forms.
- Add validation preview.
- Add run timeline and report link.
- Preserve V1 UI or provide a controlled migration path.

#### Acceptance

- UI-generated payload matches the canonical specification.
- Same saved definition can be executed via UI, API and YAML.
- Validation findings are accessible and understandable.

### Increment 2 — API importers and mature self-service

Implement:

- OpenAPI.
- Postman.
- HAR.
- cURL.
- Manual correlation/extraction.
- Dataset partitioning.
- Dry-run preflight.
- Baseline comparisons.
- Jenkins shared-library integration.

Exit:

- A consumer can onboard an API without writing JMX.

### Increment 3 — Playwright and hybrid runs

Implement:

- Immutable Playwright source import.
- Restricted runner.
- Artifacts and normalized browser result.
- Hybrid coordination.
- Optional BrowserStack provider.

Exit:

- A reviewed Playwright journey executes during JMeter steady state and produces combined evidence.

### Increment 4 — Enterprise integration and federation

Implement:

- Corporate OIDC.
- CI service identities.
- Team RBAC.
- Approved secret manager.
- Dynatrace provider.
- Shared control-plane/team-runner profile.
- Central release and upgrade process.

### Increment 5 — Assisted authoring

Implement only after governance:

- Recorder.
- AI-assisted draft generation.
- Diff/review.
- Historical recommendations.

No unreviewed AI-generated material tests.

---

## 29. Build and verification commands

Discover and use existing wrappers if present.

Typical backend commands:

```bash
mvn -version
mvn clean verify
mvn -pl platform-core -am test
```

Typical UI commands:

```bash
cd platform-ui
npm ci
npm run lint
npm run build
```

Infrastructure validation:

```bash
docker compose -f docker/docker-compose.yml config
helm lint helm/performance-platform
```

Repository hygiene:

```bash
git diff --check
git status --short
```

If a command cannot run because of environment restrictions:

- Do not claim it passed.
- Record the exact blocker.
- Run the strongest safe alternative.
- Keep unverified assumptions explicit.

---

## 30. Required completion report

At the end of each increment, respond using this structure:

```markdown
## Outcome

What is now working.

## Architecture compliance

- Decisions implemented
- Decisions intentionally deferred

## Files changed

- Exact paths and purpose

## Verification

- Command
- Result

## Tests added

- Test and behavior protected

## Risks or open decisions

- Only material items

## Next increment

- Exact recommended scope
```

Do not say “done” when:

- Tests were not run.
- A production adapter is only a stub.
- Security is local-development-only.
- Artifact storage remains pod-local in production.
- V2 executes through the old `@Async` orchestration.

---

## 31. Pause conditions

Stop and ask the user before:

- Choosing a corporate identity provider.
- Choosing or connecting a secret manager.
- Introducing an unapproved open-source dependency.
- Introducing k6.
- Enabling production or unrestricted internet targets.
- Creating cross-account/network access.
- Choosing a production S3 bucket/account.
- Using real Ameriprise credentials or endpoints.
- Enabling BrowserStack writes/execution with real credentials.
- Pushing a branch or opening a pull request.
- Deleting V1 functionality.
- Changing the current Spring Boot/Java baseline during the foundation refactor.

Do not stop for small reversible implementation details that are already determined by the architecture.

---

## 32. Definition of platform-foundation complete

The foundation is not complete until:

- UI, REST and YAML use one canonical specification.
- Definition versions are immutable.
- Run lifecycle is durable across control-plane restarts.
- Idempotency and cancellation work.
- Admission control enforces global and team limits.
- Production execution is outside the control-plane process.
- JMeter is integrated through the engine SPI.
- Playwright has a defined compatible SPI path.
- Artifacts are durable and authorized.
- Results are normalized and objectives deterministic.
- Team authorization is enforced.
- Secrets are references only.
- V1 migration is documented.
- Standalone mode works.
- Shared-runner mode remains architecturally possible.

---

## 33. First instruction to execute

Unless the user explicitly requests a later increment, begin with:

> Execute Increment 0 and Increment 1A only. Do not implement the canonical specification, Kubernetes provider, Playwright, Dynatrace or new UI yet. Protect current behavior, establish the modular skeleton, keep V1 operational, run all available verification and stop with the required completion report.

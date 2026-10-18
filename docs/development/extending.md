# Extend or rebrand the platform

Fork the repository and retain its module boundaries. The application is a modular monolith,
not a collection of separately deployed microservices. Java 17 is the compilation target; Java 21
is supported for building. The current `PerformanceTest` API-version namespace is historical and
remains stable for compatibility.

## UI and requirements

The React console lives in `platform-ui/src/v2`. Reuse the existing design tokens and studio
components. Draft state belongs in `studio/draft.ts`; `studio/specification.ts` is the single
translation into the canonical YAML model. New fields need schema/semantic validation and matching
API/YAML behavior. Do not create a second browser model in an API controller. Keep consumer labels
such as browsers, journeys and response-time objectives rather than JMeter/Kubernetes concepts.

## Engines and runners

Implement `ExecutionEngineProvider` from `platform-engine-spi`: capabilities, validate, compile,
render and parse. Register it in the composition root. Keep engine-neutral state and identifiers
in `platform-domain`, authorization/admission in orchestration, and result reduction/objectives in
`platform-results`. An engine must not authorize teams, override capacity or mutate run state.

A production execution adapter implements `ExecutionProvider`; the Kubernetes adapter submits an
isolated immutable Job per shard and persists the external UID. Runner images fetch approved plans,
verify identity/digests, execute once, publish progress and upload evidence. They never receive DB
credentials or a control-plane token. Add the pinned image to the operator's allowlist. A bare
upstream engine image does not implement the platform runner contract.

The Playwright local adapter and container share bootstrap, reporter and measurement hooks from
`engines/engine-playwright/src/main/resources/playwright-runner`. Build its Dockerfile from the
repository root. Change both the npm pin and matching Playwright base-image version together.
Run `npm --prefix runner-images/playwright-runner test` with Chromium installed; it tests real
cohort arithmetic, correlation, measurements, failure evidence, empty scripts and plan integrity.
Do not fabricate unavailable browser metrics or infer page metrics from journey timings.

## Storage, identity and integrations

- `ArtifactStore` owns run evidence. `BrowserSourceStore` owns content-addressed team scripts
  uploaded before a run. `DatasetStore`/catalog own versioned datasets. Local filesystem adapters
  are development-only; hosted replicas and runners need shared durable object storage.
- `CallerContextProvider` supplies subject, team memberships and scopes. The standalone service
  token adapter is in `platform-core/security`; corporate SSO belongs in another adapter using the
  same resource-authorization rules. A key authenticating somewhere never grants all-team access.
- `SecretResolver` resolves references at runtime. Never put credentials in specifications, source
  examples, plans, logs or provider manifests. Session acquisition and observability providers are
  separate ports; adding a telemetry integration must not control execution success.

## Tests and contracts

Run the backend reactor with Docker, UI tests/build, runner tests, Helm lint/template and
`git diff --check`. Add behavior tests for ownership, digest mismatch and failure paths when
changing source/execution contracts. Every transition remains durable and append-only; results
from missing shards cannot pass objectives. Keep migrations forward-only and add a PostgreSQL
integration test for database changes. Do not regenerate load automatically on retries.

See [CONTRIBUTING.md](../../CONTRIBUTING.md) for contribution scope and [the deployment guide](../deployment/standalone.md)
for cloud-specific settings. Record concrete verification and outstanding gaps in the handoff
rather than marking a module complete just because it compiles.

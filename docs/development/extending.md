# Extend or rebrand the platform

Fork the repository and retain its module boundaries. The application is a modular monolith,
not a collection of separately deployed microservices. Java 17 is the compilation target; Java 21
is supported for building. The current `PerformanceTest` API-version namespace is historical and
remains stable for compatibility.

## UI and requirements

For name/logo/colors/theme use [runtime branding](../deployment/branding.md) first; a deployment
does not need to fork a component. For infrastructure coordinates use
[company values](../../helm/performance-platform/examples/company-values.yaml) and
[configuration reference](../deployment/configuration-reference.md). Configuration and adapter
implementation are different extension paths: a new native storage API or corporate claims map
requires code and contract tests.

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

## A repeatable extension workflow

1. **Try configuration first.** Branding, infrastructure coordinates, pinned images, registry access,
   capacity and non-secret Spring settings already have supported configuration paths. Keep company
   values outside application source. Display branding does not rename the stable specification API
   version, Java packages or database schema.
2. **Choose the narrow port.** Use `platform-domain/.../port/ArtifactStore.java` for run evidence,
   `BrowserSourceStore.java` for reviewed scripts, dataset ports for data, or the engine SPI for a new
   workload engine. A native object-store extension must cover all applicable storage ports; swapping
   only report downloads leaves source uploads and datasets on the old provider.
3. **Implement in an adapter module.** Keep provider SDKs out of the plain Java domain. Reuse the
   existing identifiers, ownership and digest rules. Return stable object keys, not local file paths
   or permanent public links. A provider must preserve the metadata and bytes used by verification.
4. **Bind explicit settings.** Add typed properties in the composition layer, validate missing or
   unsafe combinations at startup, and select the adapter in
   `platform-core/.../v2/OrchestrationConfiguration.java`. Reuse existing environment/file binding or
   add a documented Helm value. Credentials enter through an approved secret provider/identity,
   never a public branding file or a specification.
5. **For identity, preserve authorization.** Map your validated issuer's claims into subject, team
   memberships, scopes and service/human identity. The implementation seam is
   `platform-api/.../v2/security/CallerContextProvider.java`, with authentication composed through
   Spring Security in `platform-core/security`. Configure one intended provider; merely adding a
   second bean or an `issuer-uri` property does not replace the standalone filter and claims map.
6. **For an engine, complete both ends.** Implement validation/capabilities, compilation, runner
   packaging, result normalization and objective support. Add the immutable image to the operator
   allowlist. Verify source/plan identity, zero-test/error paths, cancellation and artifacts under
   the actual non-root/read-only Job restrictions. No runner may mutate database run state.
7. **Test the real boundary.** Use contract tests plus the actual compatible service (or a scoped
   local service fixture) for storage/identity protocols. Test team denial and corrupt/missing
   evidence, not only successful SDK calls. Run the PostgreSQL tests for persistence changes and
   add Flyway migrations rather than changing a table through Hibernate.
8. **Prove portability.** Build the distribution from a clean clone, render the company values,
   exercise the UI/CLI/API flow on a disposable target, and document provider-specific routes,
   trust, retention and permissions. Mark mocks/local proofs honestly; do not claim a cloud account
   was tested because an adapter compiles.
9. **Publish a reviewable change.** Include code, schemas/examples, operator instructions, tests,
   migration/compatibility notes and handoff updates. Preserve immutable definition versions and
   API/YAML parity. Changing your provider must not accidentally replay an old material workload.

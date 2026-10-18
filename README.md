# Performance Testing Platform

A self-hosted performance testing platform for people who know their application's requirements,
but do not want to write JMeter plans or operate load generators by hand.

Import API requests or a reviewed Playwright journey, describe the traffic and objectives, run the
test, and read measured results with downloadable evidence. The web console, REST API and YAML
use the same immutable test specification. You can fork the code, change the UI, add integrations
and deploy it in your own infrastructure. [MIT licensed](LICENSE).

## Try it locally

Prerequisites: JDK 21 (the application targets Java 17), Maven, Node.js 22.22.2 or newer, Python 3,
and Docker for the PostgreSQL/object-storage integration tests. For real API load, install Apache
JMeter with its Custom Thread Groups plugin; the launcher checks/installs the plugin. Browser
execution uses the pinned Playwright runtime and Chromium, installed by the launcher.

```bash
git clone https://github.com/sanoopbhaskerv/performance-testing-platform.git
cd performance-testing-platform
# Set JAVA_HOME to your JDK installation if your environment does not already do so.
./scripts/start-platform.sh --build
```

Open `http://localhost:5173` and enter `local-dev-key-not-a-secret` in **Team access token**.
Choose **Performance tests → New test**:

- **Test an API**: import OpenAPI/Swagger, Postman, HAR, cURL, or enter requests manually. Review
  journeys, correlation/checks, traffic and objectives, validate, save and run.
- **Test web performance**: choose the `student-app` target, try the page-opening example or
  upload a reviewed Playwright script, start with one browser/one iteration, check, save and run.

[Browser walkthrough](docs/user-guide/browser-testing.md) ·
[Verified self-service review](docs/reviews/browser-self-service-2026-10-19.md) ·
[Configuration from requirements](docs/user-guide/configuration.md) ·
[API examples](docs/api/v2-examples.md) ·
[Recording API traffic](docs/operations/recording-locally.md)

The launcher starts the API, console, demo service and student web app. Ctrl-C stops them;
`./scripts/start-platform.sh --stop` cleans up a previous local session. `--build` rebuilds Java;
without it, existing jars are reused. Logs are under `.local/logs`.

**Check the execution banner.** The `fake` provider generates no traffic. Its runs are permanently
marked synthetic, objectives are INDETERMINATE and the run fails with `NO_LOAD_GENERATED`.
Those numbers are never evidence of performance. The real local adapters share your machine with
the API; Kubernetes runners isolate material execution for hosted use.

Local H2 storage is in memory. Use PostgreSQL and private S3-compatible object storage when runs
and definitions must survive a restart or be available to multiple replicas.

## Use your own application

Register a permitted non-production target for your team/environment in the console's About step
or through `POST /api/v2/targets`. The application name in your test resolves to that registered
address; a specification does not choose a new host for itself. Bring representative requests,
data and business assertions. Start small, inspect the results, then increase traffic.

Browser scripts use relative URLs, reviewed sources, and a separate browser budget. API users
and browsers are not interchangeable. Five concurrent material runs and one run per team are the
initial admission defaults. These limits are configurable; they are not a verified capacity claim.
[Calibrate your generators](docs/operations/runner-calibration.md) before trusting large-load results.

## Invoke it from an AI editor or CI

Read the portable [performance-testing skill](skills/performance-testing/SKILL.md). It works through
the public API and a Python 3 standard-library helper; it needs no hosted AI API or editor plugin.

```bash
export PERF_PLATFORM_URL=http://localhost:8080
export PERF_PLATFORM_TOKEN=local-dev-key-not-a-secret # local demo only
python3 scripts/perf.py validate --spec specification/examples/valid/demo-service.yaml
python3 scripts/perf.py create --spec specification/examples/valid/demo-service.yaml
# Use the immutable version.id returned above:
python3 scripts/perf.py run --version VERSION_UUID --idempotency-key demo-check-1
python3 scripts/perf.py wait --run RUN_UUID
python3 scripts/perf.py report --run RUN_UUID
```

For real credentials use `PERF_PLATFORM_TOKEN_FILE` or injected secrets, never a checked-in value.
[AI editor and CLI guide](docs/user-guide/ai-editors.md) explains uploads, versioning, cancellation,
exit codes and safe retries.

## Deploy in your own cloud

The portable deployment is a modular Spring Boot control plane serving the React console,
PostgreSQL, private S3-compatible object storage, and ephemeral JMeter/Playwright Kubernetes Jobs.
It does not require AWS, a corporate account, Dynatrace, or BrowserStack. Cloud-specific identity,
storage endpoints and policies are operator configuration.

[Standalone/cloud deployment](docs/deployment/standalone.md) covers image builds, existing Secrets,
scoped machine identities, Helm values, networking, storage, TLS, upgrades and verification. The
production profile refuses local load generation, filesystem artifacts and development identity.
Imported code must be reviewed and restricted by runner egress policy on a CNI that enforces it.

The supplied standalone identity adapter uses expiring team-scoped service tokens whose hashes
are mounted from an operator Secret. It supports automation and small self-hosted installations;
it does not implement a human SSO login. Corporate OIDC remains an extension behind
`CallerContextProvider`. Shared remote runner federation and BrowserStack are future work.

## Build and verify

```bash
mvn clean verify                        # JAVA_HOME must be set; Docker must be running
npm --prefix platform-ui ci
npm --prefix platform-ui test
npm --prefix platform-ui run build
npm --prefix platform-ui run lint
npm ci --prefix runner-images/playwright-runner
(cd runner-images/playwright-runner && npx playwright install chromium && npm test)
python3 scripts/perf.py --help
```

The first S3 integration test builds its optional MinIO fixture from the pinned official source
commit, verified by archive SHA-256, because upstream withdrew its old prebuilt images. Docker
needs access to GitHub and the Go module proxy for that build; Go is not required on the host.
The fixture retains MinIO's upstream license and is separate from your production object store.

The optional live Chrome-recorder test uses `-Drecorder.live=true`. PostgreSQL and MinIO tests
must actually run; skipped container tests do not prove integration. UI lint is part of verification alongside tests and the production build.

## Extend and contribute

[Extension guide](docs/development/extending.md) · [Contributing](CONTRIBUTING.md) ·
[Deployment guide](docs/deployment/standalone.md) · [Current handoff](HANDOFF.md)

The Java modules separate the domain, engine SPI, orchestration, API, persistence and results.
`platform-core` is the composition root. JMeter and Playwright implementations plug into the SPI;
runner images execute plans and upload artifacts rather than changing database run state.
The [original architecture](docs/architecture/performance-testing-platform-target-architecture.md)
contains historical enterprise requirements; the current product direction is open, self-hosted
standalone operation with extension seams for enterprise integrations. The existing API-version
namespace is retained for compatibility.

The legacy V1 path remains available for local migration. It is blocked under production profiles.
Do not mistake its public report and local-process behavior for the supported hosted V2 path.
Baseline comparisons, private-repository credentials, human SSO, remote runner federation and
physical-device testing are not implemented. Missing browser metrics remain indeterminate.

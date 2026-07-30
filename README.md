# Performance Testing Platform

A **self-service, config-driven performance testing platform** that eliminates the bottleneck of a central performance engineering team. Any team can run load, endurance, and spike tests by filling in a single YAML config file — no JMeter expertise required.

---

## 🚀 Run it locally, step by step

This walks from a fresh clone to a real load test against a real service, in order. Every
command is meant to be pasted as-is.

There are two paths through it:

| Path | What it proves | What it needs |
|---|---|---|
| **A — the control plane** (steps 1–7) | Authoring, validation, queueing, the run lifecycle, reports, evidence | JDK 21, Node |
| **B — real load** (steps 8–10) | All of the above, plus traffic actually reaching a service and being measured | also JMeter, and the bundled demo service |

Path A generates **no traffic at all**. Read step 7 before believing any number it produces.

---

### Step 1 — Prerequisites

```bash
java -version      # 21 (the project targets 17; building on 21 is correct)
node --version     # 20 or newer
mvn -version
```

`JAVA_HOME` is **not** read from your shell profile by this project's tooling. Every `mvn`
and `java` command below sets it explicitly. If you skip it, Maven fails immediately.

### Step 2 — Build

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn clean install
```

Expect `BUILD SUCCESS` and **580 tests, 0 failures, 0 skipped**. The PostgreSQL integration
tests need Docker running; if a whole module fails with an SSL/connection error, that is a
known flaky Testcontainers start — retry once.

### Step 3 — Start the control plane

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  java -jar platform-core/target/platform-core-1.0.0.jar --platform.config-dir=config/teams
```

It ships with a `local-dev` team, so this works with no configuration. Storage is in-memory
H2 by default — **runs and reports do not survive a restart**, which is fine for a trial and
is why production uses PostgreSQL.

Two loud warnings at startup are deliberate: the execution provider generates no load, and
caller identity comes from V1 API keys. Both refuse to start under a production profile.

### Step 4 — Start the UI

In a second terminal, from the repository root:

```bash
cd platform-ui && npm install && npm run dev
```

Open <http://localhost:5173>, stay on the **Performance tests** tab, and paste
`local-dev-key-not-a-secret` into *Team API key*. It is a fixed local value, not a credential.

A line at the top of the console names the execution provider in use. It is there permanently
and on purpose — see step 7.

### Step 5 — Create a test

Choose **New test**. Two starting points are offered:

- **Demo service** — runs against the bundled demo service. Use this one.
- **Blank starter** — the shape to copy when describing your own system.

Press **Check it** to validate without creating anything. Findings come back with a path and a
message; validation never creates or runs anything. Then **Save test**.

The document you just saved is the whole contract. The same file works through the REST API
and from Git — see [`docs/api/v2-examples.md`](docs/api/v2-examples.md).

### Step 6 — Run it and read the report

Press **Run this** next to the version. The run is queued and admitted within about ten
seconds — your team runs one test at a time by default.

Watch it move through *Queued → Starting → Running → Analyzing* to a terminal state. Below it:

- **Verdict** — one line per objective, each `PASS`, `BREACH`, or *not determined*.
- **What was measured** — requests, failures, median/90th/95th/99th, throughput, per journey.
- **The load this test applies** — the stage profile and journey mix.
- **Evidence** — the generated plan, the raw results and the runner manifest. Each download is
  authorized when you ask for it and checked against the fingerprint recorded when the run
  produced it. No shareable link is ever created.

### Step 7 — ⚠️ Understand what you just saw

The default execution provider is `fake`. It **generates no traffic whatsoever** and invents
its measurements, so it can exercise the control plane without a load generator.

The platform does not let that pass quietly. A run executed by it is:

- permanently marked **synthetic**, on the run itself and on its report;
- banner-flagged in the UI, in a way that survives a screenshot;
- reported with **every objective indeterminate** — "no load was generated, so nothing could
  be measured";
- **failed** with `NO_LOAD_GENERATED`, never *Passed*.

That last point is deliberate. A run that measured nothing must not produce a green verdict,
so a `fake` run ends in **Failed**. This is correct behaviour, not a problem to work around.

**Never present a number from a synthetic run as evidence of anything.**

---

### Step 8 — Start the demo service (for real load)

The repository bundles a small service with deliberately uneven behaviour, so a report is
worth reading: `GET /api/users` answers in 5–25 ms, `POST /api/payments` takes 150–600 ms and
fails about 5% of the time by design.

In a third terminal, from the repository root:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f demo-service/pom.xml clean package
java -jar demo-service/target/demo-service-1.0.0.jar     # listens on :8081
```

Check it:

```bash
curl -s localhost:8081/api/users
curl -s -X POST localhost:8081/api/payments
```

It is already registered as the `demo-service` target of the `local-dev` team, which is how
the platform knows where this test's traffic may go. A specification never names its own host.

### Step 9 — Restart the platform with a real load generator

```bash
brew install jmeter      # or install Apache JMeter and set JMETER_HOME
```

Stop the control plane from step 3 and start it again with the JMeter provider:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  java -jar platform-core/target/platform-core-1.0.0.jar \
  --platform.config-dir=config/teams \
  --platform.orchestration.execution-provider=local-jmeter
```

The console header now reports `local-jmeter` and says load is generated. In-memory storage
means the definition from step 5 is gone — recreate it, which takes one click.

### Step 10 — Run it for real

Repeat steps 5 and 6 with the **Demo service** template. This time traffic actually reaches
`localhost:8081`, and the report describes something real:

- the response-time and error-rate objectives scoped to the whole workload are **expected to
  breach** — the payment path is slow and fails on purpose;
- the objective scoped to `demo-load/browse-users` alone is **expected to pass**.

One report showing a breach and a pass together is the point. A demo that always passes never
shows anyone what a breach looks like.

`local-jmeter` runs JMeter as a child process of the control plane. That is a development
convenience only — CLAUDE.md §4 forbids the control plane from generating material load, and
production execution runs in isolated ephemeral Kubernetes Jobs. Both local providers are
refused under a production profile.

---

### Doing the same thing over REST

Everything above is available on the API, using the same document:

```bash
KEY=local-dev-key-not-a-secret

# Validate without creating anything
curl -s -X POST localhost:8080/api/v2/test-definitions/validate \
  -H "X-API-Key: $KEY" -H 'Content-Type: application/yaml' \
  --data-binary @specification/examples/valid/demo-service.yaml

# Create it, then trigger a run (the idempotency key makes a retry safe)
curl -s -X POST localhost:8080/api/v2/test-definitions \
  -H "X-API-Key: $KEY" -H 'Content-Type: application/yaml' \
  --data-binary @specification/examples/valid/demo-service.yaml

curl -s -X POST localhost:8080/api/v2/test-runs \
  -H "X-API-Key: $KEY" -H 'Idempotency-Key: nightly-1' \
  -H 'Content-Type: application/json' \
  -d '{"definitionVersionId":"<versionId from the previous response>"}'

# Then poll, and read the report
curl -s -H "X-API-Key: $KEY" localhost:8080/api/v2/test-runs/<runId>
curl -s -H "X-API-Key: $KEY" localhost:8080/api/v2/test-runs/<runId>/report
```

### Troubleshooting

| Symptom | Cause |
|---|---|
| `Port 8080 was already in use` | An earlier control plane is still running. `lsof -ti:8080 \| xargs kill` |
| `specification.team.unknown` on save | The document's `metadata.team` is not a team in `config/teams`. The shipped templates use `local-dev` |
| `403 Forbidden` from the console | The API key is wrong, or its team is not registered |
| Run stuck in `Queued` | Your team runs one test at a time; an earlier run still holds the slot |
| Run fails `PROVISIONING_FAILED`, "no permitted target" | `metadata.application` does not match a target name of that team |
| Run fails `NO_LOAD_GENERATED` | Expected on the default provider. See step 7 |
| Every objective says *not determined* | Same — no load was generated, so nothing was measured |
| Definition vanished after restart | In-memory H2 by default. Point `DB_URL` at PostgreSQL to keep data |

### Legacy (v1)

The original V1 screens are still available on the **Legacy (v1)** tab and still work
unchanged. They are removed only once V2 reaches functional parity and removal is approved.

---

## ☁️ Deploy to Kubernetes (EKS + Karpenter)

```bash
helm install perf-platform ./helm/performance-platform \
  --namespace perf-testing --create-namespace \
  --set image.tag=1.0.0 \
  --set artifacts.bucket=my-perf-artifacts \
  --set artifacts.region=us-east-1
```

Karpenter provisions and de-provisions nodes for each runner Job, so you pay for EC2 only during
active tests. The UI is at the configured ingress host.

### How a production deployment differs

The control plane **never generates load itself** (CLAUDE.md §4). Two things follow, and the
platform refuses to start without either:

| | Local | Production |
|---|---|---|
| Execution | `fake` (no traffic) or `local-jmeter` (child process) | **`kubernetes`** — one isolated, ephemeral Job per shard |
| Artifacts | pod-local filesystem | **S3-compatible object storage** |

Runner Jobs get their own namespace (`runnerCell.namespace`) with `pod-security: restricted`, their
own service account, and Jobs that are `backoffLimit: 0`, non-root, read-only-root, all capabilities
dropped, with explicit CPU/memory limits and a hard `activeDeadlineSeconds`.

**A failed run is never automatically retried.** A retried performance test is a second load test
against a real system and two overlapping sets of results.

Runner images are an **allowlist of pinned tags**, and startup refuses `latest`. A moving tag means
two runs of the same immutable definition version can execute on different runner builds, so the
plan digest still matches while the thing executing it has changed.

Object-storage credentials are never configured in Helm values — the AWS SDK's default provider
chain reads them from an IRSA-projected service account token or an instance profile.

Before trusting any capacity number, read
[`docs/operations/runner-calibration.md`](docs/operations/runner-calibration.md): a throttled load
generator reports its own latency rather than the system's, and that failure is silent.

> **The runner image is not built by this repository yet.** `runnerCell.images` expects a
> `jmeter-runner` image that reads its `PERF_*` environment, executes the plan and uploads its
> artifacts. Until it exists, the Kubernetes provider will create Jobs that cannot start.

---

## 📝 Team Onboarding (3 Steps)

**Step 1** — Get your API key from the platform admin.

**Step 2** — Create your config file at `config/teams/<your-team>.yml`:

```yaml
team: my-team
apiKey: "your-api-key"

targets:
  - name: my-service
    baseUrl: "http://my-service:8080"
    jvmEnabled: false         # Set true only for Java/Spring Boot services

tests:
  load:
    enabled: true
    rampUpUsers: 10
    steadyStateUsers: 100
    rampUpDurationSeconds: 60
    steadyStateDurationSeconds: 300
    scenarios:
      - name: "core-flow"
        endpoints:
          - method: GET
            path: "/api/health"

sla:
  maxP95LatencyMs: 500
  maxErrorRatePercent: 1.0
```

**Step 3** — Run the test via:
- **Web UI**: Open `http://localhost:8080`, select your team, click ▶ Run Test
- **CI/CD**: Copy `ci-templates/github-actions.yml` into your repo → tests auto-trigger on config change
- **REST API**: `POST /api/v1/tests/run`

---

## ⚙️ Configuration Reference

| Field | Default | Description |
|---|---|---|
| `team` | required | Unique team identifier |
| `apiKey` | required | API key for authentication |
| `targets[].baseUrl` | required | Base URL of the service under test |
| `targets[].jvmEnabled` | `false` | Enable JVM heap/thread dump collection |
| `targets[].jvmJmxPort` | `9010` | JMX port on the target service |
| `tests.load.enabled` | `false` | Enable load test |
| `tests.load.steadyStateUsers` | `50` | Concurrent users at peak |
| `tests.endurance.durationMinutes` | `60` | Soak test duration |
| `analysis.heapDump.enabled` | `false` | Heap dump collection (JVM only) |
| `analysis.threadDump.enabled` | `false` | Thread dump collection (JVM only) |
| `sla.maxP95LatencyMs` | `1000` | Max allowed P95 latency in ms |
| `sla.maxErrorRatePercent` | `1.0` | Max allowed error rate % |

Full configuration reference: [docs/configuration-reference.md](docs/configuration-reference.md)

---

## 🔌 REST API

All endpoints require the `X-API-Key` header.

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/tests/run` | Trigger a test |
| `GET` | `/api/v1/tests/{runId}` | Poll test status |
| `GET` | `/api/v1/tests/{runId}/report` | Download HTML report |
| `GET` | `/api/v1/tests/history?team=X` | Test run history |
| `POST` | `/api/v1/dump/heap` | On-demand heap dump (JVM only) |
| `POST` | `/api/v1/dump/thread` | On-demand thread dump (JVM only) |
| `POST` | `/api/v1/config/reload` | Hot-reload team configs |

Swagger UI: `http://localhost:8080/swagger-ui`

---

## 🏗️ Architecture

```
Team YAML Config  →  Platform Core (Spring Boot)  →  JMeter Engine
                           │
                  ├── SLA Evaluator (CSV → P95/errors)
                  ├── JVM Dump Collector (JMX, opt-in)
                  ├── Eclipse MAT Analysis (heap)
                  ├── Report Generator (unified HTML)
                  └── Notification Service (Slack/email)

Observability: Prometheus + pre-built Grafana dashboards
Deployment:    Kubernetes (EKS + Karpenter) + Helm chart
```

---

## 🏗️ Building

```bash
cd performance-testing-platform
mvn clean package -pl platform-core
```

---

## 📁 Project Layout

```
performance-testing-platform/
├── pom.xml                         # Root Maven POM (reactor)
├── platform-domain/                # Engine-neutral domain model (no Spring/JPA/K8s)
├── platform-engine-spi/            # Engine contract
├── platform-results/               # Result normalization and objective evaluation
├── platform-persistence/           # PostgreSQL adapters and Flyway migrations
├── platform-orchestration/         # Admission control, state machine, reconciliation
├── platform-api/                   # /api/v2/** REST surface
├── engines/
│   └── engine-jmeter/              # JMeter provider for the engine SPI
├── runners/
│   └── runner-kubernetes/          # Production execution: one ephemeral Job per shard
├── demo-service/                   # A real target to load-test locally (port 8081)
├── platform-core/                  # Spring Boot composition root — owns bootstrapping
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/
│       ├── java/com/perfplatform/  # V1 control plane (/api/v1/**)
│       └── resources/
│           ├── application.yml
│           ├── templates/jmx/      # Freemarker JMX templates
│           └── static/index.html   # Legacy web UI
├── platform-ui/                    # React UI (not a Maven module — build separately)
├── config/teams/                   # Team YAML configs
│   └── example-team.yml
├── docker/                         # Local Docker Compose
├── helm/performance-platform/      # Kubernetes Helm chart (EKS)
├── ci-templates/                   # GitHub Actions + Jenkins
└── docs/
    ├── architecture/               # Target architecture
    ├── adr/                        # Architecture decision records
    ├── migration/                  # Baseline report and V1→V2 strategy
    ├── operations/
    └── api/
```

Dependencies point inward: `platform-domain` depends on nothing, `platform-core` depends on
everything. The rules are in `CLAUDE.md` §9 and enforced by `ModuleDependencyRulesTest`.

> **V2 migration in progress.** This repository is being evolved to the architecture in
> [`docs/architecture/`](docs/architecture/performance-testing-platform-target-architecture.md).
> `/api/v1/**` stays operational throughout. Before relying on V1 behavior, read
> [`docs/migration/baseline-report.md`](docs/migration/baseline-report.md) — it records
> verified defects in the current proof of concept, including one that makes any test at
> 1,000 virtual users or any 60-minute endurance run generate an invalid JMeter plan.

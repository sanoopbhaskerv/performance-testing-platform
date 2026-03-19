# Performance Testing Platform

A **self-service, config-driven performance testing platform** that eliminates the bottleneck of a central performance engineering team. Any team can run load, endurance, and spike tests by filling in a single YAML config file — no JMeter expertise required.

---

## 🚀 Quickstart (Local)

```bash
# 1. Clone the repo
git clone <repo-url>
cd performance-testing-platform

# 2. Copy the example team config and fill in your values
cp config/teams/example-team.yml config/teams/your-team.yml
# Edit config/teams/your-team.yml with your service URL and endpoints

# 3. Start the platform
cd docker && docker-compose up -d

# 4. Open the Web UI
open http://localhost:8080
```

---

## ☁️ Deploy to Kubernetes (EKS + Karpenter)

```bash
# Add your team config to helm/performance-platform/values.yaml under teamConfigs:
# Then install:
helm install perf-platform ./helm/performance-platform \
  --namespace perf-testing --create-namespace \
  --set image.tag=1.0.0

# Access the UI via the configured ingress host
open https://perf-platform.internal.your-company.com
```

Karpenter will automatically provision and de-provision nodes for each JMeter test run — you only pay for EC2 during active tests.

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
├── pom.xml                         # Root Maven POM
├── platform-core/                  # Spring Boot control plane
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/
│       ├── java/com/perfplatform/  # All Java source
│       └── resources/
│           ├── application.yml
│           ├── templates/jmx/      # Freemarker JMX templates
│           └── static/index.html   # Web UI
├── config/teams/                   # Team YAML configs
│   └── example-team.yml
├── docker/                         # Local Docker Compose
├── helm/performance-platform/      # Kubernetes Helm chart (EKS)
└── ci-templates/                   # GitHub Actions + Jenkins
```

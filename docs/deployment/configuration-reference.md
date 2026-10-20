# Configuration reference

Start with [company-values.yaml](../../helm/performance-platform/examples/company-values.yaml).
The complete shipped chart settings are in [values.yaml](../../helm/performance-platform/values.yaml).
This guide describes implemented settings, not future provider integrations.

## Choose the correct configuration layer

| What you are changing | Where to configure it | Rebuild? |
|---|---|---|
| Name, logo, colors, light/dark | `branding` Helm values or public `platform-config.json` | No; roll/reload |
| Database endpoint and credential reference | `postgres.externalUrl`, `database.existingSecret` | No; Helm upgrade |
| Storage bucket, region, S3 endpoint | `artifacts.*` | No; Helm upgrade |
| Cloud workload identity | `serviceAccount.annotations`, `runnerCell.serviceAccountAnnotations` | No; new Pods |
| Private registry credentials | `imagePullSecrets`, `runnerCell.imagePullSecrets` | No; new Pods |
| Ingress, domain, certificate | `ingress.*` and existing TLS Secret | No |
| Queue/run capacity | `capacity.maxConcurrentRuns`, additional capacity properties below | No |
| Runner resources, deadlines, namespace | `runnerCell.*` | No; applies to new Jobs |
| Approved network reachability | `runnerCell.egress.allowedCidrs`, company CNI/firewall/DNS | No |
| A specific test's users/rate/duration/checks | UI / YAML / REST canonical specification | New immutable version |
| Additional Spring settings | `applicationConfig` | No; rolls control plane |
| New engine, storage API, corporate SSO provider | Java adapter + composition + tests | Yes |

Do not duplicate a setting across multiple layers. Explicit Spring environment properties take
precedence over YAML; the chart's short variables such as `MAX_CONCURRENT_RUNS` supply defaults to
the packaged configuration. A direct property in `applicationConfig` can override that default.
Check the rendered Deployment and configuration file when diagnosing conflicting settings.

## Chart coordinates

| Key | Meaning |
|---|---|
| `deploymentProfile` | Keep `production` for hosted operation; local/development adapters are rejected there |
| `capacity.maxProtocolUsersPerRun` | Operator ceiling across all protocol workloads in one run; default 1,000. Arrival-rate workloads reserve their `maxConcurrency`, not their RPS |
| `capacity.maxDuration` | Maximum workload duration/declaration, default `1h`; compiled engine estimates are checked before provisioning |
| `fullnameOverride` | Stable Kubernetes resource prefix, useful for identity trust policies |
| `image.repository`, `image.tag` | Your combined control-plane/UI image in your registry |
| `auth.serviceTokenSecret` | Existing Secret with `identities.json`; hashed scoped machine identities |
| `database.existingSecret` | Existing Secret with `username` and `password` keys |
| `postgres.enabled` | `false` for your existing PostgreSQL; bundled instance is a development convenience |
| `postgres.externalUrl` | JDBC URL including your required TLS options |
| `artifacts.store` | `s3` for hosted use; filesystem is local-only |
| `artifacts.bucket`, `region` | Your private bucket and signing region |
| `artifacts.endpoint`, `pathStyleAccess` | Non-AWS S3-compatible endpoint/addressing; empty endpoint uses AWS |
| `artifacts.runnerEndpoint` | Optional storage address reachable by Jobs when different from the API's route |
| `artifacts.controlPlaneCredentialsSecret` | Optional control-plane namespace Secret exposing AWS credential variables |
| `artifacts.runnerCredentialsSecret` | Optional runner namespace Secret exposing AWS credential variables |
| `serviceAccount.annotations` | Control-plane service account cloud annotations |
| `runnerCell.createNamespace` | `false` when operators pre-create the dedicated namespace |
| `runnerCell.serviceAccount` | Dedicated runner identity; must differ from the control-plane identity |
| `runnerCell.serviceAccountAnnotations` | Runner cloud annotations; identity trust must use this namespace/name |
| `imagePullSecrets`, `runnerCell.imagePullSecrets` | Lists of `{name: ...}`; each Secret must exist in its own namespace |
| `runnerCell.images` | Allowlist mapping `jmeter`/`playwright` to your pinned runner images |
| `runnerCell.resources` | Per-runner CPU/memory requests and limits; calibrate under representative traffic |
| `runnerCell.workspaceSizeLimit` | Bounded temporary workspace, not durable result storage |
| `runnerCell.finishedJobTtl`, `deadlineGrace` | Job retention for reconciliation and startup/upload allowance |
| `runnerCell.egress.allowedCidrs` | Approved target/storage/proxy networks; universal IPv4/IPv6 routes rejected in production |
| `ingress.className`, `annotations`, `host`, `tls` | Your controller's class/annotations/domain and existing TLS Secret |
| `teamConfigs` | Legacy V1 configuration; use `{}` for standalone production V2 |
| `sessionAcquisition.provider` | `disabled` for unauthenticated target journeys; configure an installed broker adapter when required |
| `secretResolver.mountedSecrets` | Existing Secrets mounted by name for `k8s://name/key` references |

Ingress annotations are controller-specific. For ingress-nginx, for example, configure request-size
and streaming timeouts under `ingress.annotations`; an ALB or another controller needs its own
annotations. Private registry access uses the service account's image-pull Secret references, which
Kubernetes adds to new Pods. See the [Kubernetes service-account documentation](https://kubernetes.io/docs/reference/access-authn-authz/service-accounts-admin/).

## Additional application properties

`applicationConfig` becomes `/config/platform/application.yaml`, loaded using
`SPRING_CONFIG_ADDITIONAL_LOCATION`. It is a non-secret ConfigMap. Example:

```yaml
applicationConfig:
  platform:
    orchestration:
      reconciliation-interval: 10s
      lease-duration: 2m
      heartbeat-tolerance: 5m
      capacity:
        default-team-max-runs: 1
        max-browser-sessions-per-run: 5
        team-overrides:
          approved-team: 2
  logging:
    level:
      com.perfplatform: INFO
```

The runtime properties are defined in `platform-core/.../v2/OrchestrationProperties.java`; defaults
are in `platform-core/src/main/resources/application.yml` and `application-production.yml`. Five
global material runs and one per team are initial defaults. Browser admission is a separate budget.
The wizard's browser default remains one browser/iteration. A capacity value controls admission;
it does not create nodes, prove generator capacity or raise a test's validated engine limit.

Per-run load limits are independent of the five-run queue limit. Configure the two Helm values
above, or `platform.orchestration.capacity.max-protocol-users-per-run` and `max-duration` in external
Spring configuration. The packaged environment defaults are `MAX_PROTOCOL_USERS_PER_RUN=1000` and
`MAX_TEST_DURATION=1h`. Invalid/non-positive operator values fail startup. A specification cannot
raise these limits using its own `governance.maxDuration`. Checks run during authoring, before a
new trigger is persisted, and again before provisioning to cover policy changes while queued.
Engine-derived duration estimates are checked as well; startup/upload grace is additional to the
workload ceiling. Existing idempotent triggers still resolve to their original run. A policy
refusal returns actionable `policy.protocolUsers.exceeded` / `policy.duration.exceeded` findings.

For a directly run Java application use an external Spring configuration file or Spring's normal
environment binding, for example `SPRING_DATASOURCE_URL`, `SERVICE_TOKEN_FILE`, and
`PLATFORM_ORCHESTRATION_ARTIFACTS_BUCKET`. Follow the same production profile, Secrets and provider
rules as the chart. Copy the composition settings rather than running the legacy Dockerfile.

## Credentials, certificates and changes

Only names/references belong in Helm values, public branding, source examples and specifications.
Use mounted identity hashes for API access, existing DB/storage Secrets or supported workload
identity for infrastructure, and `secretRef` for application-under-test credentials. These are
different identities. Never give runner Jobs the platform API token or database password.

Use your approved base-image/certificate distribution to trust private CAs in Java, Node/Chromium,
the runner CLI and your ingress. Configuring only the database TLS option does not install its CA.
Do not turn off certificate checking to make a private endpoint work. For corporate build mirrors,
use Maven settings, npm's registry configuration and the JMeter Docker build argument
`JMETER_DISTRIBUTION_BASE_URL`; that mirror must host the archive and matching `.sha512` file.

Helm changes to generated application/branding configuration roll the control plane. Existing
external Secret contents are not all included in rollout checksums: rotate using your normal
Secret-controller/rollout process. Identity files are re-read for authentication; removing an
identity revokes it, whereas storage/DB environment credentials require new Pods. Apply runner
image, network and capacity changes before starting new runs; do not mutate a material Job in flight.

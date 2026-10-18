# Deploy a standalone installation in your infrastructure

For a first company installation, use the complete [clone-to-first-run walkthrough](company-installation.md).
This page retains the compact standalone reference and disposable local verification procedure.
[Runtime branding](branding.md), [configuration keys](configuration-reference.md),
[cloud profiles](cloud-profiles.md), and [architecture diagrams](../architecture/system-overview.md)
cover customization and placement.

Supported topology: one modular control-plane application with the current React console on the
same origin, PostgreSQL, private S3-compatible storage, and isolated ephemeral runner Jobs.
Use any Kubernetes cloud or on-premises distribution that supports the required security contexts
and enforced NetworkPolicy. No cloud account, bucket, credentials or external targets are selected
by this repository. A team-local remote runner agent is not implemented yet.

## 1. Build and store your images

```bash
mvn clean package
# Run these from the repository root. The browser image shares the exact local runner sources.
docker build -f docker/Dockerfile.control-plane -t YOUR_REGISTRY/performance-control-plane:1.0.0 .
docker build -f runner-images/playwright-runner/Dockerfile -t YOUR_REGISTRY/playwright-runner:1.62.1-1 .
docker build -t YOUR_REGISTRY/jmeter-runner:5.6.3-3 runner-images/jmeter-runner
```

Publish those images to a registry your cluster can read, using your normal release process.
The control-plane image contains no JMeter runtime. The browser image pins Playwright and its
matching browser binaries; a raw upstream Playwright image is not a platform runner.
The old `platform-core/Dockerfile` is retained for legacy local packaging; the Dockerfile above is
the standalone distribution serving both UI and API.

## 2. Provision durable dependencies and operator Secrets

Create your namespace. Provision PostgreSQL and a private encrypted S3-compatible bucket with
retention/lifecycle rules and sufficient space for raw results and traces. Back up PostgreSQL.
Configure PostgreSQL TLS and database privileges according to your environment. For a managed
PostgreSQL service set `postgres.enabled=false` and its JDBC URL in `postgres.externalUrl`.

Create a database Secret with keys `username` and `password`, outside version control. The chart
uses `database.existingSecret` and never needs those values in a values file. Its optional bundled
PostgreSQL is a development convenience, not an HA managed-database replacement.

Create a scoped standalone identity:

```bash
python3 scripts/create-service-token.py --subject service:team-author --team my-team \
  --output /private/performance/identities.json --token-output /private/performance/team-token
kubectl -n YOUR_NAMESPACE create secret generic platform-auth \
  --from-file=identities.json=/private/performance/identities.json
```

The token file is for the authorized client. Only its hash, team memberships, scopes and expiry
are in `identities.json`. The API reads the mounted file on every request; replacing it revokes
removed hashes, including before their expiry. Use separate identities for separate automation,
restrict scopes with repeated `--scope`, and rotate/revoke regularly. The default expiry is 90 days.
Authorized identity memberships onboard their named teams durably on authentication. A test
specification cannot create a team through a typo.

This is a machine-token adapter, not a human SSO login. For corporate OIDC implement/configure a
`CallerContextProvider` and authentication filter using your approved identity system. No
provider-specific claims, corporate identity service or secret manager are chosen here.

The control plane and runner each need appropriately scoped object-store identities. Prefer the
cloud's workload identity mechanism. For an S3-compatible appliance, mount its credentials through
an existing Secret. Runner credentials are referenced by `artifacts.runnerCredentialsSecret` in
the runner namespace. Control-plane credentials can be injected by workload
identity or `artifacts.controlPlaneCredentialsSecret` (AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY keys); never put values into Helm values.
The key layout includes `teams/{teamId}/sources`, `datasets` and `runs`; bucket policies must cover
the owned namespaces, not make artifacts public. The runner has no control-plane API token or DB access.

## 3. Configure the chart

Example shape; replace every environment-specific coordinate:

```yaml
deploymentProfile: production
image:
  repository: YOUR_REGISTRY/performance-control-plane
  tag: "1.0.0"
auth:
  serviceTokenSecret: platform-auth
database:
  existingSecret: platform-db
postgres:
  enabled: false
  externalUrl: jdbc:postgresql://YOUR_DATABASE:5432/perfplatform?sslmode=verify-full
capacity:
  maxConcurrentRuns: 5
artifacts:
  store: s3
  bucket: YOUR_PRIVATE_BUCKET
  region: YOUR_REGION
  # For non-AWS S3-compatible storage only:
  # endpoint: https://YOUR_STORAGE_ENDPOINT
  # runnerEndpoint: https://YOUR_RUNNER_STORAGE_ENDPOINT
  # pathStyleAccess: true
  # runnerCredentialsSecret: runner-storage-credentials
runnerCell:
  namespace: perf-runners
  images:
    jmeter: YOUR_REGISTRY/jmeter-runner:5.6.3-3
    playwright: YOUR_REGISTRY/playwright-runner:1.62.1-1
  egress:
    allowedCidrs:
      - YOUR_APPROVED_TARGET_NETWORK_CIDR
      - YOUR_STORAGE_OR_EGRESS_PROXY_CIDR
ingress:
  host: performance.YOUR_DOMAIN
  tls:
    - hosts: [performance.YOUR_DOMAIN]
      secretName: performance-tls
```

Use your cluster's certificate trust distribution for private CAs. Do not disable TLS verification.
DNS egress is permitted to kube-system on port 53; adapt it for a custom/NodeLocal DNS deployment.
Allow the exact target, storage and, for repository mode, git/npm proxy networks. Production values
reject universal `0.0.0.0/0` and `::/0`. A NetworkPolicy resource is effective only on a CNI that
enforces it: verify the policy by trying a prohibited destination from a disposable runner pod.
Network restrictions are essential because reviewed Playwright source is still executable code.

For IRSA or other projected web-identity credentials, ensure the token exchange/STS endpoint is
included in approved egress. `AWS_EC2_METADATA_DISABLED=true` blocks instance-metadata credential
fallback in the runner; use projected workload identity or an explicit scoped Secret.

`sessionAcquisition.provider` defaults to `disabled` under the production profile. Tests that do
not acquire application sessions need no broker. For authenticated workload sessions, configure an
approved broker and mounted secret references as described in
[run-session-acquisition](../operations/run-session-acquisition.md). Disabled acquisition fails
closed if a test requests it. API access tokens and application-under-test credentials are separate.

```bash
helm lint helm/performance-platform -f /private/performance/values.yaml
helm upgrade --install performance helm/performance-platform \
  --namespace YOUR_NAMESPACE --create-namespace -f /private/performance/values.yaml
```

The chart requires existing authentication/database Secrets, a private bucket and restricted
runner networks for a production install. Old placeholder JMeter Jobs are disabled. The runner
cell uses its own namespace/service account, explicit resources, a writable bounded workspace,
non-root execution, read-only root filesystem, deadline, finished-Job TTL and `backoffLimit: 0`.
A failed material test is not automatically replayed.

## 4. Onboard and verify

Open the ingress URL and paste the scoped team token into **Team access token**. On **About**,
enter the token's team and register a permitted non-production target for the application and
environment. Continue through [the browser walkthrough](../user-guide/browser-testing.md).
Use the same token with the CLI via `PERF_PLATFORM_TOKEN_FILE`.

Before inviting consumers, verify a real one-browser run and a short API run. Confirm:

- `synthetic=false`, nonzero real samples, meaningful objective verdicts and authorized downloads;
- the actual Job's UID, pinned image, no retries, non-root user and read-only root filesystem;
- failure evidence for a deliberately failing assertion; a script with no tests fails execution;
- a queued/running cancellation stops the Job and emits a durable cancellation event;
- restarting the control plane preserves definitions/runs and does not recreate material load;
- another team's token cannot read the report or source, and expired tokens fail authentication;
- the UI's static assets load and readiness/liveness endpoints work without API credentials.

The previous base Kubernetes JMeter procedure is in
[local-kubernetes-run](../operations/local-kubernetes-run.md). Current verification results and exact
local environment limitations are recorded in `HANDOFF.md`; a local proof does not establish that
your cloud IAM, storage encryption, egress policy, DNS or capacity is configured correctly.

## Operate, modify and upgrade

Keep PostgreSQL and object storage durable independently of API pods. Use multiple API replicas
only after verifying every replica's storage/identity settings and lease reconciliation. Configure
retention for artifacts, traces and progress data; failure traces can contain sensitive page data
and are restricted evidence, not public links. Observe runner CPU/memory/network and calibrate
before increasing browser concurrency or API load. Five admitted runs do not imply five large
browser cohorts fit on your nodes.

Build a new immutable image tag for changes, test locally/on kind, back up PostgreSQL and then
upgrade the chart. Flyway owns schema changes; do not enable `ddl-auto:update` in hosted use.
Use `production` as the deployment profile; the legacy V1 APIs/reports and development identity
are blocked there. For local chart experiments `deploymentProfile=local` deliberately retains
local migration behavior; it is not a production security configuration.

## Reproduce the disposable local browser proof

This procedure uses only the bundled target in kind, with small cohorts. Build the two images
using the commands above with tags `perfplatform/platform-core:browser-verify` and
`perfplatform/playwright-runner:1.62.1-1`. Then:

```bash
kind create cluster --name perf-browser-verify
kind load docker-image perfplatform/platform-core:browser-verify --name perf-browser-verify
kind load docker-image perfplatform/playwright-runner:1.62.1-1 --name perf-browser-verify
# Optional local S3 fixture; the pinned upstream prebuilt image was withdrawn.
docker build -f platform-persistence/src/test/resources/minio/Dockerfile \
  -t perfplatform/minio-test:2024-11-07-1 .
kind load docker-image perfplatform/minio-test:2024-11-07-1 --name perf-browser-verify
# Load or permit pulling postgres:16.2-alpine too.
python3 scripts/verify-browser-kubernetes.py
kubectl --context kind-perf-browser-verify -n perf-browser-proof \
  port-forward service/proof-performance-platform 18080:8080
# In a second terminal:
python3 scripts/check-browser-self-service.py
```

The setup creates short-lived private credentials under `.local/browser-proof/`, never prints them,
and refuses to replace an existing proof namespace's credentials. The check drives the actual
CLI/API through success, objective breach with failure evidence, repeated idempotency keys,
digest-verified private downloads, and running cancellation. It leaves definitions and reports for
inspection. The UI at `http://127.0.0.1:18080` accepts the private `token` file's value. This is an
optional local proof, not the default application deployment or a cloud certification. The bundled
kind networking does not prove NetworkPolicy enforcement. Clean up with
`kind delete cluster --name perf-browser-verify` when inspection is finished.

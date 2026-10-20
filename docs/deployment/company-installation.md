# Company installation: clone to your first verified run

This is the main operator walkthrough. It deploys the code into **your existing infrastructure**:
your Kubernetes cluster, registry, PostgreSQL, object store, DNS, certificates and application
network. It does not create cloud accounts or select a paid service. Work through it in a
non-production environment first. Keep the completed configuration in your company's deployment
repository; keep credentials in your approved secret system.

## 1. Record your installation choices

| Decision | Example used here | What you must supply |
|---|---|---|
| Kubernetes context | Your non-production context | Cluster access, capacity, enforcing CNI |
| Control-plane namespace | `perf-platform` | Dedicated namespace |
| Runner namespace | `perf-runners` | Dedicated namespace, isolated runner identity |
| Release/resource name | `performance` | Stable name for RBAC and cloud trust policies |
| Registry | `registry.example.com/performance` | Read access for both namespaces |
| PostgreSQL | Existing managed/operator-owned database | JDBC URL, trusted CA, database/schema and credentials |
| Object storage | Existing private S3-compatible bucket | Bucket, region, endpoint if applicable, scoped identities |
| Public/internal URL | `performance.example.com` | DNS, ingress class, TLS Secret |
| Branding | Acme Performance Lab | Name, logo file, colors, theme |
| Initial capacity | Five material runs, one per team | Calibrated CPU/memory/network; separate browser limit |
| Application target | Your approved test application | Reachable URL, authorization to test, representative data |

Read the [architecture and placement diagrams](../architecture/system-overview.md). Use
[cloud profiles](cloud-profiles.md) to distinguish supported S3 configuration from integrations
that require code. A Kubernetes cluster by itself does not supply storage, database, identity,
DNS, routing or network enforcement.

## 2. Fork/clone and select a revision

```bash
git clone https://github.com/sanoopbhaskerv/performance-testing-platform.git
cd performance-testing-platform
git rev-parse HEAD
```

Use your fork when you intend to change code. Record the chosen commit in your release manifest.
Use JDK 21 to build the Java 17 target, Maven, Node.js 22.22.2, Python 3, Docker, Helm and kubectl.
Confirm `JAVA_HOME` points to your JDK. Docker must be running for authoritative PostgreSQL/S3
integration tests. No hosted AI key is required. Follow your company process for approved versions
and dependency mirrors; avoid upgrading the application baseline as part of first deployment.

## 3. Run the local product once

```bash
./scripts/start-platform.sh --build
```

Open `http://localhost:5173`, connect using the displayed local development token and try a small
API/browser test against the bundled target. Use [browser testing](../user-guide/browser-testing.md)
and [configuration from requirements](../user-guide/configuration.md). Ctrl-C stops the launcher.
The local profile is for learning/development. Its H2 data is not a durable hosted installation,
and its local process adapters are not the production execution path.

## 4. Verify and build your three images

```bash
mvn clean verify
npm --prefix platform-ui ci
npm --prefix platform-ui test
npm --prefix platform-ui run lint
npm --prefix platform-ui run build
python3 -m unittest discover -s runner-images/jmeter-runner -p 'test_*.py'

docker build -f docker/Dockerfile.control-plane \
  -t registry.example.com/performance/control-plane:1.0.0-company.1 .
docker build -t registry.example.com/performance/jmeter-runner:5.6.3-3 runner-images/jmeter-runner
docker build -f runner-images/playwright-runner/Dockerfile \
  -t registry.example.com/performance/playwright-runner:1.62.1-1 .
```

Replace the registry coordinate with yours. The control-plane image includes the React console and
API on one origin. Use the platform's runner images: raw upstream JMeter/Playwright images lack its
plan, identity, progress and artifact contracts. The older Dockerfiles under `platform-core/` and
`platform-ui/` are legacy packaging, not this standalone distribution.

If the Apache archive is slow, use a reviewed mirror hosting the same archive and `.sha512`:

```bash
docker build --build-arg JMETER_DISTRIBUTION_BASE_URL=https://dlcdn.apache.org/jmeter/binaries \
  -t registry.example.com/performance/jmeter-runner:5.6.3-3 runner-images/jmeter-runner
```

Corporate Maven/npm mirrors and Docker registry proxies remain your build environment's
configuration. The optional MinIO integration-test fixture builds from pinned official source;
the test build needs its documented upstream dependencies. Production uses your object store.

## 5. Publish to your private registry

Use your normal approved registry login/release process, then push the exact images you built:

```bash
docker push registry.example.com/performance/control-plane:1.0.0-company.1
docker push registry.example.com/performance/jmeter-runner:5.6.3-3
docker push registry.example.com/performance/playwright-runner:1.62.1-1
```

Use new immutable tags for changed builds. Record image digests. The runner allowlist rejects
moving tags such as `latest`; the image and browser/runtime versions must remain compatible.

## 6. Prepare namespaces and private deployment files

```bash
kubectl config current-context
kubectl create namespace perf-platform
kubectl create namespace perf-runners
kubectl label namespace perf-runners pod-security.kubernetes.io/enforce=restricted

umask 077
export PERF_DEPLOY_DIR="$HOME/performance-installation"
mkdir -p "$PERF_DEPLOY_DIR"
cp helm/performance-platform/examples/company-values.yaml "$PERF_DEPLOY_DIR/values.yaml"
```

These namespace-creation commands are for a new installation. Reuse only namespaces your operator
has designated for this product. The company example sets `runnerCell.createNamespace=false`, so
Helm does not try to adopt an operator-owned namespace. Set it true only when the chart should own
creating that dedicated namespace. Confirm every command targets your intended context.

## 7. Supply PostgreSQL credentials out of band

Provision the database before installation. The configured database role must support the
Flyway migrations and the application's tables in its owned schema. Enable backups/PITR according
to your policy. Ensure API Pods can resolve and reach it and trust its certificate chain.

Create a private file `$PERF_DEPLOY_DIR/database.env` with the two keys `username` and `password`,
populated from your secret-management process. Do not commit it. Create the Kubernetes Secret:

```bash
kubectl -n perf-platform create secret generic platform-db \
  --from-env-file="$PERF_DEPLOY_DIR/database.env"
```

Set `postgres.enabled=false`, your JDBC URL in `postgres.externalUrl`, and
`database.existingSecret=platform-db`. A typical verified-certificate URL ends in
`?sslmode=verify-full`; install the provider CA using your approved certificate distribution.
Do not enable Hibernate `ddl-auto:update`. Flyway owns schema changes.

## 8. Supply object-store access

Use a private encrypted bucket with lifecycle/retention configured independently of API Pods.
The control plane reads/writes scripts, datasets, plans and results. Runners read their inputs and
write results/progress. Neither identity needs bucket administration. Scope access to the
installation's prefixes and review trust boundaries; a cell currently uses a shared configured
runner storage identity rather than an automatically minted per-run identity.

For AWS S3 use the [IRSA overlay](cloud-profiles.md) and leave both credential Secret names empty.
For an S3-compatible provider that uses access keys, prepare a private `storage.env` with
`AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` (and `AWS_SESSION_TOKEN` when applicable):

```bash
kubectl -n perf-platform create secret generic storage-access \
  --from-env-file="$PERF_DEPLOY_DIR/storage.env"
kubectl -n perf-runners create secret generic storage-access \
  --from-env-file="$PERF_DEPLOY_DIR/storage.env"
```

Prefer separate, appropriately scoped credentials for the two namespaces in real installations.
The same example name does not require the same credential. Configure bucket, signing region,
endpoint and path-style addressing. `runnerEndpoint` is only needed if the runner reaches storage
through a different address. The bucket must be reachable from both control plane and runners.

## 9. Create API identities for your teams and CI

```bash
python3 scripts/create-service-token.py --subject service:my-team-author --team my-team --days 30 \
  --output "$PERF_DEPLOY_DIR/identities.json" \
  --token-output "$PERF_DEPLOY_DIR/my-team.token"
kubectl -n perf-platform create secret generic platform-auth \
  --from-file=identities.json="$PERF_DEPLOY_DIR/identities.json"
```

The helper writes private files, refuses overwrite, and prints file paths rather than the token.
The server stores only hashes plus memberships/scopes/expiry. Give the private token only to its
authorized consumer or automation. Default scopes support authoring, execution, reading and
cancellation for the named team. Use repeated `--scope` options for narrower CI/read-only identities.

For multiple identities, generate separate files/tokens and combine the JSON arrays into the one
mounted `identities.json` through your approved tooling. Keep all intended identities on rotation;
replacing the file with only a new entry revokes every omitted entry. Use different identities for
different teams/CI jobs. Authorized membership creates the team's durable registry entry on login.

This is the current machine-token adapter. Human OIDC login and corporate claims mapping require
an identity adapter; adding an issuer URL alone does not implement that flow.

## 10. Configure registry pulls, TLS, networking and branding

Create registry-access Secrets using your approved credential mechanism in **each namespace**.
Set `imagePullSecrets` and `runnerCell.imagePullSecrets` to their names, or use your cluster's
existing registry integration. Create the TLS Secret/certificate using your company's certificate
manager and set `ingress.className`, `host`, `tls` and controller-specific `annotations`.

Replace the example egress CIDRs with approved application, storage and package/git proxy networks.
Include identity exchange endpoints for projected cloud credentials. The chart permits kube-system
DNS on port 53; adapt policies when using NodeLocal/custom DNS. A NetworkPolicy-capable CNI is
required. Test an allowed and prohibited destination from a disposable runner before onboarding
consumers; creating a NetworkPolicy object alone does not establish enforcement.

The control plane separately needs PostgreSQL, storage and Kubernetes API access, plus approved
target access for its single-iteration HTTP validator. Use your company network controls for those
routes. Remote private targets require your routing/VPN/peering/DNS; this application does not
create those connections.

Follow [branding](branding.md) to create `company-logo` and set the name/theme/colors. Put extra
non-secret Spring properties in `applicationConfig`. Start at five admitted runs, one per team,
and a small browser budget. The [configuration reference](configuration-reference.md) lists exact
keys and precedence.

## 11. Render, inspect and install

```bash
helm lint helm/performance-platform -f "$PERF_DEPLOY_DIR/values.yaml"
helm template performance helm/performance-platform --namespace perf-platform \
  -f "$PERF_DEPLOY_DIR/values.yaml" > "$PERF_DEPLOY_DIR/rendered.yaml"
# Inspect images, namespaces, identities, mounts, JDBC URL, ingress and network rules.
helm upgrade --install performance helm/performance-platform --namespace perf-platform \
  -f "$PERF_DEPLOY_DIR/values.yaml" --wait --timeout 10m
kubectl -n perf-platform rollout status deployment/performance
kubectl -n perf-platform get pods,service,ingress
```

`fullnameOverride: performance` in the example makes these resource names stable. Production
rendering requires auth/DB Secret references, a private artifact bucket and restricted runner egress.
It rejects universal routes. Existing Secrets must already exist; Helm does not create their values.
This chart references a compatible Kubernetes cluster; it is not cloud-account Terraform.

## 12. Verify the public shell and protected API

Open your URL. Confirm your name, logo, tab title and theme appear before login. Check
`/actuator/health/readiness` and `/actuator/health/liveness`. Connect using the team's private token.
Definitions and reports require team authorization; a public branding file grants no API access.

Register an approved non-production target on the wizard's About step or with
`POST /api/v2/targets`. Use the team in the token and the application/environment that own this URL.
The stored application target determines the destination; changing a test file cannot select an
unregistered target. Choose **New test** and follow the appropriate user guide.

## 13. Verify real execution, then increase load

Run one browser/one iteration and a small API workload first. Confirm the execution banner names
the real Kubernetes provider, `synthetic=false`, nonzero measurements, complete required shards,
objective verdicts and private fingerprint-verified evidence downloads. Inspect the actual Job's
image, resource limits, non-root/read-only security context, deadline and `backoffLimit: 0`.

Verify a deliberately failing check, running cancellation, restart recovery and cross-team denial.
The browser guide explains expected traces/page metrics. For 100 RPS with a concurrency ceiling
of 20, use the [rate example](../../specification/examples/valid/api-100-rps.yaml). This is a rate
target with a concurrency limit, not a guarantee of twenty constantly active users. Validate the
steady stage separately from the full-run average, which includes ramps.

Calibrate generators against a representative target and observe CPU, memory, network and target
saturation before increasing traffic. The local demo is functional proof, not a capacity benchmark
or evidence about your company's application.

## 14. Hand consumers the right access and documentation

Provide the platform URL, authorized team/application/environment, an approved non-production
target, the team's access method, and the [consumer guide](../user-guide/configuration.md). Provide
the [browser walkthrough](../user-guide/browser-testing.md) for reviewed scripts and the
[AI editor/CI guide](../user-guide/ai-editors.md) for API-driven use. A consumer needs neither cloud
administrator access nor direct database/storage credentials.

## 15. Operate, update and recover

Back up PostgreSQL and preserve object storage independently. Maintain artifact retention and
protect sensitive failure evidence. Monitor Job failures, queue time and generator resource use.
The production profile currently denies the public Prometheus endpoint; do not assume enabling
the old bundled dashboard supplies a working authenticated metrics integration.

For an upgrade: record current revision/images/values, back up durable stores, read migrations,
run checks, publish new immutable tags, test in staging, and apply the new values. `helm rollback`
rolls Kubernetes resources back; it does not reverse Flyway database migrations. Prefer compatible
roll-forward migrations and test backup restoration. Do not delete the database/bucket when
restarting API Pods or uninstalling the chart. A running performance Job must not be replayed as
a generic recovery step.

Use [troubleshooting](troubleshooting.md) for common failures. Contribute reusable integrations
behind the [documented ports](../development/extending.md); keep company coordinates and secrets
in deployment configuration rather than hardcoding them into the fork.

# Broker-backed run://session

`run://session` means the specification needs short-lived application sessions for the duration of a run, but the specification and trigger request must not carry those session values.

Use this when a test needs a real logged-in application context and replaying a static bearer token, cookie or refresh token would be unsafe or already expired.

Related contract: [session-broker-contract.md](session-broker-contract.md). Example specification: [session-broker-api.yaml](../../specification/examples/valid/session-broker-api.yaml). Example Helm overlay: [session-broker-production-values.yaml](../../helm/performance-platform/examples/session-broker-production-values.yaml).

---

## Runtime flow

1. A definition version declares an authentication entry with `secretRef: run://session`.
2. `POST /api/v2/test-runs` references only the immutable definition version. For broker-backed production runs it does not supply a session value.
3. Reconciliation compiles the plan and asks the configured `SessionAcquisitionProvider` for enough leases to cover the run deadline.
4. The approved provider calls the external session broker using a client credential resolved through `SecretResolver`.
5. The control plane writes this shard's lease partition as a run-scoped Kubernetes Secret in the runner namespace.
6. The runner Job mounts that Secret read-only as `/workspace/sessions.csv`; `PERF_SESSION_FILES` contains `sessions.csv`.
7. The Kubernetes execution provider deletes run-scoped Secrets on terminal observation or cancellation. The lifecycle sweeper also deletes runner-owned Secrets whose delete-after annotation has elapsed.

No session value is stored in the specification, execution plan, database, artifact store, Job labels, Job annotations, command arguments or normal logs.

## Specification shape

```yaml
spec:
  authentication:
    - name: broker-session
      type: bearer
      injectAs: Authorization
      secretRef: run://session

  workloads:
    - name: authenticated-api-load
      type: protocol
      engine: jmeter
      authRef: broker-session
```

`run://session` is reserved for pooled application sessions. Other `run://...` references remain scalar runtime credentials and are local-development compatibility only unless another production provider is explicitly approved.

## Trigger shape

Broker-backed production trigger:

```http
POST /api/v2/test-runs
Idempotency-Key: nightly-authenticated-api-2026-08-03
Content-Type: application/json
```

```json
{
  "definitionVersionId": "3f2b1c8e-5d4a-4a1b-9c7e-2f6d8b0a1e33"
}
```

Do not include `credentials.session` for the broker-backed path. The old runtime credential seed exists only so local development can exercise `run://session` without a broker.

## Control-plane mounted Secret layout

The broker client credential is resolved by the control plane, not by runner Pods. With the default mount root:

```text
/var/run/perfplatform/secrets/
  session-broker-client/
    token
```

Reference it as:

```yaml
sessionAcquisition:
  clientCredentialSecretRef: k8s://session-broker-client/token
```

For a namespace-qualified reference:

```text
/var/run/perfplatform/secrets/
  perf-platform/
    session-broker-client/
      token
```

Reference it as:

```yaml
sessionAcquisition:
  clientCredentialSecretRef: k8s://perf-platform/session-broker-client/token
```

The Helm chart mounts existing Kubernetes Secrets listed under `secretResolver.mountedSecrets`. It does not create them and it never stores their values in `values.yaml`.

Create the Secret out of band:

```bash
kubectl -n perf-platform create secret generic session-broker-client \
  --from-literal=token='<broker-client-bearer-token>'
```

## Runner mounted Secret layout

The acquired sessions are delivered separately, in the runner namespace, as a per-shard Secret named from the runner Job:

```text
perf-<run-prefix>-<shard-index>-sessions
```

It is mounted read-only into the runner workspace:

```text
/workspace/sessions.csv
```

The file has this header:

```csv
session_id,session_value,session_expires_at
```

`session_value` is the exact header value the engine injects. For bearer authentication that means the value includes the `Bearer ` prefix.

Example file shape:

```csv
session_id,session_value,session_expires_at
broker-session-0001,Bearer eyJhbGciOi...,2026-08-03T11:00:00Z
broker-session-0002,Bearer eyJhbGciOi...,2026-08-03T11:00:00Z
```

The Secret carries:

```yaml
metadata:
  labels:
    app.kubernetes.io/managed-by: performance-testing-platform
    perfplatform.ameriprise.com/run-id: <run uuid>
    perfplatform.ameriprise.com/shard-id: <shard uuid>
  annotations:
    perfplatform.ameriprise.com/secret-expires-at: "2026-08-03T11:00:00Z"
    perfplatform.ameriprise.com/secret-delete-after: "2026-08-03T10:30:00Z"
```

Operators should treat these Secrets as ephemeral run material. They are not backups, evidence or user-facing artifacts.

## Helm production settings

The minimum S5 settings for broker-backed session acquisition are:

```yaml
sessionAcquisition:
  provider: external-session-broker
  endpoint: https://session-broker.perf-platform.svc.cluster.local
  requestTimeout: 10s
  clientCredentialSecretRef: k8s://session-broker-client/token

secretResolver:
  mountRoot: /var/run/perfplatform/secrets
  mountedSecrets:
    - name: session-broker-client

lifecycleCleanup:
  enabled: true
  interval: 1m
```

Use the full overlay in [session-broker-production-values.yaml](../../helm/performance-platform/examples/session-broker-production-values.yaml) as the starting point for production-like installs.

S5 production session delivery still depends on the wider production posture: Kubernetes execution, durable object storage, pinned runner images, scoped runner RBAC and a non-development identity provider.

## Production readiness smoke

Before trusting broker-backed `run://session` in a production-like cluster, run
[kind smoke: broker-backed run://session](kind-session-broker-smoke.md). It exercises the real Helm
mount, the `external-session-broker` provider, the Kubernetes runner Secret and both normal and TTL
cleanup paths against a stub broker Service.

## Diagnosing failed acquisition

Start with the run events:

```bash
curl -s -H "X-API-Key: $KEY" \
  localhost:18080/api/v2/test-runs/<runId>/events
```

Most acquisition failures surface as `PROVISIONING_FAILED`. The user-facing reason is sanitized on
purpose; use cluster evidence to find the concrete cause.

### 1. Provider configuration

```bash
kubectl -n <platform-namespace> get deploy <release>-performance-platform \
  -o jsonpath='{range .spec.template.spec.containers[0].env[*]}{.name}={.value}{"\n"}{end}' \
  | grep PLATFORM_ORCHESTRATION_SESSIONACQUISITION
```

Expected production settings:

- `PROVIDER=external-session-broker`;
- `ENDPOINT` points at the approved broker Service or URL;
- `CLIENTCREDENTIALSECRETREF` is a `k8s://...` reference;
- the trigger body did not include `credentials.session`.

If `runtime-credential` is active under a production profile, startup should fail. Do not work around
that by seeding a session in the trigger request.

### 2. Mounted client Secret

```bash
kubectl -n <platform-namespace> get deploy <release>-performance-platform \
  -o jsonpath='{.spec.template.spec.volumes[*].secret.secretName}'; echo

kubectl -n <platform-namespace> exec deploy/<release>-performance-platform -- \
  sh -c 'test -s /var/run/perfplatform/secrets/session-broker-client/token'
```

Failure signatures:

| Symptom | Likely cause | Fix |
|---|---|---|
| `mounted-file resolver supports only k8s:// references` | `clientCredentialSecretRef` uses an unsupported scheme | Use `k8s://secret-name/key` for the mounted-file resolver |
| `mounted file is not readable` | Secret was not mounted, key is wrong or mount root differs | Create the Secret out of band and include it in `secretResolver.mountedSecrets` |
| `mounted file is empty` | Secret key exists but has no value | Recreate the Secret with a non-empty broker client credential |

Never print the mounted file contents in a ticket or runbook transcript.

### 3. Broker reachability and contract

From the control-plane namespace:

```bash
kubectl -n <platform-namespace> run broker-probe --rm -i --restart=Never \
  --image=curlimages/curl:8.11.1 -- \
  curl -sS -o /tmp/response.json -w 'status=%{http_code}\n' \
  -H 'Authorization: Bearer probe' \
  -H 'Content-Type: application/json' \
  <broker-url>/v1/sessions:acquire
```

Use a real probe token only in a private terminal. For the checked-in kind smoke, the token is
synthetic.

Failure signatures:

| Symptom | Likely cause | Fix |
|---|---|---|
| DNS or connection timeout | Service name, namespace, NetworkPolicy or egress route is wrong | Fix endpoint or allow egress from the control-plane namespace |
| HTTP 401/403 | mounted broker credential is wrong or broker rejects the client identity | Rotate/fix the mounted Secret or broker policy |
| HTTP 404 | `loginPath`/`refreshPath` mismatch | Use `/v1/sessions:acquire` and `/v1/sessions:refresh`, or configure broker-specific paths |
| Invalid JSON or missing fields | broker response does not match `session-broker-contract.md` | Fix broker response: `sessionId`, `sessionValue`, `expiresAt` |
| `expires before the requested validity window` | broker lease is too short for the run deadline | Increase lease duration or reduce the test duration |

Broker logs may include run id, authentication name and status. They must not include
`sessionValue`, cookies or client credential material.

### 4. Runner session Secret

After the run leaves `QUEUED`, find runner-owned Secrets by run id:

```bash
kubectl -n <runner-namespace> get secrets \
  -l app.kubernetes.io/managed-by=performance-testing-platform,perfplatform.ameriprise.com/run-id=<runId>
```

Expected:

- a `perf-<run-prefix>-<shard>-sessions` Secret exists while the Job is active;
- it has `secret-expires-at` and `secret-delete-after` annotations;
- the runner Job mounts it read-only as `/workspace/sessions.csv`;
- the Job YAML contains the Secret name and `PERF_SESSION_FILES=sessions.csv`, not the session value.

If no session Secret exists, check broker acquisition first. If the Secret exists but the Pod is stuck
in `ContainerCreating`, inspect Pod events:

```bash
kubectl -n <runner-namespace> describe pod -l perfplatform.ameriprise.com/run-id=<runId>
```

Missing Secret mount errors usually mean the Secret was deleted before the Pod started. That points
to an incorrect `secret-delete-after`, an overly short broker lease or a clock problem.

### 5. Cleanup

Normal terminal observation deletes runner session Secrets immediately. If a Secret remains:

```bash
kubectl -n <runner-namespace> get secret <secret-name> -o yaml \
  | grep 'perfplatform.ameriprise.com/secret-delete-after'

kubectl -n <platform-namespace> logs deploy/<release>-performance-platform \
  | grep 'Runner lifecycle cleanup'
```

If `lifecycleCleanup.enabled=false`, stranded session Secrets remain until an operator deletes them.
Production-like installs should keep cleanup enabled. The cleanup Role is namespace-scoped and only
selects runner-managed Secrets with the delete-after annotation; do not broaden it to cluster-wide
Secret deletion.

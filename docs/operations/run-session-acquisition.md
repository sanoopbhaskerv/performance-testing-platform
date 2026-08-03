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


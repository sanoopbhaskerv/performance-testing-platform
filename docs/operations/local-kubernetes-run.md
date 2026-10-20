# Running the platform on a local Kubernetes cluster

This is the procedure that first proved the Kubernetes execution path works end to end, and the one
to repeat after changing the runner image, the Job manifest or the artifact store.

For the production `run://session` path, run the dedicated
[kind broker-backed session smoke](kind-session-broker-smoke.md) after this base Kubernetes path is
working. It adds a stub session broker Service, a mounted broker-client Secret and checks runner
session Secret creation and cleanup.

**It found three real defects that Docker and a mock API server did not.** They are recorded at the
bottom, because the point of writing this down is that the next person runs it rather than assuming.

---

## What this exercises that nothing else does

| | Covered by unit/contract tests | Only covered here |
|---|---|---|
| Job manifest shape | `RunnerJobManifestTest` | Whether the API server **admits** it |
| Provider observe/cancel | `KubernetesExecutionProviderTest` (mock server) | Real Job and Pod lifecycle |
| Runner behaviour | Docker run against MinIO | `fsGroup` on an `emptyDir`, image pull, PodSecurity |
| RBAC | `helm template` renders it | Whether it actually permits what the provider does |
| Artifact round trip | `S3ArtifactStoreMinioTest` | Runner-written objects, adopted by the control plane |

## Prerequisites

```bash
brew install kind
# kubectl and Docker are assumed
```

## 1. Cluster and images

```bash
kind create cluster --name perf-e2e --wait 120s

JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -DskipTests package
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -f demo-service/pom.xml -DskipTests package

docker build -t perfplatform/jmeter-runner:5.6.3-3 runner-images/jmeter-runner
docker build -t perfplatform/platform-core:1.0.0 platform-core
docker build -t perfplatform/demo-service:1.0.0 demo-service

for i in jmeter-runner:5.6.3-3 platform-core:1.0.0 demo-service:1.0.0; do
  kind load docker-image perfplatform/$i --name perf-e2e
done
```

`kind load` matters: the images are local only, so a Pod would otherwise sit in `ImagePullBackOff`.

## 2. The runner cell

Apply the chart's own RBAC rather than hand-writing it — hand-written RBAC proves nothing about
what ships:

```bash
helm template perf helm/performance-platform \
  --set artifacts.bucket=perf-artifacts \
  --show-only templates/runner-cell.yaml | kubectl apply -f -

helm template perf helm/performance-platform \
  --set artifacts.bucket=perf-artifacts \
  --show-only templates/serviceaccount.yaml | kubectl apply -f -
```

Then check the permissions are what was intended — including what must *not* be granted:

```bash
SA=system:serviceaccount:default:perf-performance-platform-sa
kubectl auth can-i create jobs --as=$SA -n perf-runners   # yes
kubectl auth can-i delete jobs --as=$SA -n perf-runners   # yes
kubectl auth can-i create secrets --as=$SA -n perf-runners # yes — run-scoped runtime credential
                                                          #       and session-file Secrets
kubectl auth can-i delete secrets --as=$SA -n perf-runners # yes — terminal cleanup and TTL sweep
kubectl auth can-i patch  jobs --as=$SA -n perf-runners   # no  — an edited Job is a test whose
                                                          #       plan digest no longer describes it
kubectl auth can-i create jobs \
  --as=system:serviceaccount:perf-runners:perf-runner -n perf-runners   # no — a runner that could
                                                          #       create Jobs could create load
                                                          #       nobody asked for
kubectl auth can-i get secrets \
  --as=system:serviceaccount:perf-runners:perf-runner -n perf-runners    # no — runner Pods mount
                                                          #       their own Secrets; the runner
                                                          #       identity cannot list them
```

## 3. Supporting services

**Put MinIO and the demo service in their own namespace.** `perf-runners` enforces
`pod-security: restricted` and will reject both — correctly: only runners belong there.

```bash
kubectl create namespace perf-support
# Deploy minio (RELEASE.2024-11-07T00-52-20Z) and demo-service into perf-support,
# each with a Service on 9000 and 8081.
kubectl -n perf-support exec deploy/minio -- mc alias set local http://localhost:9000 minio minio123
kubectl -n perf-support exec deploy/minio -- mc mb local/perf-artifacts
```

MinIO only understands access keys, so the runner needs a Secret. **A name reaches the manifest,
never a value:**

```bash
kubectl -n perf-runners create secret generic runner-storage-credentials \
  --from-literal=AWS_ACCESS_KEY_ID=minio \
  --from-literal=AWS_SECRET_ACCESS_KEY=minio123
```

## 4. The control plane, in the cluster

Run it **inside** the cluster, not on the host. Only then are the service account, the RBAC binding
and in-cluster DNS actually exercised — and the target has to be resolvable from wherever the control
plane runs, so a host process could not address `demo-service.perf-support.svc.cluster.local` anyway.

Key settings:

```yaml
PLATFORM_ORCHESTRATION_EXECUTIONPROVIDER: kubernetes
PLATFORM_ORCHESTRATION_RUNNERCELL_NAMESPACE: perf-runners
PLATFORM_ORCHESTRATION_RUNNERCELL_SERVICEACCOUNT: perf-runner
PLATFORM_ORCHESTRATION_RUNNERCELL_IMAGES_JMETER: perfplatform/jmeter-runner:5.6.3-3
PLATFORM_ORCHESTRATION_ARTIFACTS_STORE: s3
PLATFORM_ORCHESTRATION_ARTIFACTS_BUCKET: perf-artifacts
PLATFORM_ORCHESTRATION_ARTIFACTS_ENDPOINT: http://minio.perf-support.svc.cluster.local:9000
PLATFORM_ORCHESTRATION_ARTIFACTS_PATHSTYLEACCESS: "true"
PLATFORM_ORCHESTRATION_ARTIFACTS_RUNNERCREDENTIALSSECRET: runner-storage-credentials
```

The team's target must point at the in-cluster service:

```yaml
targets:
  - name: demo-service
    baseUrl: "http://demo-service.perf-support.svc.cluster.local:8081"
```

## 5. Run it

```bash
kubectl -n default port-forward svc/platform-core 18080:8080 &
KEY=local-dev-key-not-a-secret

curl -s -H "X-API-Key: $KEY" localhost:18080/api/v2/platform/execution
# {"providerId":"kubernetes","generatesRealLoad":true,...}

curl -s -X POST localhost:18080/api/v2/test-definitions \
  -H "X-API-Key: $KEY" -H 'Content-Type: application/yaml' \
  --data-binary @specification/examples/valid/demo-service.yaml

curl -s -X POST localhost:18080/api/v2/test-runs \
  -H "X-API-Key: $KEY" -H 'Idempotency-Key: kind-1' \
  -H 'Content-Type: application/json' -d '{"definitionVersionId":"<id>"}'

kubectl -n perf-runners get jobs -w
curl -s -H "X-API-Key: $KEY" localhost:18080/api/v2/test-runs/<runId>/report
```

## 6. Check what the cluster actually admitted

The manifest tests assert what is *built*. This asserts what was *accepted*:

```bash
kubectl -n perf-runners get job <name> -o yaml | grep -E \
  'backoffLimit|activeDeadline|restartPolicy|runAsNonRoot|readOnlyRoot|allowPrivilege|drop|seccomp'
```

Expect `backoffLimit: 0`, `restartPolicy: Never`, `runAsNonRoot: true`, `readOnlyRootFilesystem:
true`, `capabilities.drop: [ALL]`, `seccompProfile: RuntimeDefault`. And confirm no credential value
is present:

```bash
kubectl -n perf-runners get job <name> -o json | grep -c 'AWS_SECRET_ACCESS_KEY'   # 0
```

## 7. Check live monitoring (S7, never yet run on a cluster)

The runner-image half of live monitoring has only been verified through the local provider. This is
the procedure that would confirm it in a pod.

```bash
# The snapshot appears within ~10s of the Job starting, and its timestamp advances every ~5s.
kubectl -n perf-runners exec deploy/minio-client -- \
  mc ls --recursive local/perf-artifacts | grep progress.json
```

```bash
# The control plane merged it.
curl -s "http://localhost:18080/api/v2/test-runs/$RUN_ID/progress" -H "X-API-Key: $KEY" | jq \
  '{age: .ageSeconds, samples: .overall.sampleCount, shards: .shards, obs: [.observations[].code]}'
```

Four things to confirm, in order:

1. `progress.json` exists under `teams/{team}/runs/{run}/shards/{shard}/` and its modification time
   advances every ~5 seconds.
2. `GET …/progress` returns `200` with a `sampleCount` that grows between polls, and `ageSeconds`
   stays under ~20.
3. **Delete one Job of a two-shard run.** Within the heartbeat tolerance the response carries
   `RUNNER_SILENT`, that shard's `ageSeconds` keeps climbing, and its `sampleCount` stops — while
   the surviving shard's keeps rising. This is the case merged totals cannot show, and the reason
   the per-shard block exists.
4. After the Job finishes, the last snapshot is still readable from object storage and matches the
   final point in the series.

If `progress.json` never appears, check `PERF_PROGRESS_INTERVAL_SECONDS` in the Job's environment
and the runner's own log — publishing failures are swallowed by design, so the only trace is there.

## Expected result

A `Complete` Job, ~8,000 requests, and a report showing **two passes and one breach** — the demo
service's payment path is slow and fails ~4-5% on purpose. The run ends `FAILED` with
`OBJECTIVE_BREACHED`, which is the correct outcome for a test whose objective was not met.

## Teardown

```bash
kind delete cluster --name perf-e2e
```

---

## What this procedure found the first time

Recorded because each was invisible to every other form of testing.

1. **The runner had no way to authenticate.** The design said "the Pod's projected service-account
   token authorises it", which is true on EKS with IRSA and false for every S3-compatible store that
   only understands access keys. The Job ran, generated no load, and failed on `Unable to locate
   credentials`. Fixed by referencing a Secret **by name** in the Job manifest.

2. **The control plane could not see what the runner uploaded.** `S3ArtifactStore.listForRun`
   identified artifacts by object metadata that only its own uploads set — a runner uploading with an
   ordinary S3 client sets none. Every runner-produced file was silently skipped, and the run reported
   *"0 of 1 runner shards reported"* rather than any error. Fixed by deriving identity from the object
   key, which is the contract both sides actually share.

3. **The shard was lost even once artifacts were found.** References rebuilt from storage carried no
   shard id, and analysis matches results to the shard that produced them — so results would have
   been adopted and still been invisible. Fixed alongside (2).

The pattern is worth noticing: all three presented as *a run that measured nothing*, never as an
error. That is the failure mode this platform has to be most suspicious of.

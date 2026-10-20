# kind smoke: broker-backed run://session

This smoke test extends [the local Kubernetes run](local-kubernetes-run.md). It proves the
production `run://session` path in a real API server:

- the control plane resolves a mounted broker-client Secret;
- the `external-session-broker` provider calls a broker Service, not trigger-supplied material;
- the Kubernetes execution provider creates a per-run runner Secret containing `sessions.csv`;
- the runner Job mounts that file by name only;
- terminal observation or lifecycle cleanup removes the runner session Secret.

Run this after changing session acquisition, the Helm chart, the runner Job manifest or runner
Secret lifecycle code.

## Prerequisites

Finish sections 1-4 of [local-kubernetes-run.md](local-kubernetes-run.md) first, with one change:
install the control plane through Helm so the mounted Secret volume comes from the chart.

The smoke uses a stub broker in `perf-support`. It is not an authentication system; it only checks
the platform-to-broker contract and returns synthetic short-lived sessions.

## 1. Stub broker Service

```bash
kubectl -n perf-support apply -f - <<'YAML'
apiVersion: v1
kind: ConfigMap
metadata:
  name: session-broker-stub
data:
  server.py: |
    import json
    from datetime import datetime, timedelta, timezone
    from http.server import BaseHTTPRequestHandler, HTTPServer

    TOKEN = "kind-broker-client-token"

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            length = int(self.headers.get("content-length", "0"))
            body = json.loads(self.rfile.read(length) or "{}")
            auth = self.headers.get("authorization", "")
            if auth != f"Bearer {TOKEN}":
                self.send_response(401)
                self.end_headers()
                return
            if self.path not in ["/v1/sessions:acquire", "/v1/sessions:refresh"]:
                self.send_response(404)
                self.end_headers()
                return
            run_id = body.get("runId", "missing-run")
            index = body.get("sessionIndex", 0)
            print(
                f"operation={body.get('operation')} runId={run_id} "
                f"correlation={self.headers.get('X-Performance-Test-Run-Id', '')}",
                flush=True,
            )
            expires = datetime.now(timezone.utc) + timedelta(hours=1)
            response = {
                "sessionId": f"kind-{run_id[:8]}-{index}",
                "sessionValue": f"Bearer kind-session-{run_id[:8]}-{index}",
                "expiresAt": expires.isoformat().replace("+00:00", "Z"),
            }
            self.send_response(200)
            self.send_header("content-type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(response).encode("utf-8"))

        def log_message(self, fmt, *args):
            print(fmt % args, flush=True)

    HTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: session-broker-stub
spec:
  replicas: 1
  selector:
    matchLabels:
      app: session-broker-stub
  template:
    metadata:
      labels:
        app: session-broker-stub
    spec:
      containers:
        - name: broker
          image: python:3.12-alpine
          command: ["python", "/app/server.py"]
          ports:
            - containerPort: 8080
          volumeMounts:
            - name: script
              mountPath: /app
              readOnly: true
      volumes:
        - name: script
          configMap:
            name: session-broker-stub
---
apiVersion: v1
kind: Service
metadata:
  name: session-broker
spec:
  selector:
    app: session-broker-stub
  ports:
    - name: http
      port: 8080
      targetPort: 8080
YAML

kubectl -n perf-support rollout status deploy/session-broker-stub
```

## 2. Mounted broker client Secret

The broker client credential belongs to the control plane. The runner never receives it.

```bash
kubectl -n default create secret generic session-broker-client \
  --from-literal=token=kind-broker-client-token
```

Install or upgrade the chart with the mounted Secret and the stub broker endpoint:

```bash
cat >/tmp/perf-session-broker-values.yaml <<'YAML'
image:
  repository: perfplatform/platform-core
  tag: "1.0.0"
  pullPolicy: IfNotPresent

artifacts:
  store: s3
  bucket: perf-artifacts
  region: us-east-1
  endpoint: http://minio.perf-support.svc.cluster.local:9000
  pathStyleAccess: true
  runnerEndpoint: http://minio.perf-support.svc.cluster.local:9000
  runnerCredentialsSecret: runner-storage-credentials

runnerCell:
  enabled: true
  namespace: perf-runners
  serviceAccount: perf-runner
  images:
    jmeter: perfplatform/jmeter-runner:5.6.3-3

sessionAcquisition:
  provider: external-session-broker
  endpoint: http://session-broker.perf-support.svc.cluster.local:8080
  requestTimeout: 10s
  clientCredentialSecretRef: k8s://session-broker-client/token

secretResolver:
  mountRoot: /var/run/perfplatform/secrets
  mountedSecrets:
    - name: session-broker-client

lifecycleCleanup:
  enabled: true
  interval: 15s

teamConfigs:
  advice-implementation: |
    team: advice-implementation
    apiKey: "advice-kind-key"
    targets:
      - name: proposal
        baseUrl: "http://demo-service.perf-support.svc.cluster.local:8081"
        jvmEnabled: false

postgres:
  enabled: true
YAML

helm upgrade --install perf helm/performance-platform \
  --namespace default \
  -f /tmp/perf-session-broker-values.yaml

kubectl -n default rollout status deploy/perf-performance-platform
```

Verify that the control plane mounted the Secret by name, not value:

```bash
kubectl -n default get deploy perf-performance-platform -o jsonpath='{.spec.template.spec.volumes[*].secret.secretName}'; echo
kubectl -n default get deploy perf-performance-platform -o yaml | grep -c kind-broker-client-token
# expected: 0
```

## 3. Trigger a broker-backed run

```bash
kubectl -n default port-forward svc/perf-performance-platform 18080:8080 &
KEY=advice-kind-key

cat >/tmp/session-broker-smoke.yaml <<'YAML'
apiVersion: performance.ameriprise.com/v1alpha1
kind: PerformanceTest
metadata:
  name: kind-session-broker-smoke
  team: advice-implementation
  application: proposal
spec:
  environment:
    name: performance
    targetClass: non-production
  authentication:
    - name: broker-session
      type: bearer
      injectAs: Authorization
      secretRef: run://session
  workloads:
    - name: authenticated-api-smoke
      type: protocol
      engine: jmeter
      authRef: broker-session
      model:
        type: stages
        stages:
          - duration: 10s
            targetUsers: 1
          - duration: 10s
            targetUsers: 0
      scenarios:
        - name: open-users
          weight: 100
          method: GET
          path: /api/users
  objectives:
    - metric: http.error.rate
      scope: authenticated-api-smoke
      threshold: 100%
  governance:
    maxDuration: 2m
    approvalPolicy: non-production-standard
    retention: 1d
YAML

curl -s -X POST localhost:18080/api/v2/test-definitions \
  -H "X-API-Key: $KEY" -H 'Content-Type: application/yaml' \
  --data-binary @/tmp/session-broker-smoke.yaml

curl -s -X POST localhost:18080/api/v2/test-runs \
  -H "X-API-Key: $KEY" \
  -H "Idempotency-Key: kind-session-broker-1" \
  -H 'Content-Type: application/json' \
  -d '{"definitionVersionId":"<id from the previous response>"}'
```

The trigger request must not include `credentials.session`.

## 4. Inspect acquisition and session Secret delivery

The broker should show one request per acquired lease:

```bash
kubectl -n perf-support logs deploy/session-broker-stub
```

Find the run and session Secret:

```bash
RUN_ID=<run uuid>
kubectl -n perf-runners get secrets \
  -l app.kubernetes.io/managed-by=performance-testing-platform,perfplatform.ameriprise.com/run-id=$RUN_ID
```

Expected while the runner is active:

- a `perf-<run-prefix>-0-sessions` Secret exists;
- it has a `perfplatform.ameriprise.com/secret-delete-after` annotation;
- the corresponding Job mounts it read-only as `/workspace/sessions.csv`;
- the Job manifest does not contain `kind-session-` or `kind-broker-client-token`.

```bash
JOB=$(kubectl -n perf-runners get jobs \
  -l perfplatform.ameriprise.com/run-id=$RUN_ID \
  -o jsonpath='{.items[0].metadata.name}')

kubectl -n perf-runners get job "$JOB" -o jsonpath='{.spec.template.spec.volumes[?(@.name=="session-files")].secret.secretName}'; echo
kubectl -n perf-runners get job "$JOB" -o jsonpath='{.spec.template.spec.containers[0].volumeMounts[?(@.mountPath=="/workspace/sessions.csv")].readOnly}'; echo
kubectl -n perf-runners get job "$JOB" -o yaml | grep -c 'kind-session-\|kind-broker-client-token'
# expected: 0
```

Do not turn `sessions.csv` into an artifact check. It is live credential material and is deliberately
not downloadable evidence.

## 5. Verify cleanup

Normal terminal observation deletes the session Secret:

```bash
kubectl -n perf-runners get jobs -w
kubectl -n perf-runners get secrets \
  -l app.kubernetes.io/managed-by=performance-testing-platform,perfplatform.ameriprise.com/run-id=$RUN_ID
# expected after terminal observation: no resources found
```

If the control plane dies before observing the Job, the lifecycle sweeper deletes after the
`secret-delete-after` timestamp. To prove the sweeper specifically, temporarily scale the control
plane down after the session Secret appears, wait past the annotation, then scale it back up:

```bash
kubectl -n default scale deploy/perf-performance-platform --replicas=0
# wait until secret-delete-after has passed
kubectl -n default scale deploy/perf-performance-platform --replicas=1
kubectl -n default logs deploy/perf-performance-platform | grep 'Runner lifecycle cleanup'
```

## Pass criteria

The smoke passes when all of these are true:

- broker logs show accepted `/v1/sessions:acquire` requests with the run correlation header;
- no broker client token or session value appears in the Deployment or Job YAML;
- a runner-owned `*-sessions` Secret exists while the Job is active;
- the Job mounts `/workspace/sessions.csv` read-only and uses only the file name in
  `PERF_SESSION_FILES`;
- the session Secret is gone after terminal observation or after the lifecycle sweeper runs.

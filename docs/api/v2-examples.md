# V2 API Examples

**Status:** Increment 1B — validation only. Definition creation, versioning and run endpoints arrive in 1B/1C follow-ups.
**Schema:** [`specification/schema/v1alpha1/performance-test.schema.json`](../../specification/schema/v1alpha1/performance-test.schema.json)
**Worked examples:** [`specification/examples/`](../../specification/examples/)

`/api/v1/**` remains operational and unchanged. V2 is additive and never reuses a V1 path.

---

## Available today

```text
POST /api/v2/test-definitions/validate
POST /api/v2/test-runs
GET  /api/v2/test-runs/{runId}
GET  /api/v2/test-runs/{runId}/events
POST /api/v2/test-runs/{runId}/cancel
```

Validates a specification. **Creates nothing and executes nothing** — safe to call from a pull
request as a status check.

Accepts `application/yaml`, `application/x-yaml`, `text/yaml`, `application/json` or
`text/plain`. YAML and JSON are two serializations of the same canonical model, so an equivalent
document in either form produces the same digest.

Authentication is the existing `X-API-Key` header. (V1's known cross-team authorization gap
applies here too until Increment 4 — see the register in
[`../migration/baseline-report.md`](../migration/baseline-report.md).)

---

## Validating a specification

```bash
curl -X POST http://localhost:8080/api/v2/test-definitions/validate \
  -H "X-API-Key: $PERF_API_KEY" \
  -H "Content-Type: application/yaml" \
  --data-binary @specification/examples/valid/api-only.yaml
```

### Valid specification

```json
{
  "valid": true,
  "digest": "sha256:235e6fd6bef56732a747d3f28d0e735aceb24534b540d1ece6753826eaae4557",
  "summary": { "errors": 0, "warnings": 0, "info": 0 },
  "findings": []
}
```

The `digest` is the canonical identity of the test. Two specifications that mean the same thing
produce the same digest regardless of key order, comment or formatting differences, or whether
a duration was written `60m` or `1h`. Compare it against the digest of the current version to
tell whether an edit actually changed anything before creating a new version.

A digest is returned **only when the specification is valid** — issuing one for a rejected
specification would imply it is a versionable identity.

### Invalid specification

```json
{
  "valid": false,
  "digest": null,
  "summary": { "errors": 1, "warnings": 1, "info": 0 },
  "findings": [
    {
      "code": "spec.secret.inline-value",
      "severity": "ERROR",
      "path": "/spec/authentication/0/secretRef",
      "message": "This field appears to contain a credential value. Specifications must contain secret references only.",
      "remediation": "Store the credential in the approved secret manager and reference it, for example vault://performance/<app>/<credential>. Supported schemes: vault://, awssecrets://, azurekeyvault://, k8s://, env://"
    },
    {
      "code": "spec.governance.maxDuration.missing",
      "severity": "WARNING",
      "path": "/spec/governance/maxDuration",
      "message": "No maximum duration is declared, so the platform default applies.",
      "remediation": "Declare maxDuration to make the intended ceiling explicit."
    }
  ]
}
```

---

## Response contract

| Field | Meaning |
|---|---|
| `valid` | Whether the specification can be versioned and executed. **Warnings do not make it invalid.** |
| `digest` | Canonical `sha256:<hex>` identity. `null` when invalid. |
| `summary` | Counts by severity, so CI can decide without walking `findings`. |
| `findings` | Every finding from every layer that ran, errors first, in a stable order. |

### Why validation returns `200`, not `400`

An invalid specification is still a successfully answered request — the findings *are* the
answer. Returning `400` would conflate "your document has problems" with "your request was
malformed", and would force a pipeline to treat a successful validation reporting errors as a
transport failure. Problems with the **request itself** (unreadable body, unsupported content
type) do return RFC 7807 problem responses.

**Branch on `valid`, never on the HTTP status.**

### Finding codes are a stable contract

`code` is a machine-readable identifier that does not change once released — pipelines key on it.
`message` and `remediation` are human-facing and may be reworded at any time. `path` is a
JSON-pointer into the submitted document.

`severity` is `ERROR`, `WARNING` or `INFO`. Only `ERROR` blocks.

---

## What validation does and does not check

Runs today:

1. **Structural** — is the document well formed, are types correct, are there unknown fields.
   Unknown fields are rejected rather than ignored: silently dropping `targetUser` (a typo for
   `targetUsers`) would produce a test running at a load nobody chose.
2. **Semantic** — do cross-references resolve (`sourceRef`, objective `scope`, `startWhen`), are
   weights and percentiles sane, is the git ref immutable, does `secretRef` hold a reference
   rather than a credential.

Both layers always run, so one response reports every problem rather than one field per round
trip.

Deliberately **not** run here:

- **Policy** — team quotas, target allowlists, duration ceilings. Enforced server-side by
  admission control at trigger time against configured limits, never against values the document
  supplies about itself.
- **Connectivity preflight** — DNS, TLS, target health. Requires the network and runs from the
  runner cell that will execute the test. Including it would make validation slow, flaky and
  unusable in a pull request.

A specification that validates here can still be rejected at trigger time by policy. That is by
design: validation answers "is this test well formed", not "are you allowed to run it".

---

## Common findings

| Code | Severity | Meaning |
|---|---|---|
| `spec.parse.unknownField` | ERROR | Field not defined by the schema — usually a typo |
| `spec.parse.type.invalid` | ERROR | Wrong type at `path` |
| `spec.parse.threshold.invalid` | ERROR | Threshold missing a unit; state `750ms`, `8s` or `1%` |
| `spec.parse.duration.invalid` | ERROR | Use `30s`, `5m`, `2h`, `90d` or ISO-8601 |
| `spec.parse.enum.invalid` | ERROR | Unsupported value; message lists the permitted set |
| `spec.secret.inline-value` | ERROR | A credential value where a reference belongs |
| `spec.source.git.ref.mutable` | ERROR | Playwright source pinned to a branch, not a commit SHA |
| `spec.workload.sourceRef.unresolved` | ERROR | `sourceRef` names no declared source |
| `spec.objective.scope.unresolved` | ERROR | Objective scoped to a workload that is not declared |
| `spec.governance.maxDuration.exceeded` | ERROR | Stages run longer than the declared ceiling |
| `spec.scenario.weights.sum` | WARNING | Weights do not sum to 100; treated as relative shares |
| `spec.dataset.partition.disabled` | WARNING | A sharded run would replay the same rows on every shard |
| `spec.objective.missing` | WARNING | No objectives, so the test cannot pass or fail |

The finding never echoes a suspected credential value: findings are logged and returned over
HTTP, so repeating the value would spread the disclosure.

---

## Using it as a CI status check

```bash
response=$(curl -sS -X POST "$PERF_PLATFORM_URL/api/v2/test-definitions/validate" \
  -H "X-API-Key: $PERF_API_KEY" \
  -H "Content-Type: application/yaml" \
  --data-binary @perf/proposal-baseline.yaml)

echo "$response" | jq -r '.findings[] | "\(.severity) \(.code) \(.path): \(.message)"'

if [ "$(echo "$response" | jq -r '.valid')" != "true" ]; then
  echo "Specification rejected."
  exit 1
fi
```

---

## Triggering a run

```bash
curl -X POST https://performance.example.internal/api/v2/test-runs \
  -H "X-API-Key: $PERF_API_KEY" \
  -H "Idempotency-Key: nightly-baseline-2026-07-29" \
  -H "Content-Type: application/json" \
  -d '{"definitionVersionId":"3f2b1c8e-5d4a-4a1b-9c7e-2f6d8b0a1e33"}'
```

```http
HTTP/1.1 202 Accepted
Location: /api/v2/test-runs/9c1f0b44-2a77-4a1e-8d3b-6f0e2c5a7b91
Idempotent-Replay: false
```

```json
{
  "id": "9c1f0b44-2a77-4a1e-8d3b-6f0e2c5a7b91",
  "teamId": "b81e2f3a-1c44-4a9d-9f21-3d5e7a0c4b18",
  "definitionVersionId": "3f2b1c8e-5d4a-4a1b-9c7e-2f6d8b0a1e33",
  "executionPlanId": null,
  "status": "QUEUED",
  "environment": "performance",
  "requestedBy": "team:advice-implementation",
  "requestedAt": "2026-07-29T22:00:00Z",
  "queuedAt": "2026-07-29T22:00:00Z",
  "cancellationRequested": false
}
```

`202`, not `201`: the run exists but nothing has executed, and reporting it as created would suggest
the test had started. The request never blocks — a performance test runs for minutes to hours.

The run's team comes from the definition version, never from the request. A caller states *what* to
run, not who to bill it to.

### The idempotency key is required

Repeating a request with the same key returns the **original run** and sets
`Idempotent-Replay: true`. This matters more here than in most APIs: the duplicate would not be a
wasted row but a second load test against a real system, doubling the traffic and invalidating both
sets of results.

Keys are scoped per team, so two teams can both use `nightly-baseline`.

Reusing a key for a *different* definition version is a `409`, not a replay:

```json
{
  "type": "https://performance.ameriprise.com/problems/run.idempotencyKey.conflict",
  "status": 409,
  "code": "run.idempotencyKey.conflict",
  "detail": "This idempotency key was already used for a different request. Use a new key, or resubmit the original request unchanged.",
  "existingRunId": "9c1f0b44-2a77-4a1e-8d3b-6f0e2c5a7b91",
  "correlationId": "…"
}
```

Silently returning the first run would tell a pipeline its new test had been accepted when it had
not, and it would read the old run's results as if they described the new one.

### Trigger failures

| Situation | Status | `code` |
|---|---|---|
| No `Idempotency-Key` header | 400 | `request.header.missing` |
| Missing or malformed `definitionVersionId` | 400 | `request.body.invalid` |
| No such definition version | 404 | `definitionVersion.notFound` |
| Version failed validation | 422 | `definitionVersion.notExecutable` |
| Key reused for a different version | 409 | `run.idempotencyKey.conflict` |
| Caller not permitted | 403 | `auth.forbidden` |

`422` rather than `400` for an unrunnable version: the request was well formed and understood, and
the problem is with what it refers to. A pipeline can tell "I sent bad JSON" from "the test itself is
not runnable".

---

## Polling a run

```bash
curl https://performance.example.internal/api/v2/test-runs/$RUN_ID \
  -H "X-API-Key: $PERF_API_KEY"
```

`status` moves `QUEUED → PROVISIONING → RUNNING → ANALYZING → COMPLETED`. Terminal states are
`COMPLETED`, `FAILED`, `CANCELLED`, `REJECTED` and `EXPIRED`; see
[run-lifecycle.md](../operations/run-lifecycle.md).

A failed run carries a stable `failureCode` and a sanitized `failureReason`. Neither ever contains a
stack trace, a connection string or a Kubernetes object — those go to the platform's own logs behind
the `correlationId`.

A run identifier that names another team's run returns `404`, the same as one that does not exist.
Returning `403` for one and `404` for the other would turn the endpoint into an oracle for which run
identifiers are real.

---

## Reading a run's history

```bash
curl "https://performance.example.internal/api/v2/test-runs/$RUN_ID/events?afterSequence=12" \
  -H "X-API-Key: $PERF_API_KEY"
```

```json
[
  {
    "sequence": 13,
    "type": "STATE_CHANGED",
    "previousState": "QUEUED",
    "newState": "PROVISIONING",
    "actor": "system:reconciler",
    "detail": {"reason": "Admitted by capacity policy"},
    "occurredAt": "2026-07-29T22:04:11Z"
  }
]
```

Append-only, in order. Pass `afterSequence` to resume from the last event seen rather than re-reading
a history that can be long for a run lasting hours — the same cursor Server-Sent Events will use for
resumption in a later increment.

`detail` carries short operational facts only. It never contains request bodies, headers or secret
values: run events are read by every member of a team and exported to operational tooling.

---

## Cancelling a run

```bash
curl -X POST "https://performance.example.internal/api/v2/test-runs/$RUN_ID/cancel?reason=Wrong+environment" \
  -H "X-API-Key: $PERF_API_KEY"
```

```json
{
  "outcome": "CANCELLATION_REQUESTED",
  "run": { "id": "9c1f0b44-…", "status": "RUNNING", "cancellationRequested": true }
}
```

`outcome` is reported separately from `status` because they answer different questions. A run still
`RUNNING` with `CANCELLATION_REQUESTED` has been asked to stop and has not stopped yet; a caller
shown only the status would conclude the request had been ignored.

| `outcome` | Meaning |
|---|---|
| `CANCELLED` | Nothing was executing; the run stopped immediately |
| `CANCELLATION_REQUESTED` | A runner is still being terminated |
| `ALREADY_CANCELLED` | The run was already cancelled |

Repeating a cancellation succeeds. Cancelling a run that has *finished* is a `409`
(`run.notCancellable`) — that is a mistake about which run is being stopped, not a retry.

See [cancellation-and-recovery.md](../operations/cancellation-and-recovery.md).

---

## Planned endpoints

Not yet implemented. Listed so integrations can be designed against the intended shape.

```text
POST   /api/v2/test-definitions                      # create a definition
GET    /api/v2/test-definitions/{definitionId}
POST   /api/v2/test-definitions/{definitionId}/versions
GET    /api/v2/test-runs/{runId}/report
GET    /api/v2/test-runs/{runId}/artifacts
```

Definition endpoints are the first thing Increment 1D needs: without them a definition version can
only be created through the storage layer, so the run endpoints above are not yet reachable end to
end by a consumer. Report and artifact endpoints arrive with results normalization (1D) and durable
artifact storage (1E).

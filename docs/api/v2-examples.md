# V2 API Examples

**Status:** Increment 1B — validation only. Definition creation, versioning and run endpoints arrive in 1B/1C follow-ups.
**Schema:** [`specification/schema/v1alpha1/performance-test.schema.json`](../../specification/schema/v1alpha1/performance-test.schema.json)
**Worked examples:** [`specification/examples/`](../../specification/examples/)

`/api/v1/**` remains operational and unchanged. V2 is additive and never reuses a V1 path.

---

## Available today

```text
POST /api/v2/test-definitions/validate
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

## Planned endpoints

Not yet implemented. Listed so integrations can be designed against the intended shape.

```text
POST   /api/v2/test-definitions                      # create a definition
GET    /api/v2/test-definitions/{definitionId}
POST   /api/v2/test-definitions/{definitionId}/versions
POST   /api/v2/test-runs                             # requires an idempotency key; returns 202 + Location
GET    /api/v2/test-runs/{runId}
GET    /api/v2/test-runs/{runId}/events
POST   /api/v2/test-runs/{runId}/cancel
GET    /api/v2/test-runs/{runId}/report
GET    /api/v2/test-runs/{runId}/artifacts
```

Run endpoints arrive in Increment 1C.

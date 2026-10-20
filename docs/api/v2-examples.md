# V2 API Examples

**Status:** Standalone V2 supports imports, validation, immutable definitions/versions, targets,
browser-source upload, idempotent runs, progress, reports and authorized artifact downloads.
**Schema:** [`specification/schema/v1alpha1/performance-test.schema.json`](../../specification/schema/v1alpha1/performance-test.schema.json)
**Worked examples:** [`specification/examples/`](../../specification/examples/)

`/api/v1/**` remains a local migration surface and is blocked by production profiles. V2 never reuses a V1 path.

---

## Available today

```text
POST /api/v2/imports/{sourceType}                    # curl | openapi | har | postman
POST /api/v2/datasets?team={team}&name={name}        # the data a test sends
GET  /api/v2/datasets
GET  /api/v2/datasets/{team}/{name}/{version}
POST /api/v2/test-definitions/validate
POST /api/v2/test-definitions
GET  /api/v2/test-definitions
GET  /api/v2/test-definitions/{definitionId}
POST /api/v2/test-definitions/{definitionId}/versions
GET  /api/v2/test-definitions/{definitionId}/versions
POST /api/v2/test-runs
GET  /api/v2/test-runs/{runId}
GET  /api/v2/test-runs/{runId}/events
POST /api/v2/test-runs/{runId}/cancel
GET  /api/v2/test-runs/{runId}/report
GET  /api/v2/test-runs/{runId}/artifacts
GET  /api/v2/test-runs/{runId}/artifacts/{artifactId}/content
POST /api/v2/browser-sources?team={team}&reviewed=true
POST /api/v2/targets
GET  /api/v2/platform/caller
GET  /api/v2/platform/execution
GET  /api/v2/platform/experience
```

The `/test-definitions/validate` endpoint **creates nothing and executes nothing** — suitable for
a pull-request configuration check. Creating a definition saves a version; triggering a run is a
separate, load-generating action.

Accepts `application/yaml`, `application/x-yaml`, `text/yaml`, `application/json` or
`text/plain`. YAML and JSON are two serializations of the same canonical model, so an equivalent
document in either form produces the same digest.

Production authentication uses `Authorization: Bearer <team-scoped-token>`. Every V2 resource
also enforces scope and team ownership. The local development adapter accepts its local API key;
that adapter is rejected under production profiles. For credentials held in a private file prefer
the [portable CLI](../user-guide/ai-editors.md) with `PERF_PLATFORM_TOKEN_FILE`.

---

## Importing a source

```text
POST /api/v2/imports/{sourceType}      # curl | openapi | har | postman
```

**Creates nothing.** No definition, no version, no run — so it is safe to call repeatedly while
iterating, and pasting the wrong file costs nothing. It returns draft *scenarios*, not a whole
specification: a source cannot answer what the load model, objectives or journey mix should be, and
placeholder values for them would produce a test that looks ready and means nothing.

```bash
curl -X POST http://localhost:8080/api/v2/imports/openapi \
  -H "Authorization: Bearer $PERF_PLATFORM_TOKEN" \
  -H "Content-Type: application/yaml" \
  --data-binary @openapi.yaml
```

```json
{
  "sourceType": "openapi",
  "scenarios": [
    {
      "name": "list-orders",
      "weight": 34,
      "operationId": "listOrders",
      "method": "GET",
      "path": "/v1/orders",
      "headers": {},
      "query": {"status": "open"},
      "body": null
    }
  ],
  "findings": [ /* the same Finding shape the validate endpoint returns */ ]
}
```

**The findings are as much the answer as the scenarios.** An importer strips credentials and hosts,
and it does not invent data — so what it *could not* import is what stands between the draft and a
test that measures anything.

`operationId` is preserved verbatim while `name` is its kebab-case form, because names reach
artifact object keys and Kubernetes labels and must be short lowercase identifiers. `null` for
sources with no such concept, such as cURL.

### What an importer never does

| | Why |
|---|---|
| Invent business data | A schema says a field is a string, not which string. A made-up value produces a test that exercises validation-failure paths at full load and reports them as latency. |
| Import a credential | Sources are full of them. They are stripped and their removal reported, so it is replaced with a secret reference rather than discovered as a wall of 401s at run time. |
| Execute anything | A cURL command is parsed as arguments, never handed to a shell. An external `$ref` in an OpenAPI document is not fetched. |
| Choose a target | The host is reported and dropped; where traffic goes is resolved from the team's registered targets. A server URL's *path* prefix is kept, because that is part of the request rather than the address. |

### OpenAPI findings worth knowing

| Code | Severity | Meaning |
|---|---|---|
| `import.openapi.path.parameter.unresolved` | ERROR | A `{placeholder}` was left in the path. Sent as it stands, every request 404s and the run reports the latency of an error path. |
| `import.openapi.body.missing` | WARNING | The operation takes a body and the document states no example. None was generated from the schema. |
| `import.openapi.value.missing` | WARNING | A required parameter has no example, default or enum value. |
| `import.openapi.parameter.credential` | WARNING | A parameter named as authentication, or whose example was token-shaped. Never echoes the value. |
| `import.openapi.security.declared` | WARNING | The document declares security. Declare it under `spec.authentication` as a secret reference. |
| `import.openapi.ref.external` | WARNING | A `$ref` to another file or URL was not followed. Provide a bundled document. |
| `import.openapi.values.fromDocument` | INFO | How many values came from the document. Valid shapes; not necessarily data that exists in your environment. |
| `import.openapi.weights.equal` | INFO | Every operation got an equal weight. The document says nothing about traffic mix. |

### HAR findings worth knowing

A capture is a recording of a live session, so this importer removes far more than it keeps and
every removal is reported.

| Code | Severity | Meaning |
|---|---|---|
| `import.har.reviewRequired` | WARNING | Emitted on **every** import. What survives is what a real person did, against the environment they did it in. |
| `import.har.body.credential` | WARNING | A body carrying a field named `password`, `clientSecret`, `token`… was refused whole. Names the field, never the value. |
| `import.har.body.personalData` | WARNING | A body carrying `ssn`, `cardNumber`, `dateOfBirth`… A capture records a real customer. |
| `import.har.thirdParty.dropped` | WARNING | Requests to other hosts. Every scenario goes to one registered target, so importing these would aim another service's paths at your application. |
| `import.har.cookies.stripped` | WARNING | Cookies are a live session and expire besides. |
| `import.har.header.credential` / `.session` | WARNING | `Authorization`, and CSRF/session headers — minted for one session, rejected on replay. |
| `import.har.journeys.notOrdered` | WARNING | Scenarios are named after the page they came from, but **order and correlation are not preserved**: the platform runs scenarios independently in weighted proportion. |
| `import.har.assets.dropped` | INFO | Scripts, stylesheets, images and fonts. |
| `import.har.failures.dropped` | INFO | Requests that failed when captured. Replaying a known failure measures an error path. |
| `import.har.duplicates.collapsed` | INFO | Repeats raised a scenario's weight rather than adding a scenario. |
| `import.har.weights.observed` | INFO | Weights come from observed frequency — the one thing a recording knows that an API document does not. Still one session, not production traffic. |

Also dropped without a finding, because sending them would be wrong rather than sensitive: HTTP/2
pseudo-headers, browser negotiation headers (`User-Agent`, `sec-*`, `Accept-Encoding`), host-bearing
headers (`Referer`, `Origin`), and **conditional headers** — replaying `If-None-Match` makes the
target answer `304` without doing any work, and the run would report excellent latency for a service
that did nothing.

### Postman findings worth knowing

Collection **v2.0 and v2.1** only. A v1 export is refused by name — re-export it from Postman.

| Code | Severity | Meaning |
|---|---|---|
| `import.postman.path.unresolved` | ERROR | A `{{placeholder}}` or `:name` was left in the path. Sent as it stands, every request 404s. |
| `import.postman.script.notExecuted` | WARNING | One per script, naming what it belongs to. **Scripts are never executed** (CLAUDE.md §23). |
| `import.postman.scripts.summary` | WARNING | If a script performs the sign-in, the collection imports cleanly and every request still fails at run time. |
| `import.postman.variable.credential` | WARNING | A collection variable holding a credential. An export ships its values, so these are live — rotate them if it has been shared. |
| `import.postman.body.credential` / `.personalData` | WARNING | A saved request body keeps whatever value made it work. Refused whole; names the field, never the value. |
| `import.postman.auth.declared` | WARNING | An `auth` block at collection, folder or request level. |
| `import.postman.variable.unknown` | WARNING | A variable with no stated value — usually in a Postman *environment*, which exports separately. |
| `import.postman.variable.dynamic` | WARNING | `{{$guid}}`, `{{$randomInt}}` … The platform does not evaluate them; a dataset is the equivalent. |
| `import.postman.body.unsupported` | WARNING | form-data or file. A collection stores a path on the author's machine, not the contents. |
| `import.postman.disabled.skipped` | INFO | Headers, parameters and fields switched off in Postman. |
| `import.postman.weights.equal` | INFO | A collection says nothing about traffic mix; folder order is how someone organised their work. |

Scenario names come from the author — folder path plus request name, kebab-cased — because a
collection is the one source where somebody already wrote down what each request is *for*.

A source type with no importer deployed is refused by name with `422`, listing the ones that are.

---

## Uploading a dataset

A journey can say `path: /orders/${orderId}` and take the next row on each iteration. This is where
the rows arrive.

```bash
curl -sS -X POST 'http://localhost:8080/api/v2/datasets?team=local-dev&name=proposal-clients' \
  -H 'X-API-Key: local-dev-key-not-a-secret' \
  -H 'Content-Type: text/csv' \
  --data-binary @clients.csv
```

The body is the CSV itself, not multipart: there is one file, its media type is known, and
`--data-binary @file` is how a pipeline sends it. The first line must be the header — those names
are what `${...}` binds to.

```json
{
  "name": "proposal-clients",
  "version": 1,
  "uri": "dataset://proposal-clients/1",
  "columns": ["orderId", "customerId"],
  "rowCount": 5000,
  "sizeBytes": 121400,
  "digest": "sha256:…",
  "findings": [
    {
      "code": "dataset.contents.notInspected",
      "severity": "INFO",
      "message": "Only the column names were checked. The platform does not know whether the rows hold real customer data — that remains the uploader's responsibility."
    }
  ]
}
```

`uri` is what goes into a specification, and it is the only place to learn the version that was
just allocated:

```yaml
spec:
  datasets:
    - name: client-data
      source:
        type: object-storage
        uri: dataset://proposal-clients/1
      columns: [orderId, customerId]
      partitionByShard: true
```

**No rows are ever returned.** The listing and the get endpoint carry column names, counts and a
digest — this is business data, and a response that included a sample to "confirm it worked" would
put customer records into a response body, a browser cache and every log in between.

### Versions are allocated, never chosen

Uploading `proposal-clients` again creates version 2; nothing overwrites version 1. A specification
pins an exact version, so pointing a test at newer data is an edit to the specification — visible,
reviewable, and with its own digest. `dataset://proposal-clients` without a version is refused for
the same reason a floating runner image tag is refused at startup.

### What is refused

| Code | Status | Meaning |
|---|---|---|
| `dataset.column.sensitive` | 422 | A column named as a credential or as personal data. **Nothing is stored.** |
| `dataset.header.missing` | 422 | No header row, so nothing could bind to it |
| `dataset.column.duplicate` | 422 | Two columns of one name; a `${name}` reference could not say which |
| `dataset.column.blank` | 422 | A column with no name |
| `dataset.tooLarge` | 422 | Over `platform.orchestration.datasets.max-size` (16 MiB by default) |
| `dataset.version.exists` | 409 | Two uploads raced. Retry; the next version number is free |
| `dataset.notFound` | 404 | The caller's team has no such name or version |

A finding names the offending **column**, never a value — the response is returned over HTTP and
written to a log. `dataset.column.sensitive` exists because a value check cannot help here:
nothing about a real password is token-shaped, so the field name is the only thing that can be
judged.

### At run time

Before a runner starts, the control plane resolves the reference against the team's own datasets,
compares the file's header against the columns the specification declares, slices it so no two
shards send the same row, and stores each slice as a `DATASET` artifact of the run. Any failure
fails the run with `PROVISIONING_FAILED` **before load is generated** — an unfilled `${orderId}` is
not sent as an empty value, it is sent as those characters, and a run made of 404s reports fast,
healthy-looking latency for a target that did no work.

---

## Validating a specification

```bash
curl -X POST http://localhost:8080/api/v2/test-definitions/validate \
  -H "Authorization: Bearer $PERF_PLATFORM_TOKEN" \
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
| `spec.source.git.repository.credential` | ERROR | The clone URL carries a user and token |
| `spec.source.git.repository.scheme` | ERROR | Not an https or ssh clone URL |
| `spec.source.git.repository.noneAllowed` | ERROR | This deployment allowlists no repositories at all |
| `spec.source.git.repository.notAllowed` | ERROR | Repository is not on this deployment's allowlist |
| `spec.workload.sourceRef.unresolved` | ERROR | `sourceRef` names no declared source |
| `spec.objective.scope.unresolved` | ERROR | Objective scoped to a workload that is not declared |
| `spec.governance.maxDuration.exceeded` | ERROR | Stages run longer than the declared ceiling |
| `spec.scenario.weights.sum` | WARNING | Weights do not sum to 100; treated as relative shares |
| `spec.dataset.source.uri.unversioned` | ERROR | `dataset://orders` — two runs of one version would read different rows |
| `spec.dataset.source.uri.unresolvable` | ERROR | Not a `dataset://name/version` reference, so nothing would be delivered |
| `spec.scenario.dataset.unresolved` | ERROR | `${column}` names no declared column; it would be sent as literal text |
| `spec.dataset.partition.disabled` | WARNING | A sharded run would replay the same rows on every shard |
| `spec.dataset.columns.undeclared` | WARNING | Nothing can check that the columns a journey asks for exist |
| `spec.objective.missing` | WARNING | No objectives, so the test cannot pass or fail |
| `validator.correlation.unused` | ERROR | A correlation is extracted and then sent by no later step |
| `validator.correlation.unproven` | WARNING | The extracted value matched the recorded one, so nothing was shown |

The finding never echoes a suspected credential value: findings are logged and returned over
HTTP, so repeating the value would spread the disclosure.

---

## Importing a Playwright test from a repository

A browser workload's source is either a stored artifact or git coordinates:

```yaml
  sources:
    - name: proposal-browser
      type: playwright
      git:
        repository: ssh://bitbucket/proposal-e2e
        ref: 4f2a91c7e0b3d85a16cf7420ab93de5817c04e9b
        path: tests/performance/proposal-journey.spec.ts
        project: chromium
```

Nothing is copied into the specification, so a reviewed journey stays in the repository where it is
reviewed. `specification/examples/valid/browser-git-pinned.yaml` is the full example.

### What the platform enforces, and why each rule exists

| Rule | Finding | Why |
|---|---|---|
| The repository is on this deployment's allowlist | `spec.source.git.repository.notAllowed` | A git source is the one field naming a host the platform will contact and then execute what comes back. Everywhere else a test says *what* and the platform decides *where* — this keeps that property instead of making the repository an exception to it |
| A deployment that allowlists nothing permits nothing | `spec.source.git.repository.noneAllowed` | An empty allowlist is "not decided", not "no restriction". The remediation names the property, so the refusal reads as configuration rather than as a bad test |
| https or ssh only | `spec.source.git.repository.scheme` | `file://` and plaintext http make the imported material unverifiable |
| No credentials in the URL | `spec.source.git.repository.credential` | `https://x-access-token:ghp_live@host/org/repo` is a secret written into a stored, digested, findings-echoing document. A bare `git@host` over ssh is fine — that is a username, not a secret |
| `ref` is an immutable commit SHA | `spec.source.git.ref.mutable` | A branch moves, so the code that ran could not be recovered from the stored version |
| `path` stays inside the checkout | `spec.source.git.path.traversal` | A `..` segment would read the runner's filesystem outside the repository |

Configure the allowlist per deployment:

```yaml
platform:
  orchestration:
    playwright:
      allowed-repositories:
        - ssh://bitbucket/proposal-e2e
        - https://github.com/ameriprise/perf-journeys
```

Spelling is normalized before comparison, so `.git`, a trailing slash and host casing all match the
same repository.

### At run time

The repository, ref and path are compiled into the plan and therefore covered by the plan digest —
the commit a run executed is recoverable from the stored definition version. The runner clones at
that SHA, **verifies the resolved `HEAD` equals the SHA it asked for**, and refuses otherwise:
`git fetch <sha>` asks a remote for a commit and what arrives is whatever the remote chose to send,
so without that check pinning is a comment.

**Not yet implemented: credentials.** Only repositories the runner can read anonymously, or through
a credential already present in its cell, will fetch. A private repository needs a git credential
resolved through `SecretResolver` and mounted into the Job, which is a separate slice.

---

## Proving a recording's correlations

`POST /api/v2/test-definitions/validator` runs one live iteration and returns request, response,
extraction and assertion evidence. It cannot judge a **correlation**, because a specification no
longer holds the literal a correlation replaced — accepting the rule is what removed it.

`POST /api/v2/recordings/{sessionId}/validator` runs the same iteration against the recording the
draft came from, so the literals are available and each rule gets a verdict.

```bash
curl -sS -X POST "$PERF_PLATFORM_URL/api/v2/recordings/$SESSION_ID/validator" \
  -H "Authorization: Bearer $PERF_PLATFORM_TOKEN" \
  -H "Content-Type: application/yaml" \
  --data-binary @draft.yaml
```

```json
{
  "valid": false,
  "target": "http://demo-service.perf.internal",
  "findings": [
    {
      "code": "validator.correlation.unused",
      "severity": "ERROR",
      "path": "/spec/workloads/scenarios/checkout/steps/login/extract/session",
      "message": "The correlation 'session' is extracted but no later step sends it.",
      "remediation": "The journey is still sending the value that was captured, which will be stale under load. Re-accept the suggestion, or remove the rule if the value is genuinely fixed."
    }
  ],
  "journeys": [
    {
      "name": "checkout",
      "passed": true,
      "steps": ["…"],
      "correlations": [
        {
          "variable": "session",
          "fromStep": "login",
          "usedBySteps": [],
          "verdict": "unused",
          "livePreview": "[redacted]",
          "message": "Extracted, then sent by nothing. The steps that should use 'session' are still carrying the captured value."
        }
      ]
    }
  ]
}
```

Note `"passed": true` on the journey alongside a failed run. That combination is the point: every
step succeeded, because the literal the rule was meant to replace was still valid. The rule is
nonetheless dead, and only the comparison against the recording could say so.

### The verdicts

| Verdict | Meaning |
|---|---|
| `proven` | The live value **differed** from the recorded one and a later step sent it |
| `unproven` | The live value **equalled** the recorded one, so a journey ignoring the rule would look identical |
| `used` | Extracted and sent onwards; no recording was supplied, so nothing stronger is claimed |
| `unused` | Extracted and sent by nothing. An `ERROR` finding, and the response is not `valid` |
| `not-extracted` | Nothing matched. The step already failed |

Comparison happens on the raw values inside the control plane; only the redacted preview is
returned. A session token is exactly the value most worth proving, so redaction must not weaken the
verdict.

Like the ordinary Validator, this creates no definition, run or artifact and consumes no admission
slot. A document that would not validate is refused **before** the recording is read: fetching
somebody's captured session on behalf of a request that was never going to execute would make a
junk body a way to probe which session ids exist.

`correlations` is present on the ordinary Validator's response too — it simply never reports
`proven` or `unproven` there, because there is nothing to compare against.

---

## Using it as a CI status check

```bash
response=$(curl -sS -X POST "$PERF_PLATFORM_URL/api/v2/test-definitions/validate" \
  -H "Authorization: Bearer $PERF_PLATFORM_TOKEN" \
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
curl -X POST https://performance.example.com/api/v2/test-runs \
  -H "Authorization: Bearer $PERF_PLATFORM_TOKEN" \
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

### Broker-backed run://session

A definition can declare `secretRef: run://session` when its workload needs broker-acquired
application sessions. For the production broker path, the trigger request still references only the
definition version:

```json
{
  "definitionVersionId": "3f2b1c8e-5d4a-4a1b-9c7e-2f6d8b0a1e33"
}
```

Do not put a bearer token, cookie or refresh token in the request. The control plane calls the
configured session broker, partitions the leases per shard and mounts them into the runner as
`/workspace/sessions.csv`. See [broker-backed run://session](../operations/run-session-acquisition.md)
and the [session broker contract](../operations/session-broker-contract.md).

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
curl https://performance.example.com/api/v2/test-runs/$RUN_ID \
  -H "Authorization: Bearer $PERF_PLATFORM_TOKEN"
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
curl "https://performance.example.com/api/v2/test-runs/$RUN_ID/events?afterSequence=12" \
  -H "Authorization: Bearer $PERF_PLATFORM_TOKEN"
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

## Watching a run while it executes

```bash
curl "https://performance.example.com/api/v2/test-runs/$RUN_ID/progress?limit=120" \
  -H "Authorization: Bearer $PERF_PLATFORM_TOKEN"
```

```json
{
  "runId": "9c1f0b44-…",
  "engine": "jmeter",
  "capturedAt": "2026-08-04T10:01:05Z",
  "observedAt": "2026-08-04T10:01:17Z",
  "ageSeconds": 12,
  "shardsExpected": 2,
  "shardsReporting": 2,
  "elapsedSeconds": 65,
  "activeUsers": 50,
  "overall": {
    "sampleCount": 102480, "errorCount": 35867, "unparsedCount": 1,
    "errorRateMilliPercent": 34999,
    "p50Millis": 2, "p95Millis": 3, "p99Millis": 12,
    "throughputPerSecondMilli": 3618000
  },
  "byOperation": [{ "name": "checkout/pay", "…": "…" }],
  "shards": [{ "shardIndex": 0, "ageSeconds": 12, "sampleCount": 51240, "activeUsers": 25 }],
  "series": [{ "capturedAt": "…", "throughputPerSecondMilli": 2719600, "p95Millis": 3 }],
  "provisionalObjectives": [{ "metric": "http.error.rate", "verdict": "BREACH", "…": "…" }],
  "observations": [{
    "code": "ERROR_RATE_CRITICAL",
    "severity": "CRITICAL",
    "message": "A quarter or more of the requests are failing. A failed request is usually answered faster than a real one, so the response times below are flattering rather than reassuring.",
    "evidence": "34.999% of 102481 requests have failed, against a 25% rule."
  }]
}
```

`204` when no generator has published anything yet. Not a body of zeros: "not measured yet" and
"measured nothing" are different facts, and zeros let a client render the second while the first is
true.

### Everything here is provisional

Percentiles are **upper bounds** — the top of the latency bucket the sample fell in, never
interpolated — and every count comes from partial runner output. `provisionalObjectives` is judged by
the same evaluator the final verdict uses, so a breach here means the same thing it will mean later,
but it is **not the run's verdict**: that is computed from the complete raw results once every shard
has finished, and arrives on `/report`.

### Read `shards` as well as `overall`

Merged totals cannot show a generator that stopped — the survivors keep adding to them, so a run that
lost half its load generation reads as a run that is merely slower. `shards[].ageSeconds` is what
exposes it, and the `RUNNER_SILENT` observation is what says so in words.

### Freshness

A number arrives through two cadences: generators publish every five seconds, and the control plane
merges every reconciliation pass. A healthy view is ~15 seconds behind by construction; compare
`observedAt` against `capturedAt` rather than assuming currency. Full detail in
[live-monitoring.md](../operations/live-monitoring.md).

### Polling, for now

Server-Sent Events suits this better and is a later increment. The merged snapshots are durable
either way, so changing the transport changes nothing about what a client can ask for or resume from.

---

## Cancelling a run

```bash
curl -X POST "https://performance.example.com/api/v2/test-runs/$RUN_ID/cancel?reason=Wrong+environment" \
  -H "Authorization: Bearer $PERF_PLATFORM_TOKEN"
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

## Reviewed browser source and private evidence

Upload a reviewed self-contained script with
`POST /api/v2/browser-sources?team=my-team&reviewed=true`, `Content-Type: text/plain`, and the UTF-8
source body. The response contains an immutable team-owned `uri`, `digest` and `sizeBytes`; use
those in the canonical browser specification. Uploading performs no execution. See the
[browser guide](../user-guide/browser-testing.md) for the full consumer flow and Git-pinned imports.

Read `/api/v2/test-runs/{runId}/report`, then download an owned artifact using
`GET /api/v2/test-runs/{runId}/artifacts/{artifactId}/content`. The server authorizes the caller and
verifies its recorded SHA-256 before serving it as an attachment. Store stable references, not
temporary/public links. The plan, JTL/results, logs and manifest are evidence; the report is the
normalized summary and objective verdicts.

## Rate-based report profiles

Report workload profiles include additive fields `modelType` (`STAGES` or `ARRIVAL_RATE`),
`maxConcurrency`, `peakArrivalRate` and stage-level `targetRate`. For an arrival-rate test, the
legacy `peakUsers` field is the concurrency budget, not an observed fixed user count. Use the
explicit rate and ceiling fields for display. Browser profiles use `type=BROWSER` and
`iterationsPerUser`; browser concurrency is separate from protocol users.

For example, [the 100-RPS specification](../../specification/examples/valid/api-100-rps.yaml) is
reported with `modelType=ARRIVAL_RATE`, `maxConcurrency=20`, `peakArrivalRate=100`, and stage
rates `100, 100, 0`. Achieved throughput is measured separately. A run's full average includes ramps.

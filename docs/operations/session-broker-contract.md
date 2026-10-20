# Session broker contract

This is the HTTP contract implemented by the `external-session-broker` `SessionAcquisitionProvider`. It is intentionally small: the broker owns real login and refresh behavior, while the platform owns admission, partitioning, delivery and lifecycle cleanup.

The default paths are:

```text
POST /v1/sessions:acquire
POST /v1/sessions:refresh
```

They can be overridden with:

```yaml
sessionAcquisition:
  loginPath: /custom/acquire
  refreshPath: /custom/refresh
```

## Authentication

Every request includes:

```http
Authorization: Bearer <resolved broker client credential>
Content-Type: application/json
Accept: application/json
X-Performance-Test-Run-Id: <run uuid>
```

The bearer value comes from `sessionAcquisition.clientCredentialSecretRef`, resolved through `SecretResolver`. It must not be supplied by a test specification, trigger request or Helm values file.

## Acquire request

```http
POST /v1/sessions:acquire
```

```json
{
  "operation": "acquire",
  "runId": "9c1f0b44-2a77-4a1e-8d3b-6f0e2c5a7b91",
  "authenticationName": "broker-session",
  "requestedAt": "2026-08-03T10:00:00Z",
  "minimumValidUntil": "2026-08-03T10:30:00Z",
  "sessionIndex": 2
}
```

`sessionIndex` is zero-based and identifies the lease being requested within this run. The broker may use it to select a distinct test account, browser profile or application tenant, but it must not derive authorization from it.

## Refresh request

```http
POST /v1/sessions:refresh
```

```json
{
  "operation": "refresh",
  "runId": "9c1f0b44-2a77-4a1e-8d3b-6f0e2c5a7b91",
  "authenticationName": "broker-session",
  "requestedAt": "2026-08-03T10:12:00Z",
  "minimumValidUntil": "2026-08-03T10:30:00Z",
  "currentSessionId": "broker-session-0002"
}
```

Refresh is used by providers that already have a lease but need it extended to satisfy the run deadline. A broker may return the same `sessionId` with a new value or a replacement `sessionId`.

## Successful response

```json
{
  "sessionId": "broker-session-0002",
  "sessionValue": "Bearer eyJhbGciOi...",
  "expiresAt": "2026-08-03T11:00:00Z"
}
```

`sessionValue` is the exact value the runner injects into the authentication header selected by `injectAs`. For bearer authentication, include the `Bearer ` prefix.

The provider rejects a response when:

| Condition | Outcome |
|---|---|
| HTTP status is not 2xx | Run provisioning fails with a sanitized provider error |
| `sessionId` is missing or blank | Run provisioning fails |
| `sessionValue` is missing or blank | Run provisioning fails |
| `expiresAt` is missing, invalid or before `minimumValidUntil` | Run provisioning fails |

The broker response body is never copied into a run event or user-facing error. Provider logs may include status, run id and authentication name, but must never include `sessionValue`, cookies, tokens or broker credential material.

## Broker responsibilities

- Acquire real application sessions from approved test accounts or service identities.
- Keep leases valid until at least `minimumValidUntil`.
- Return values that are already scoped to the application environment being tested.
- Prevent two simultaneously leased sessions from colliding on mutable account state unless the test data is designed for that.
- Rate-limit and audit by broker client identity and `runId`.
- Expose no generic credential minting endpoint. This broker exists only for performance-test session acquisition.

## Platform responsibilities

- Validate that specifications contain `run://session`, not an inline session.
- Resolve only the broker client credential reference through `SecretResolver`.
- Partition acquired leases per shard.
- Deliver leases to runner Jobs as mounted Secret files, not environment variables.
- Delete run-scoped session and runtime credential Secrets on terminal observation, cancellation and
  explicit TTL cleanup.
- Reject results whose run id, shard id or plan digest does not match the durable run.

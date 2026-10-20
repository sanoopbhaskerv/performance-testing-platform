# ADR 004 — Object storage is the durable artifact store

**Status:** Accepted
**Date:** 2026-07-29
**Relates to:** [Target architecture §9, §12](../architecture/performance-testing-platform-target-architecture.md)

## Context

V1 writes JMX, JTL results, JMeter logs, heap dumps and HTML reports under a pod-local working
directory and persists the absolute filesystem path in `test_runs.report_path`. Two failures
follow:

- A second replica cannot serve a report written by the first. The stored path is meaningless
  outside the pod that produced it, so the control plane cannot be scaled.
- The artifacts vanish when the pod restarts. Evidence for a completed test is lost.

Separately, the whole working directory is exposed as static assets under `/runs/**` with no
authentication, and `/api/v1/tests/*/report` is explicitly `permitAll()`. Raw results, logs and
heap-dump output — which can contain production-shaped data and secrets — are readable by anyone
who can reach the service, across teams.

Once runners are ephemeral Jobs (ADR 001), pod-local storage stops being merely inadequate and
becomes impossible: the pod that holds the results is deleted.

## Decision

Encrypted, S3-compatible object storage is the durable artifact store. Pod-local files are
temporary working files and are never the system of record.

- Two implementations behind one `ArtifactStore` port: `FileSystemArtifactStore` for local
  development, an S3-compatible implementation for production.
- Immutable object keys prefixed by team, run and shard:
  `teams/{teamId}/runs/{runId}/{plan|shards/{shardId}|analysis|report}/`.
- The database stores **stable object references**, never pre-signed URLs. A signed URL is an
  ephemeral capability; storing one turns a credential into a database column.
- Upload and download are both authorized. Downloads are issued as time-limited grants after a
  team-ownership check. There are no permanent public URLs.
- Digest, content type and size are verified and recorded on upload.
- Retention and expiration metadata are stored per artifact.
- Runners receive narrowly scoped credentials or time-limited upload grants — never raw
  long-lived storage credentials in Job configuration.

## Alternatives considered

| Alternative | Why it was rejected |
|---|---|
| Keep pod-local files, add a shared `ReadWriteMany` volume | Available in some clusters but not all, becomes a scaling bottleneck and a shared failure domain, and does not solve cross-team authorization |
| Store artifacts as blobs in PostgreSQL | JTL files from a 1,000-user run are large; the database becomes a file server, backups balloon and streaming is awkward |
| Keep serving `/runs/**` as static assets, add authentication | Still requires shared storage for multi-replica correctness, and static resource handlers are the wrong place to enforce per-team resource authorization |
| Store pre-signed URLs in the database | Bakes an expiring credential into durable state; the reference breaks on expiry and leaks access if the row is read |

## Consequences

**Easier.** Any replica can serve any artifact. Artifacts outlive the pod and the run. Retention
becomes a policy applied to storage rather than a cleanup script. Runner Jobs upload directly
without routing large files through the control plane. Team-prefixed keys make authorization
checks and lifecycle rules straightforward.

**Harder.** A storage dependency must be provisioned, encrypted and credentialed for every
deployment, including standalone team installs. Local development needs a filesystem
implementation that behaves closely enough to be useful. Download authorization is now an
explicit code path that must be tested, not an absent one.

**Obligations.** Every download is authorized against team ownership on every request — a grant
is not a substitute for the check that precedes it. Heap dumps in particular carry a retention
and confidentiality obligation and must not be stored under the same default retention as an
HTML report.

**Blocked on.** The object-storage service, bucket/account and retention period are unresolved
enterprise decisions (architecture §18.4). Increment 1E must pause for them before wiring a
production bucket.

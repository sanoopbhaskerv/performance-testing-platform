# ADR 002 — PostgreSQL durable orchestration, not a workflow platform

**Status:** Accepted
**Date:** 2026-07-29
**Relates to:** [Target architecture §5.5, §5.6, §9, §13](../architecture/performance-testing-platform-target-architecture.md)

## Context

Once execution leaves the control-plane process (ADR 001), run state must survive a control-plane
restart. V1 has no durable orchestration: `TestRun` carries four states
(`PENDING`, `RUNNING`, `COMPLETED`, `FAILED`), transitions are unvalidated field assignments, and
the only recovery signal is a `findAllRunning()` query with nothing that acts on it.

The scale is modest and known: 10 concurrent runs at peak, each lasting minutes to hours. Run
throughput is single-digit per hour, not thousands per second. The hard requirements are
durability, correct concurrency and auditability — not high message throughput.

The platform is also deployed by application teams into their own infrastructure, so every
operational component added here is a component every team must run and patch.

## Decision

Use PostgreSQL as the durable source of truth for orchestration, with a reconciler that compares
desired state, runner state and stored state.

- An explicit state machine: `VALIDATING`, `REJECTED`, `AWAITING_APPROVAL`, `QUEUED`,
  `PROVISIONING`, `RUNNING`, `ANALYZING`, `COMPLETED`, `FAILED`, `CANCELLED`, `EXPIRED`.
- Transition validation is centralized. Invalid transitions are rejected, not silently applied.
- Every transition appends an immutable `run_events` row with actor, reason and timestamps.
- Optimistic locking (a version column) guards concurrent state changes.
- Queue claiming uses PostgreSQL row-level claim semantics with a durable owner and an expiring
  lease, so multiple replicas cannot claim the same run and a dead scheduler's claim recovers.
- Idempotency is enforced by a unique constraint on `(team_id, idempotency_key)`.
- Schema is managed by Flyway. `ddl-auto: update` is disabled outside local development.

Do **not** introduce Kafka, Temporal or another workflow platform in this architecture.

## Alternatives considered

| Alternative | Why it was rejected |
|---|---|
| Temporal or a similar workflow engine | Correct fit for complex long-running workflows, but adds a clustered service that every standalone team deployment must operate, upgrade and secure. Disproportionate to ~10 concurrent runs |
| Kafka for run events and coordination | Solves a throughput problem this platform does not have, while adding a broker to every deployment. The event log requirement is auditability, which a Postgres append-only table satisfies better |
| Spring `@Scheduled` polling over the existing schema, no state machine | Keeps V1's core defect: state is implicit, transitions are unvalidated, and concurrent replicas race on the same rows |
| Kubernetes CRDs plus a controller as the state store | Makes etcd the system of record for business data, ties the domain to Kubernetes, and blocks the non-Kubernetes runner providers the engine SPI is meant to allow |
| Redis-backed queue | Another operational component, and it is not a durable system of record for approvals, versions and audit |

## Consequences

**Easier.** One datastore for definitions, runs, approvals and audit. Transactional consistency
between a state transition and its event record. A restart resumes reconciliation from committed
state. Straightforward for a team to operate — most already run PostgreSQL. Deterministic,
auditable queue ordering.

**Harder.** Queue claiming must be written and tested carefully; concurrent-claim behavior needs
integration tests against real PostgreSQL via Testcontainers, not H2. Reconciliation must be
idempotent throughout, since it will re-observe the same state repeatedly. Polling adds latency
between a slot freeing and the next run starting — acceptable for tests measured in minutes.

**Obligations.** Every reconciliation operation must be safe to run twice. H2 stays only as a
local-development convenience; PostgreSQL/Testcontainers is the authoritative integration-test
database, because H2 will not reproduce the locking semantics the queue depends on.

**Revisit if.** Sustained concurrency substantially exceeds the 10-run peak, or multi-region
federation introduces coordination this model cannot express.

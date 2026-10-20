# Architecture Decision Records

Each ADR records one decision: why it was taken, what was rejected, and what it costs.

ADRs **link to** [the target architecture](../architecture/performance-testing-platform-target-architecture.md)
rather than restating it. If an ADR and the architecture document disagree, the architecture
document wins unless the ADR explicitly supersedes it and says so.

## Index

| ADR | Decision | Status |
|---|---|---|
| [001](001-control-execution-separation.md) | Separate control, execution and analysis planes | Accepted |
| [002](002-postgresql-durable-orchestration.md) | PostgreSQL state machine and reconciler, not a workflow platform | Accepted |
| [003](003-engine-spi.md) | Engines integrate through an SPI, not orchestrator branching | Accepted |
| [004](004-object-artifact-storage.md) | Object storage is the durable artifact store | Accepted |
| [005](005-deployment-profiles.md) | One product, two deployment profiles | Accepted |

## Status values

- **Proposed** — under discussion, not yet safe to build against.
- **Accepted** — binding. Code that contradicts it is a defect.
- **Superseded by NNN** — kept for history; do not follow.
- **Deprecated** — no longer applies and has no successor.

## Writing a new ADR

Copy [`000-template.md`](000-template.md). Number sequentially. An ADR is immutable once
Accepted: to change a decision, write a new ADR that supersedes it and update the old one's
status line. Do not edit the decision text of an Accepted ADR in place.

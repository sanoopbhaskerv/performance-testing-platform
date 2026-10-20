# ADR 005 — One product, two deployment profiles

**Status:** Accepted
**Date:** 2026-07-29
**Relates to:** [Target architecture §4, §15](../architecture/performance-testing-platform-target-architecture.md)

## Context

Two deployment shapes are both legitimate and neither can be dismissed:

- **Standalone** — a team installs the whole platform in its own infrastructure. Needs no
  cross-network approval and no central hosting, which makes it the realistic first release.
- **Shared control plane with team-local runner cells** — central governance, identity, audit,
  policy and test history, with execution running inside each team's network next to its targets.
  This is the enterprise target.

Building these as two products would fork the domain model, the API and the UI, and the fork
would be permanent. Committing solely to standalone would make the later move to central
governance a rewrite, because federation constraints only surface once execution is remote.

Whether runner cells may call outbound to a central control plane is still an open enterprise
question (architecture §18.6), so the decision must not depend on its answer.

## Decision

One codebase and one Helm distribution, with two profiles:

- `profile=standalone` — UI, API, scheduler, PostgreSQL connection and runners in one namespace.
- `profile=shared-runner` — execution agent and runners only, driven by a central control plane.

Deploy at most three things: one control-plane application, an optional lightweight runner agent
for remote cells, and ephemeral runner Jobs. A microservice decomposition is explicitly not
substituted for the modular monolith.

The initial implementation may deliver standalone first, but **domain and execution contracts
must not assume the runner is local**. Specifically:

- The runner contract is already remote-shaped: an immutable plan reference, a plan digest, a
  shard index and count, and artifact upload configuration. Nothing depends on the control plane
  and the runner sharing a filesystem or a process.
- Communication is designed outbound from the cell: the agent authenticates with a scoped service
  identity, leases work for its cell, submits local Jobs and reports status and heartbeats
  outbound. Artifacts upload directly to approved object storage.
- The control plane does **not** require inbound access to a team's Kubernetes cluster. That is a
  network approval most enterprises will refuse, and designing around it later is expensive.

Inbound control-plane access to team clusters is not implemented in the first standalone
increment, and the outbound protocol seam is preserved rather than built.

## Alternatives considered

| Alternative | Why it was rejected |
|---|---|
| Standalone only | Every team patches and upgrades its own instance, with no central policy, audit or cross-team capacity view. Moving to central governance later would be a rewrite |
| Shared control plane only | Requires cross-network connectivity approval before any team can use the platform. Blocks adoption on an approval that may not arrive |
| Two separate products | Permanent fork of domain, API and UI. Twice the maintenance and inevitable behavioral drift between them |
| Control plane reaches into team clusters | Requires inbound access to team Kubernetes APIs — an enterprise network approval that is usually refused, and a large blast radius if the control plane is compromised |

## Consequences

**Easier.** A team can adopt the platform without waiting on network approvals. Central
governance becomes a configuration change plus a runner agent, not a migration. One chart, one
image set and one upgrade pipeline serve both shapes.

**Harder.** Every capability must work under both profiles, which constrains design: no
capability may assume the runner is local or that the control plane can reach the cluster. The
runner agent is an additional component to build and secure once shared-runner mode is enabled.
Testing must cover both profiles.

**Obligations.** A central Helm chart, an approved container registry and an automated upgrade
pipeline are mandatory, not optional — standalone deployments multiply the cost of manual
upgrades across every team.

**Deferred.** The runner-agent lease protocol is designed for but not implemented in the
foundation phase. Which profile ships first is an open enterprise decision (architecture §18.2);
the recommended MVP default is standalone first while preserving the shared-runner protocol.

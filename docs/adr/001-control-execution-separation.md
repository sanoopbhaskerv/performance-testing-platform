# ADR 001 — Separate control, execution and analysis planes

**Status:** Accepted
**Date:** 2026-07-29
**Relates to:** [Target architecture §1, §5, §5.7](../architecture/performance-testing-platform-target-architecture.md)

## Context

V1 starts JMeter with `ProcessBuilder` inside the Spring Boot control plane
(`JmeterTestOrchestrator.run`) and holds the run lifecycle in a single `@Async` method
(`TestRunService.executeAsync`). Three consequences follow directly:

- Load generation competes with the API for CPU, memory and GC in the same JVM. A large test
  degrades the API that is supposed to report on it.
- The control plane cannot scale horizontally. Adding a replica multiplies load generators
  rather than adding API capacity, and `replicaCount: 1` in the Helm chart reflects that.
- A pod restart loses the run. Nothing outside that thread knows the test is in flight, so the
  run is stranded in `RUNNING` forever with no reconciliation path.

The platform must support 3–4 concurrent team tests normally and 10 at peak, at up to 1,000
virtual users each. That is not reachable from inside the API process.

## Decision

Separate three responsibilities and do not let them share a process:

1. **Control plane** — accepts and validates specifications, enforces policy, schedules runs and
   owns run state. Deployed as at least two stateless replicas. **Generates no material load.**
2. **Execution plane** — runs workloads in isolated, ephemeral Kubernetes Jobs, one or more
   shards per run, with explicit resource requests and limits.
3. **Analysis plane** — normalizes runner output, evaluates objectives and produces reports from
   stored artifacts, independently of the process that executed the test.

`ProcessBuilder` execution survives only as `LocalJmeterExecutionProvider` behind an explicit
local/development profile. The production profile must refuse to run a load generator inside the
control-plane container.

## Alternatives considered

| Alternative | Why it was rejected |
|---|---|
| Keep in-process execution, scale the control plane vertically | Does not fix lifecycle loss on restart, and one team's test still destabilizes every other team's API access |
| JMeter distributed mode with a remote controller over RMI | Couples the run to a long-lived controller process, reintroduces the single point of failure, and RMI is awkward to secure and operate across team network boundaries |
| Separate microservices per plane from the start | Buys deployment isolation at the cost of an unnecessary service estate. A modular monolith with ephemeral Jobs gets the isolation that matters — load generation — without it. See ADR 005 |
| A dedicated long-running runner pool | Idle cost between runs, and cross-run contamination of JVM state. Ephemeral Jobs give clean isolation per run |

## Consequences

**Easier.** The control plane becomes stateless and horizontally scalable. A restart no longer
loses a run. Per-run resource limits become enforceable. Karpenter can provision nodes against
real Job resource requests. One team can no longer exhaust the platform for everyone.

**Harder.** Execution becomes asynchronous and distributed, so a durable state machine and
reconciler are now mandatory rather than optional (ADR 002). Artifacts must leave the pod
(ADR 004). Debugging spans process boundaries. The runner image becomes a versioned, scanned
release artifact with its own lifecycle.

**Obligations.** Kubernetes Job retry semantics must be deliberately restricted: `restartPolicy:
Never` and normally `backoffLimit: 0`. An automatic retry of a load test duplicates real traffic
against a real target and invalidates the results. This is a correctness and safety constraint,
not a tuning preference.

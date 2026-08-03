# Run lifecycle

How a run moves from a request to a result, what moves it, and what to look at when it does not.

Delivered by Increment 1C. Relates to [ADR 002](../adr/002-postgresql-durable-orchestration.md) and
[target architecture §5.5, §5.6](../architecture/performance-testing-platform-target-architecture.md).

---

## 1. States

| State | Meaning | Holds a capacity slot |
|---|---|---|
| `VALIDATING` | The request is durable; the platform is deciding whether it is acceptable | No |
| `REJECTED` | Refused. No load was generated. Terminal | No |
| `AWAITING_APPROVAL` | Governance requires a human decision before any load is generated | No |
| `QUEUED` | Accepted, waiting for a free slot | No |
| `PROVISIONING` | A runner is being created; handles are being persisted | **Yes** |
| `RUNNING` | The runner is generating load | **Yes** |
| `ANALYZING` | Load has stopped; artifacts are being reduced | **Yes** |
| `COMPLETED` | Finished with results. Terminal | No |
| `FAILED` | Finished without usable results. Terminal | No |
| `CANCELLED` | Stopped on request. Terminal | No |
| `EXPIRED` | Aged out before it ever ran. Terminal | No |

`ANALYZING` holds a slot on purpose. The run still owns its runner artifacts and shard records, and
releasing the slot early would let an eleventh material run start while the tenth is still being
reduced.

**`DRAFT` is not a run state.** A draft is a *specification* that has not been versioned. It has no
run, no plan and no capacity claim. Admitting a `DRAFT` run would create a row that occupies a queue
position while meaning "nothing was requested".

### Permitted transitions

```
VALIDATING        → REJECTED | AWAITING_APPROVAL | QUEUED | CANCELLED
AWAITING_APPROVAL → QUEUED | REJECTED | CANCELLED | EXPIRED
QUEUED            → PROVISIONING | CANCELLED | EXPIRED
PROVISIONING      → RUNNING | FAILED | CANCELLED
RUNNING           → ANALYZING | FAILED | CANCELLED
ANALYZING         → COMPLETED | FAILED
```

Terminal states do not transition. Recovering one requires an explicit administrative workflow, so
that a reconciliation bug cannot quietly resurrect a finished run and replay its load.

There is deliberately **no `QUEUED → RUNNING` shortcut**: every run passes through `PROVISIONING` so
the external runner handle is persisted *before* the platform believes load is being generated.
Without that ordering, a control-plane crash between the two leaves a load generator running that
nothing can find or cancel.

`AWAITING_APPROVAL` is in the matrix but nothing produces it yet — approvals arrive with governance
in Increment 4. The transitions are settled now so that adding them changes no other component.

Enforced by `RunStateMachine`. An invalid transition raises rather than being applied: it means two
components disagree about where a run is, and the states this platform guards decide whether load is
generated.

---

## 2. What moves a run

Nothing advances because an in-process callback fired. Every transition is the result of reading
durable state and reconciling it against what the runner provider reports. That is what makes a
control-plane restart a non-event.

`RunReconciler` runs one pass every `platform.orchestration.reconciliation-interval` (default 10s),
on **every replica**. Claiming arbitrates between them; a single scheduler would be a single point of
failure with no recovery.

A pass does three things, in this order:

1. **Recover** runs stranded mid-trigger (`VALIDATING` older than `validation-grace`).
2. **Observe** active runs — this is what finishes them and frees their slots.
3. **Admit** queued runs into the capacity that observing just released.

Observing before admitting means a pass that completes a run can start the next one immediately,
rather than leaving a free slot idle until the following cycle.

---

## 3. Admission and queueing

A trigger always queues a valid run. Capacity is decided later, at claim time, because capacity at
the moment of submission says nothing about capacity twenty minutes later when a slot frees.

| Limit | Default | Property |
|---|---|---|
| Global concurrent runs | 10 | `platform.orchestration.capacity.global-max-runs` |
| Per team | 1 | `platform.orchestration.capacity.default-team-max-runs` |
| Per team, policy-approved | 2 | `platform.orchestration.capacity.team-overrides.<team>` |

Ten is a worst-case ceiling, not a target; normal operation is three or four. The per-team limit is
what does the interesting work: without it, one team submitting ten runs occupies every global slot
and nine other teams wait behind it — the platform technically within its limits and useless to
everyone else.

Browser sessions are **not** counted here. They are governed separately and must never be equated
with protocol virtual users; the Playwright engine brings its own, much smaller budget in Increment 3.

### Ordering and fairness

Queued runs are claimed oldest first, with the run identifier as a tiebreak, so "why did their run
start before ours" has an answer that does not depend on physical row order.

The admission decision and the claim happen in **one transaction**, against **one capacity snapshot**,
serialized across replicas by a lock on a single row in `orchestration_locks`. Without that, four
reconcilers each observing three free slots would each admit three and the platform would run twelve.

A deferred run keeps its queue position and is reconsidered next pass. Its first deferral is recorded
as an `ADMISSION_DEFERRED` event — once, not once per pass, or a run waiting forty minutes would bury
the events that matter. The *current* reason is available live from the run's status, which is the
only place it can stay accurate as capacity shifts.

---

## 4. Leases and recovery

A reconciler that claims a run holds a lease on it for `lease-duration` (default 2 minutes). The
lease must comfortably exceed the reconciliation interval, or a reconciler loses runs it is actively
working on to a peer mid-provision.

Leases expire. If they did not, a reconciler that died mid-provision would own the run forever and
nothing would ever finish it. Any reconciler may take over a run whose lease has lapsed — which is
what makes recovery after a control-plane restart automatic rather than an operator task. The
takeover is recorded as a `CLAIM_EXPIRED` event: it is the visible trace of a control plane that
failed and recovered itself.

**Provisioning is never repeated.** It is the one step that creates load, so a shard that already
carries an external handle is never provisioned again, however many times a pass re-runs.

**A failed run is never restarted.** A retried performance test is duplicate load against a real
system and two overlapping sets of results. Failures are recorded and surfaced; replaying one is an
explicit human decision.

---

## 5. The event log

Every run has an append-only history in `run_events`. It answers "what happened to this run" months
later, which V1 could not: V1 kept a current status and nothing else.

Events are never updated or deleted — PostgreSQL rejects both with a trigger, which is the half of
the guarantee that survives a direct SQL write. A correction is a new event.

Read it at `GET /api/v2/test-runs/{runId}/events`. Pass `afterSequence` to resume from the last event
seen rather than re-reading a long history.

| Event | Recorded when |
|---|---|
| `REQUESTED` | The run was created. Always first |
| `IDEMPOTENT_REPLAY` | A duplicate trigger returned this run |
| `STATE_CHANGED` | Any state transition, with both states |
| `ADMISSION_DEFERRED` | The run was first held back for capacity |
| `CLAIMED` | A reconciler took or renewed its lease |
| `CLAIM_EXPIRED` | A lease lapsed and another reconciler took over |
| `SHARD_PROVISIONED` | A runner was created and its handle persisted |
| `SHARD_FINISHED` | A shard reported a terminal outcome |
| `HEARTBEAT_STALE` | A shard went silent past the tolerance |
| `CANCELLATION_REQUESTED` | Someone asked for the run to stop |
| `EXECUTION_TERMINATED` | The provider confirmed the workload stopped |

Event detail carries short operational facts only — a shard index, a lease owner, an admission
reason. It must never carry a request body, an authorization header or a raw exception: run events
are read by every member of a team and exported to operational tooling.

---

## 6. Idempotency

`POST /api/v2/test-runs` requires an `Idempotency-Key` header. Repeating a request with the same key
returns the original run rather than starting a second one — which matters more here than in most
APIs, because the duplicate would not be a wasted row but a second load test against a real system,
doubling the traffic and invalidating both sets of results.

Enforced by a unique constraint on `(team_id, idempotency_key)`, not by a read-then-write check: two
replicas can pass the same check simultaneously, and only the constraint is authoritative.

Keys are scoped per team, so two teams independently using `nightly-baseline` do not collide and
neither can reach the other's run by guessing a key.

Reusing a key for a **different definition version** returns `409` rather than the original run.
Silently returning the first run would tell a caller their new test had been accepted when it had
not, and they would read the old run's results as if they described the new one.

---

## 7. Failure codes

| Code | Meaning |
|---|---|
| `PROVISIONING_FAILED` | The runner could not be created — quota, image pull, scheduling |
| `RUNNER_EXITED_NON_ZERO` | A shard exited non-zero |
| `RUNNER_LOST` | A shard stopped reporting, or the provider no longer knows it |
| `DURATION_EXCEEDED` | The run passed its wall-clock ceiling and was stopped |
| `RESULTS_INCOMPLETE` | Required shards produced no usable results |
| `PLAN_DIGEST_MISMATCH` | A runner returned results for a different plan |
| `ANALYSIS_FAILED` | Objectives could not be evaluated from the collected artifacts |
| `INTERNAL_ERROR` | The control plane could not attribute the failure |

The run reports the code its shard failed with rather than a generic one: a runner that vanished and
one that exited non-zero send an operator to completely different places.

A run fails if **any** required shard fails. A partial result set measures less load than the plan
describes, and reporting its latencies as the test's outcome would state a pass the test never
demonstrated.

---

## 8. Configuration

```yaml
platform:
  orchestration:
    execution-provider: fake        # 'fake' generates no load; refused under a production profile
    reconciliation-enabled: true
    reconciliation-interval: 10s
    lease-duration: 2m              # must exceed the reconciliation interval
    heartbeat-tolerance: 5m         # too short fails healthy runs during a GC pause
    execution-grace: 10m            # added to the plan duration to form the runner deadline
    validation-grace: 1m
    claim-batch-size: 20
    session-acquisition:
      provider: runtime-credential # use external-session-broker for production run://session
    capacity:
      global-max-runs: 10
      default-team-max-runs: 1
      team-overrides: {}
```

Every value is enforced server-side. Nothing in a submitted specification can influence any of them:
a specification that could raise its own limits would make the limits decorative, and the thing being
limited is how much simultaneous load the platform points at internal systems.

Broker-backed `run://session` has its own operator guide:
[run-session-acquisition.md](run-session-acquisition.md). It covers Helm values, mounted Secret
layout, the runner `sessions.csv` file and the broker request/response contract.

---

## 9. Diagnosing a run that is not progressing

**Stuck in `QUEUED`.** Capacity. `GET` the run — the admission reason is computed live. Check whether
the team is already at its ceiling (`capacity.team.exhausted`) or the platform is full
(`capacity.global.exhausted`). The `ADMISSION_DEFERRED` event records that it waited; the live reason
records why it is waiting *now*.

**Stuck in `VALIDATING`.** A trigger died between creating the run and queueing it. Reconciliation
picks it up after `validation-grace` and decides it. If it stays, reconciliation is not running —
check `reconciliation-enabled` and the scheduler log line at startup.

**Stuck in `PROVISIONING`.** The provider accepted nothing, or the pass is failing. Look for
`Reconciliation failed runId=… stage=provision` in the logs, with the exception. The run keeps its
state and its lease lapses, so it is retried; one misbehaving run cannot stall other teams' runs.

**Stuck in `RUNNING` with nothing executing.** The shard's heartbeat goes stale after
`heartbeat-tolerance` and the run fails with `RUNNER_LOST`. If that is not happening, the provider is
reporting `UNKNOWN` — it cannot be reached. Nothing is concluded from an unreachable provider on
purpose: a wrong "finished" frees a capacity slot while the load continues.

**Nothing is progressing at all.** Every pass logs when it does anything. A silent loop with queued
runs means either reconciliation is disabled, or the admission mutex is held by a stuck transaction —
check for long-running transactions on `orchestration_locks`.

# Cancellation and recovery

Stopping a run, and what happens when the control plane does not survive the attempt.

Delivered by Increment 1C. Relates to
[target architecture §5.6, §9](../architecture/performance-testing-platform-target-architecture.md)
and [run-lifecycle.md](run-lifecycle.md).

---

## 1. Cancelling a run

```
POST /api/v2/test-runs/{runId}/cancel?reason=Submitted%20against%20the%20wrong%20environment
```

Requires the `runs:cancel` scope **and** membership of the run's team. `runs:cancel` is separate from
`runs:execute` so a responder can stop a run without being able to start any.

### It is two steps, not one

Whenever a runner might exist, cancellation is recorded first and acted on afterwards:

1. The request is persisted on the run (`cancellation_requested_by`, `_at`, `_reason`) and a
   `CANCELLATION_REQUESTED` event is appended.
2. The next reconciliation pass asks the provider to terminate each live shard.
3. Only once termination is **observed** does the run move to `CANCELLED`.

Doing it in one step would mean a control-plane crash between "user pressed stop" and "runner
terminated" left a load generator running against a real system with nothing recording that anyone
had asked it to stop.

Reporting `CANCELLED` before the load actually ceased would be worse than slow: someone would walk
away believing the traffic had stopped.

### Responses

| Outcome | Meaning | HTTP |
|---|---|---|
| `CANCELLED` | Nothing was executing; the run stopped immediately | 200 |
| `CANCELLATION_REQUESTED` | A runner is still being terminated; the run is still `RUNNING` | 200 |
| `ALREADY_CANCELLED` | The run was already cancelled. Nothing changed | 200 |
| `run.notCancellable` | The run has finished, or has passed the point where stopping means anything | 409 |

A run that has not been provisioned yet (`VALIDATING`, `AWAITING_APPROVAL`, `QUEUED`) goes straight
to `CANCELLED` — there is no reason to make someone wait a reconciliation cycle for a run that was
never going to start.

### Idempotency

Repeating a cancellation succeeds and changes nothing. People retry a stop button that appears not to
have worked, and a retry that failed would push them toward blunter instruments — deleting the Job by
hand, or restarting the control plane. A repeat also does not append a second
`CANCELLATION_REQUESTED` event.

Cancelling a run that **finished** is refused with `409`. That is not a retry; it is a mistake about
which run is being stopped, and silently returning success would leave the caller believing they had
stopped something.

Cancelling during `ANALYZING` is also refused. The load has already stopped, so there is nothing left
to stop, and discarding the results would throw away evidence the test already paid for.

---

## 2. Recovery after a control-plane restart

Nothing is held in memory. A run's state, its plan, its shards and its external runner handles are
all durable before they matter, so a restart resumes from committed state rather than losing whatever
an in-process thread was holding — which is exactly what V1 did.

### How it works

Each reconciler instance has an owner identity (`reconciler-<random>`), fresh on every start. A
restarted process is therefore a *new* owner, and the runs its predecessor held become claimable as
soon as their leases lapse — within `lease-duration`, 2 minutes by default.

The takeover appends a `CLAIM_EXPIRED` event naming the previous owner. That is the visible trace of
a control plane that failed and recovered itself, and it is worth having when reconstructing an
incident.

The first reconciliation pass runs as soon as the application is up, with no initial delay: a control
plane that has just restarted is precisely the one holding runs whose leases lapsed while it was down.

### What recovery covers

| Interrupted at | What happens |
|---|---|
| Between creating a run and queueing it | The run sits in `VALIDATING`; after `validation-grace` a pass re-decides it and queues or rejects it |
| After claiming, before provisioning | The lease lapses; another reconciler claims it and provisions normally |
| After provisioning, before recording `RUNNING` | The shard already carries its handle, so it is **not** provisioned again; the pass records `RUNNING` |
| While a run was executing | The lease lapses; another reconciler observes the shards and drives the run to a terminal state |
| After a cancellation was requested | The request is durable; the next pass terminates the runner and finishes the job |
| After one shard failed but others are running | The persisted failure intent is recovered; all runner handles are stopped before the run becomes `FAILED` or releases its slot |

The third row is the important one. Provisioning is the only step that creates load, so it is the
only step that must never be repeated. A shard with a handle is skipped, however many times a pass
re-runs against it.

### What recovery deliberately does not do

**It never replays a failed run.** A Kubernetes pod restart or an automatic retry after a runner
failure produces a second load test against a real system and two overlapping sets of results.
Failures are recorded and surfaced; replaying one is an explicit human decision.

**It never concludes anything from an unreachable provider.** A provider reporting `UNKNOWN` says
nothing about the workload, which may well still be generating load. The heartbeat tolerance is what
eventually declares a shard lost — guessing a terminal phase would free a capacity slot while the
traffic continued.

### Failure cleanup also waits for confirmed termination

A failed shard, provisioning refusal after another shard started, stale heartbeat, or deadline
records `FAILURE_REQUESTED` and the intended failure code/reason on the active run. The state stays
`PROVISIONING`/`RUNNING`, with capacity reserved and no completion timestamp, while termination is
pending. Reconciliation cancels every known handle, including a shard labelled failed because
contact was lost. Once every provider confirms termination, it appends `EXECUTION_TERMINATED` and
moves to `FAILED`. A restart resumes cleanup; it never resumes provisioning that failed run.

An unavailable provider keeps the slot occupied until termination can be confirmed. Restore
provider connectivity or verify/terminate the managed Jobs through your operator process. When
upgrading from an older release, inspect already-terminal failed runs for surviving Jobs before
resuming admission; terminal history is not automatically reopened or rewritten.

---

## 3. Runner deadlines

Every runner is created with a hard deadline: the plan's duration plus `execution-grace` (10 minutes
by default, covering startup, ramp-down and artifact upload, none of which appear in the plan's stage
arithmetic).

This is enforced by the provider, not by the control plane, and that is the point. A workload that
ignores its stages — a hung connection pool, a runaway loop — must not keep generating load
indefinitely because the control plane happened to be unreachable.

A run that passes its deadline is terminated and fails with `DURATION_EXCEEDED`.

---

## 4. Operator scenarios

### "A run is stuck and I need it stopped now"

Use the cancel endpoint first; it is the path that leaves an audit trail and stops the runner
properly. If the control plane itself is not reconciling, deleting the runner Job directly stops the
load, and the next pass will observe the shard as gone and fail the run with `RUNNER_LOST`. The run
will read as failed rather than cancelled, which is accurate: it did not stop the way it was asked to.

### "The control plane is down and runs are executing"

The runners are autonomous and carry their own deadlines. They keep running and then stop on their
own. When the control plane returns, leases lapse and the runs are observed and finished. No action
is needed unless the outage exceeds the runners' deadlines, in which case the runs will have stopped
already and will be reported as failed rather than completed.

### "Two control-plane replicas are fighting over a run"

They cannot. Claiming is arbitrated by row locks, and a run is owned by exactly one reconciler until
its lease lapses. If you see rapid `CLAIM_EXPIRED` events for one run, the lease is too short relative
to how long a pass takes — raise `lease-duration`, which must comfortably exceed
`reconciliation-interval`.

### "The queue is not draining"

Check, in order: reconciliation is enabled and logging; the global ceiling is not already reached
(`capacity.global.exhausted` on the queued runs); the waiting team is not at its own ceiling
(`capacity.team.exhausted`); and no transaction is sitting on the `orchestration_locks` row, which
serializes every admission decision.

### "A run completed but I do not believe it ran"

Check the execution provider and `synthetic` flag. The `fake` provider generates no traffic: its
runs finish with `NO_LOAD_GENERATED` and indeterminate objectives. Production refuses this adapter.
Hosted material execution uses the Kubernetes provider and stored runner evidence.

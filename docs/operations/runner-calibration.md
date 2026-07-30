# Runner calibration

**Status:** required before the platform's capacity numbers mean anything.
**Owner:** whoever operates a runner cell.

Shard count is currently hardcoded to **1**. This document is what has to happen before it is
anything else, and why guessing instead is worse than leaving it at one.

---

## The problem this exists to prevent

A load generator that is short of CPU, memory or network reports **its own** latency, not the system
under test's. The numbers look ordinary. Nothing errors. A p95 of 600 ms is indistinguishable from a
p95 of 600 ms, whether the extra 400 ms came from the service or from a runner Pod waiting on a
throttled CPU quota.

That failure is silent, it is flattering to the system under test's *availability* and damning to its
*latency*, and it is the single easiest way for this platform to produce a confident, wrong answer.

So: the generator must be proven not to be the bottleneck before any capacity claim is made.

---

## What has to be measured

For each runner image and Pod size, find the point where the generator itself becomes the limit.

| Measure | Why it matters | Warning sign |
|---|---|---|
| Runner CPU utilisation | A saturated generator queues its own requests | Sustained > 70% of the limit |
| CPU throttling (`container_cpu_cfs_throttled_seconds_total`) | Throttling adds latency the target never caused | Any sustained throttling at all |
| Heap usage and GC pause time | A GC pause is indistinguishable from a slow response | Pauses approaching the latencies being measured |
| Network throughput and socket exhaustion | The generator runs out of ports before the target runs out of capacity | `TIME_WAIT` growth, connection errors |
| Reported vs. actual concurrency | Threads blocked on the generator are not applying load | Achieved throughput flat while users rise |

The last row is the decisive one. **Raise virtual users until throughput stops rising.** If
throughput plateaus while the runner's CPU is saturated, the plateau is the generator. If it plateaus
while the runner is comfortable, the plateau is the system under test — which is the finding you
actually wanted.

---

## Procedure

1. Point a runner at a **deliberately trivial endpoint** — one that returns immediately and does no
   work. Anything it reports is overhead, and this establishes the floor.
2. Ramp virtual users in steps, holding each step long enough to be steady (2–3 minutes).
3. Record the table above at each step.
4. Find the **knee**: the last step where throughput still rose roughly linearly and the runner was
   not throttled.
5. Set the per-Pod safe ceiling to a fraction of the knee — **60–70%** is a reasonable starting
   point. Calibrating to the knee itself leaves no headroom for a slow target, which is precisely
   when a generator needs the most.
6. Repeat per runner image. A Playwright browser session costs orders of magnitude more than a
   protocol virtual user; the two are budgeted separately and must never share a number.

---

## Turning that into a shard count

Shards exist because one Pod has a ceiling. Once the per-Pod ceiling is known:

```
shardCount = ceil(peakVirtualUsers / safeUsersPerPod)
```

Two things to hold onto:

- **A shard is not free.** Each is a Pod to schedule, an image to pull, and a result set to reduce.
  Datasets must partition cleanly across shards, or every shard replays the same rows and the test
  measures cache behaviour rather than the system.
- **More shards is not more realistic.** Splitting 1,000 users across ten Pods and across one Pod are
  different tests if the target load-balances by connection. Say which one you meant.

Until this procedure has been run for a given cell and image, **leave the shard count at 1**. One
shard whose ceiling is unknown is a smaller lie than four shards whose ceiling is guessed.

---

## Pacing and concurrency, said plainly

CLAUDE.md §15 asks for this to be documented, because it is routinely misread.

**A JMeter thread is not a user.** A thread issues a request, waits for the response, waits out its
think time, and issues the next. What a thread models is *one caller who never overlaps their own
requests*. Real users abandon, retry, open two tabs, and sit idle for minutes.

The practical consequences:

- **Throughput is an outcome, not an input, in a thread-based model.** 100 threads against a service
  answering in 50 ms produce roughly 2,000 requests/second; the same 100 threads against a service
  answering in 500 ms produce 200. The load *falls* as the target slows, which is the opposite of
  what happens to a real system under stress.
- If a target rate is what matters, say so with a throughput objective and pace the workload.
  Otherwise the test quietly relieves pressure exactly when the system is struggling.
- **The reported "expected users" in a report is the thread count**, not a measured population.

## The stepwise stages problem

Load stages are currently rendered as **separate thread groups with staggered start delays**, so
concurrency moves in steps rather than ramping smoothly. A step change stresses a system differently
from a smooth ramp — connection pools and autoscalers both respond to the transition, not just to the
level.

Smooth ramping needs `ConcurrencyThreadGroup` from JMeter Plugins in the runner image. That is a
runner packaging decision and belongs with the image build, which is why it is recorded here rather
than worked around in the plan generator.

---

## Recording a result

Calibration is only useful if the next person can find it. For each cell, record:

- runner image and tag (pinned — a floating tag makes the whole exercise meaningless);
- Pod CPU/memory request and limit;
- safe virtual users per Pod, and the knee it was derived from;
- the date and the platform version it was measured on.

Re-calibrate when the runner image changes, when the Pod size changes, or when the cluster's node
type changes. All three move the knee.

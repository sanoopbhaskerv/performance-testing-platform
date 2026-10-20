# Load models: users, or requests per second

Two ways to describe load, and the difference decides what a run is capable of measuring. CLAUDE.md
§15 requires the platform to document its pacing and concurrency semantics rather than leave them
implicit; this is that document.

---

## 1. The two models

### `stages` — a closed model

A fixed number of virtual users. Each one sends a request, waits for the answer, and sends the next.

**Throughput is an output.** If the system slows down, the users send less, and the load applied
falls with the system's health. That is a faithful model of a fixed population of people using a
system, and it is what most load tests want.

```yaml
model:
  type: stages
  stages:
    - duration: 1m
      targetUsers: 50
    - duration: 5m
      targetUsers: 200
    - duration: 30s
      targetUsers: 0
```

### `arrival-rate` — an open model

Requests arrive at a stated rate whatever the system does with them.

**Throughput is an input.** This models traffic the system does not control — an upstream queue, a
scheduled batch, a public endpoint — and it is the only way to ask *"can it sustain 400 requests a
second?"* rather than *"what does it do with 200 users?"*.

```yaml
model:
  type: arrival-rate
  maxConcurrency: 250
  stages:
    - duration: 1m
      targetRate: 100
    - duration: 5m
      targetRate: 400
    - duration: 30s
      targetRate: 0
```

A stage sets the field its model reads and **not the other one**. A `targetRate` in a closed model,
or a `targetUsers` in an open one, is refused at validation rather than ignored: whoever wrote it
believes something about the run that would not be true, and they would read the report against an
expectation the platform never agreed to.

---

## 2. Which to choose

| The question you are asking | Model |
|---|---|
| "Can the system handle our 200 concurrent users?" | `stages` |
| "What happens at twice our busiest hour's user count?" | `stages` |
| "Can it sustain 400 requests a second?" | `arrival-rate` |
| "The upstream batch pushes 1,000/s at us — does it cope?" | `arrival-rate` |
| "How does it behave when a queue drains into it?" | `arrival-rate` |

The closed model has a well-known blind spot, usually called **coordinated omission**: a system that
stalls receives *less* traffic during the stall, because its users are all waiting. The test
therefore under-reports how bad the stall was. If that specific behaviour is what you are
investigating, the open model is the one that will show it.

The open model has the opposite hazard, which is why it needs a ceiling.

---

## 3. `maxConcurrency`: what it is for

**Required for `arrival-rate`, and refused for `stages`** — in a closed model the user count already
*is* the concurrency, so a second limit would be either redundant or a contradiction.

In an open model, requests that do not complete accumulate. At 400/s against a target that stops
answering, ten seconds of stall is four thousand requests in flight. Without a ceiling the load
generator exhausts its own threads or heap, dies, and **the run reports the generator's failure as
the target's** — a result that looks like evidence and is not.

### Choosing a number

Little's law: sustaining a rate `R` with at most `C` requests in flight requires every response to
arrive within `C / R` seconds.

```
maxConcurrency ≈ peak rate × the slowest response time you are willing to tolerate, in seconds
```

400/s where half a second is the worst acceptable response is 200; the shipped example uses 250 to
leave headroom. Validation warns when the implied budget falls below **100 ms**, and states the
figure rather than prescribing a ceiling — only you know how fast your endpoint is.

### When the ceiling binds

If the target is slower than the implied budget, the ceiling — not your rate — is what limits the
run, and the test measures the platform's own throttle. That is sometimes exactly what is wanted
(deliberate throttling), which is why it is a warning and not an error. The way to tell from the
results: **the achieved throughput sits below the requested rate while latency climbs**.

Which is why an open-model test should carry a throughput objective:

```yaml
- metric: http.throughput
  scope: quotes-rate
  threshold: 380/s
```

In an open model, failing to *achieve* the rate is itself the finding. A run that asked for 400/s and
sustained 240/s did not meet its objective however good its latency looked — and without this
objective nothing in the report would say so.

---

## 4. How the platform executes them

Both go through JMeter's **Custom Thread Groups** plugin (`jpgc-casutg`), which the runner image
installs and which a local development JMeter needs too. `./scripts/start-platform.sh` installs it;
the control plane refuses to start without it, because JMeter does not fail on an element it cannot
resolve — it logs, skips the thread group and exits successfully, so a missing plugin would present
as a run that succeeded having generated no load.

| Model | Element | Why |
|---|---|---|
| `stages` | Ultimate Thread Group | Layers sum to the requested concurrency curve, so concurrency **glides** |
| `arrival-rate` | Free-Form Arrivals Thread Group | Schedule rows map one-to-one onto stages, with `ConcurrencyLimit` as the ceiling |

### Staged concurrency now glides, and that changed what the numbers mean

Before S8 a staged model became one vanilla thread group per stage, each starting from zero, so
concurrency **stepped** at every boundary: the previous stage's threads stopped and the next stage's
started. A smooth ramp and a step are different tests — connection pools, autoscalers and circuit
breakers respond to the transition and not only to the level.

**Runs of the same definition either side of this change are not directly comparable at their stage
transitions.** Steady-state numbers are unaffected; what changes is the behaviour observed while
load is moving.

### Sharding

Users, rates and the concurrency ceiling all divide across shards the same way, with the remainder
going to the lowest-indexed shards. The shards together ask for what the specification asked for:
rounding each up would send more traffic than the document describes, and rounding each down would
quietly send less — the worse error, because the run would report a rate it never achieved as though
the target could not keep up.

---

## 5. What admission control budgets

**Never the rate.** A rate says nothing about how much of a generator a test occupies: 500 requests a
second against a 20 ms endpoint is about ten concurrent requests, and against a 2 s endpoint it is a
thousand. Budgeting against the rate would let a heavy test through as if it were free and hold a
light one back as if it were enormous.

So `peakUsers` — the number admission reads — is the **concurrency ceiling** for an open model and
the **peak user count** for a closed one. Both describe the same thing: requests in flight at once.

The run's report quotes both, because they answer different questions: the rate is what was asked
for, and the ceiling is what the platform reserved.

---

## 6. What is not here yet

- **Arrival rate for browser workloads.** A browser cohort is a fixed number of real browsers
  measuring the experience; asking for a rate of browser journeys would be asking for a number of
  machines. The studio offers the choice only for protocol tests.
- **Per-scenario rates.** A rate applies to the workload, and scenarios divide it by weight.
- **Representative distributed calibration.** The single-shard arrival-rate path has now run in
  kind through the production-profile console: 7,000 requests, zero errors, 100 RPS in a 50-second
  steady window, concurrency ceiling 20, and four fingerprint-verified evidence downloads. This
  fixture proof does not establish multi-shard capacity, cloud network enforcement or performance
  against an operator's application. See `runner-images/jmeter-runner/README.md` for the run identity.

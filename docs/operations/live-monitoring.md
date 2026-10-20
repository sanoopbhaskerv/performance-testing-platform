# Live monitoring

How a run that is still executing shows something other than a state.

Until S7 a run in flight showed `Running` and nothing else. For an experienced engineer that is
tolerable; for someone on their first load test it is twenty minutes of a spinner followed by a
verdict they have no context for, with no way to tell a climbing error rate from a healthy run until
it is over.

---

## 1. The shape of it

```
  runner pod                     object storage                control plane                console
  ──────────                     ──────────────                ─────────────                ───────
  every 5s:                      teams/{team}/runs/{run}/      every 10s (reconcile):       every 2s:
    reduce results so far   ──▶    shards/{shard}/               list PROGRESS objects  ──▶   GET …/progress
    PUT progress.json              progress.json                 decode + verify identity
                                   (overwritten in place)        merge across shards
                                                                 append to run_progress
```

**The runner publishes; nothing calls back.** A runner holds no control-plane credential and no
database access, by design — a provider able to write run state could report its own failures as
successes (`HANDOFF.md` §3e). It already holds object-storage credentials, so publishing there costs
nothing new. This is the single constraint that shapes everything below.

**It is engine-neutral.** The JMeter runner reduces its JTL with a Python script; the Playwright
runner reduces its results CSV with a Node one; the local development provider does the same in Java
because it owns the results file rather than a pod. All three write the same document, and the
control plane merges them with the same code. No `BackendListener`, no InfluxDB, no Graphite.

---

## 2. The document

`progress.json`, `perfplatform.progress/v1`. One per shard, overwritten in place every five seconds.

```json
{
  "schema": "perfplatform.progress/v1",
  "runId": "…", "shardId": "…", "shardIndex": 0, "shardCount": 2,
  "engine": "jmeter",
  "planDigest": "sha256:…",
  "capturedAt": "2026-08-04T10:01:05Z",
  "elapsedSeconds": 65,
  "windowSeconds": 5,
  "activeUsers": 26,
  "overall": {
    "sampleCount": 5, "errorCount": 1, "unparsedCount": 1,
    "minMillis": 38, "maxMillis": 9001, "meanMillis": 1909,
    "throughputPerSecondMilli": 1000,
    "latencyBucketBoundsMillis": [1, 2, 3, 5, …, 60000],
    "latencyBucketCounts": [0, 0, 0, …]
  },
  "byOperation": { "checkout/browse": { … } }
}
```

### Three properties that are decisions, not details

**A runner never reports a percentile.** It reports how many samples fell in each latency bucket.
Percentiles do not merge — the p95 of two shards is neither the mean nor the larger of their p95s —
so a shard that reported one would force the control plane either to average them, producing a
number no sample supports, or to discard the other shard. Bucket counts add exactly.

The consequence is that **a live percentile is an upper bound**: the top of the bucket the sample
fell in, never interpolated. A p95 shown as 150 ms means "at most 150 ms". The exact figure comes
from the final analysis, which reduces the complete raw results.

**The bucket edges travel with the counts.** Runner images are released on their own schedule, so a
control plane that assumed the current edges would silently misread an older image's snapshot and
report confident, wrong latencies. Snapshots whose edges disagree are refused for merging rather
than re-bucketed.

**Identity is echoed and checked.** `runId` and `planDigest` are repeated inside the document even
though the object key implies them. A stale object under a reused key would otherwise contribute
real-looking numbers to the wrong run's live view, where — unlike a report — nothing would ever look
wrong about them. A snapshot describing another run or another plan is discarded and logged.

`throughputPerSecondMilli` is measured over the window since the previous snapshot, not over the
run. "How fast is it going" is a question about now; a lifetime average hides a generator that
stopped two minutes ago behind the traffic it sent before it did.

---

## 3. What the control plane does with it

Each reconciliation pass over a `RUNNING` run lists the `PROGRESS` artifacts, decodes each, discards
anything whose identity does not match, merges what is left, and appends the result to
`run_progress`. Collection happens twice per pass — once before the shards are inspected, so a pass
that finds them all finished still merges the final snapshot each runner wrote on its way out, and
once after, because the local development provider publishes from inside `inspect`.

**Nothing about live monitoring can fail a run.** Storage briefly unreadable, an object caught
mid-overwrite, a document from an image this version does not know — each costs a point on a chart,
and none may stop a run reaching a terminal state. A run that could not finish because a chart could
not be drawn would be a strictly worse platform than one with no chart.

### `run_progress` is derived and disposable

Percentiles are bucket bounds and counts come from partial output. A run's verdict is computed from
the complete raw results and lives in `run_metrics`. Losing the progress series costs a chart, never
a verdict — which is what makes it safe to prune on a retention schedule while artifacts are kept.

It is kept after a run reaches a terminal state anyway: a run that died leaves its last snapshot as
the only account of what was happening at the time.

**Retention is not yet automated.** Rows accumulate at roughly one per ten seconds per active run
(≈360 for a one-hour run) and nothing prunes them. See §7.

---

## 4. Reading it

```
GET /api/v2/test-runs/{runId}/progress?limit=120
```

`204` when no generator has published anything yet — deliberately, because "not measured yet" and
"measured nothing" are different facts and a body of zeros lets a client render the second while the
first is true.

The response carries the merged numbers, each operation separately, **each generator's own last
snapshot**, a bounded series for charting, the objectives judged against the latest snapshot, and
the observations described below.

The per-generator block is not decoration. Merged totals cannot show a generator that stopped: the
survivors keep adding to them, so a run that lost half its load generation reads, in the aggregate,
as a run that is merely slower. Only the per-shard capture time exposes it.

`provisionalObjectives` uses the same evaluator the final verdict uses. One definition of a breach —
a live warning computed by a looser rule would eventually disagree with the run's own verdict and
destroy the credibility of both. It is never stored as a verdict, and each outcome's detail text
says so, because a screenshot of one row is what actually travels.

---

## 5. The interpretation layer

Rules over observed data, never a model's opinion. The audience is somebody who has never run a load
test; they cannot tell a confident wrong statement from a right one, so the platform must not be
capable of making one. Every message carries the measurement it came from.

| Code | Severity | Fires when |
|---|---|---|
| `RUNNER_SILENT` | critical | A shard's snapshot is older than the heartbeat tolerance |
| `NO_LOAD_OBSERVED` | critical | Executing 60s+ with no completed request |
| `ERROR_RATE_CRITICAL` | critical | ≥25% failing over ≥20 samples |
| `ERROR_RATE_ELEVATED` | warning | ≥5% failing over ≥20 samples |
| `ERRORS_RISING` | warning | The recent window's error rate is ≥2× the run's own baseline and ≥2% |
| `LATENCY_DEGRADING` | warning | p95 has at least doubled since the start of the run |
| `SHARDS_NOT_REPORTING` | warning | Fewer generators have published than the plan calls for |
| `OBJECTIVE_AT_RISK` | warning | A live objective is currently breaching |
| `THROUGHPUT_PLATEAU` | info | Active users up ≥25% while throughput moved <5% |
| `WARMING_UP` | info | No samples yet, under 60s elapsed |
| `NOTHING_OF_CONCERN` | info | Nothing else fired — saying nothing reads as "not looked at" |

`ERRORS_RISING` exists because a cumulative rate is slow to move: a system that started failing
outright a minute ago can still show 3% overall while it fails 40% of what it is currently asked.

`RUNNER_SILENT` uses the same tolerance the reconciler uses to declare a shard lost. Two definitions
of "this generator is gone" would eventually disagree, and a live view that called a shard silent
while the reconciler still counted it healthy — or the reverse — is worse than either answer alone.

The rules deliberately do **not** predict, extrapolate to a finish, or estimate whether a run "will
pass". A live view exists so a person can decide whether to keep watching or stop the test; a
forecast would replace their decision with arithmetic that has no basis in how the system under test
behaves at the next stage.

---

## 6. Freshness

A number reaches the screen through two independent cadences: a generator publishes every **5
seconds**, and the control plane merges on each reconciliation pass, **10 seconds** apart. A
perfectly healthy view is therefore ~15 seconds behind by construction.

The console's badge is calibrated to that whole pipeline, not to one hop, and stops saying "Live"
once the age passes it. This matters more than it sounds: a view that silently kept showing
minute-old numbers would be worse than one showing nothing, because somebody would make a decision
from it.

Measured locally against the demo service with the `local-jmeter` provider: **11–14 seconds**
consistently, over a 105-second run.

To change the cadences:

- `PERF_PROGRESS_INTERVAL_SECONDS` on the runner Job (set by `RunnerJobManifest`; `0` disables
  publishing entirely, the escape hatch for a run large enough that re-reducing its results file is
  a cost worth avoiding).
- `platform.orchestration.reconciliation-interval` for the merge cadence. Raising it makes every
  live number correspondingly staler.

---

## 7. Operating it

**Metrics to watch.** Progress collection is inside the reconciliation pass, so a slow object store
shows up as reconciliation duration rather than as a distinct signal. If passes lengthen after
enabling this on a large run, the reduction inside the runner and the `listForRun` call are the two
places to look.

**Storage cost.** One small object per shard, overwritten in place — the bucket does not grow with
run length. The database series does: ~360 rows per hour per active run, each carrying its
per-operation document. At ten concurrent runs that is ~3,600 rows an hour, which is not a capacity
problem but is unbounded until retention exists.

**Prune manually if needed.** `ProgressCatalog.clear(runId)` removes one run's series; nothing calls
it on a schedule yet. Deleting rows for terminal runs older than the artifact retention window is
safe — the verdict and the measurements behind it are in `run_metrics` and the artifacts.

**A run with no live view is not a broken run.** `204` means no generator has published yet, which
is normal for the first fifteen seconds and permanent for a runner image predating S7. The run
executes, is analysed and reaches a verdict exactly as before.

---

## 8. What is not verified

The runner-image half of this — the Python reducer in the JMeter image, the Node one in the
Playwright image, and both entrypoint loops — **has not been run on a cluster**. It joins the
outstanding caveat in `NEXT-SESSION.md`: the JMeter image's entrypoint also changed in 2h and the
Playwright image has never been built or run on kind.

What *is* verified with real load is the whole contract through the local provider: reduction,
publication, identity checks, merge, persistence, the API and the console. The reducer's own output
is committed as a decoder fixture (`platform-orchestration/src/test/resources/progress/`), captured
verbatim from running `progress.py` over a JMeter results file, so the two halves are pinned to each
other even though they have not yet run together.

Before trusting the Kubernetes path, re-run `docs/operations/local-kubernetes-run.md` and confirm:

1. `progress.json` appears under `teams/{team}/runs/{run}/shards/{shard}/` within ~10 seconds of the
   Job starting, and its modification time advances every ~5 seconds.
2. `GET …/progress` returns `200` with a growing `sampleCount`.
3. Deleting one Job of a two-shard run surfaces `RUNNER_SILENT` within the heartbeat tolerance, and
   the totals visibly stop climbing for that shard while the other continues.
4. The final snapshot survives the Job's termination — the object is still readable after the pod is
   gone, and the last series point matches it.

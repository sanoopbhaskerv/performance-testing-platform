# Playwright Runner

This image is the S6 browser runner contract. The control plane renders a shard plan, stores it as
an artifact, then provisions an isolated Job using the pinned Playwright image.

Required runner outputs:

- `runner-manifest.json`
- `results.csv`
- sanitized Playwright JSON or HTML report artifacts
- optional Lighthouse report artifacts

`results.csv` uses:

```text
metric,operation,value,success,timestamp
```

Duration metrics use milliseconds. `web.cls` uses milli-score, so `0.095` is written as `95`.
`web.lighthouse.score` uses an integer percent. The engine parser normalizes web-vital operation
names as `metric@operation`, which lets the common objective evaluator judge web metrics without
engine-specific result tables.

## Live progress

Every `PERF_PROGRESS_INTERVAL_SECONDS` (default 5, `0` disables), `progress.js` reduces `results.csv`
so far and the entrypoint uploads it as `progress.json` under this shard's prefix. It is the same
document the JMeter runner publishes — the control plane merges snapshots from both engines with the
same code, which is what makes a browser cohort and a protocol workload appear in one live view
rather than two.

Journey and step durations reach the aggregate; **web vitals do not**. A CLS score and a journey
duration averaged together would be a number describing nothing, so vitals stay in their own
`metric@operation` rows and out of the headline figures.

Like everything else here it publishes a file and calls nothing: this container holds no
control-plane credential. Failures are swallowed — a missing snapshot costs a point on a chart and
must never affect the run.

Full contract: [docs/operations/live-monitoring.md](../../docs/operations/live-monitoring.md).

**Not verified on a cluster.** This image has never been built or run on kind, and neither has this
addition to it.

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

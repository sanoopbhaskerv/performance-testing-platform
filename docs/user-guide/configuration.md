# Describe your performance requirements

The UI, REST API and YAML use one versioned `PerformanceTest` model. Export YAML from the wizard
for a working starting point. The published schema is
[`specification/schema/v1alpha1/performance-test.schema.json`](../../specification/schema/v1alpha1/performance-test.schema.json).
The existing `performance.ameriprise.com/v1alpha1` API version is retained for compatibility; using
this schema does not require Ameriprise infrastructure or accounts.

## Translate requirements into a test

| Requirement you know | Configuration |
|---|---|
| Team and application | `metadata.team`, `metadata.application`; application resolves to a registered target |
| Test environment | `spec.environment`; target class must be permitted by server policy |
| Expected concurrent API users | Protocol staged model `targetUsers` |
| Required requests per second | Arrival-rate model `targetRate` plus mandatory `maxConcurrency` ceiling |
| Ramp-up / steady state / ramp-down | `model.stages` with durations and targets |
| Transaction mix | Weighted scenarios with ordered steps, checks, extraction and think time |
| Real browser users | Browser `browserUsers`, separately budgeted, not protocol users |
| Journey repetitions | `iterationsPerUser`; applies to every test function in the reviewed script |
| Page experience | Optional `web.lcp`, `web.ttfb` (ms), and `web.cls` (unitless score) objectives |
| Journey latency / failures | `browser.journey.duration`, `browser.error.rate` objectives |
| API latency / failures / throughput | `http.response.time`, `http.error.rate`, `http.throughput` objectives |
| Test data | Versioned dataset reference, declared columns and partitioning policy |
| Authentication | Secret references, never inline credentials |
| Stop limit | `governance.maxDuration`; enforced independently of the submitted script |

## Example: 100 requests per second with a concurrency ceiling of 20

Operators can cap protocol users and duration separately from the number of simultaneous runs.
Defaults are 1,000 protocol users across a run's workloads and one hour. Arrival-rate concurrency
ceilings count toward that user budget. A specification cannot increase an operator's ceiling.
See the [configuration reference](../deployment/configuration-reference.md).

Objective thresholds must use the metric's units: durations use `ms`/`s`, error rates and Lighthouse
use `%`, throughput uses `/s`, and CLS uses `score`. Supported percentiles are 50, 75, 90, 95, 99
and 100 (the maximum). Other percentiles are rejected rather than approximated. New summaries
store exact p75; historical summaries without it remain unavailable until reanalyzed from evidence.
Existing historical verdicts are not rewritten by the upgrade. Use a fresh run for corrected
objective evaluation, or an operator's reanalysis of retained evidence; there is no public reanalysis
endpoint in this release.

The API Validator follows up to five redirects within the registered origin. A redirect to another
scheme, host or port is refused before sending a request there. Register the final origin as your
target when an application redirects to a different service. Destination DNS policy is checked
before each request; company network controls remain necessary for network isolation.

Start with [`api-100-rps.yaml`](../../specification/examples/valid/api-100-rps.yaml). In the API wizard
choose **A number of requests per second**, enter `100`, set **At most this many at once** to `20`,
ramp up for 10 seconds, hold for 60, and ramp down for 10. The expected whole-run mean is 87.5/s
because ramps are included. The example's throughput floor is 85/s; inspect the steady stage
separately when judging the 100-RPS requirement. If you instead need twenty continuously looping
virtual users, choose **A number of people** with `20`; achieved RPS is then a measured output.

## Browser example

First upload the reviewed script using the wizard or `scripts/perf.py upload-browser`. Substitute
the URI and SHA-256 returned by your deployment below; do not invent an artifact reference.

```yaml
apiVersion: performance.ameriprise.com/v1alpha1
kind: PerformanceTest
metadata:
  name: catalogue-browser
  team: my-team
  application: catalogue
spec:
  environment:
    name: performance
    runnerCell: standalone
    targetClass: non-production
  sources:
    - name: reviewed-journey
      type: playwright
      uri: "<URI returned by upload>"
      digest: "<SHA-256 returned by upload>"
  workloads:
    - name: catalogue-browser
      type: browser
      engine: playwright
      sourceRef: reviewed-journey
      browserUsers: 1
      iterationsPerUser: 1
      browserProfile:
        device: desktop
        network: none
        cpuThrottle: 1
  objectives:
    - metric: browser.journey.duration
      scope: catalogue-browser
      percentile: 95
      threshold: 8s
    - metric: browser.error.rate
      scope: catalogue-browser
      threshold: 0%
  governance:
    maxDuration: 5m
```

Use a workload name as the scope for whole-journey objectives. Step samples are labelled
`workload/step title`; mixing them into a whole-journey objective changes what the percentile means.
Web-metric objectives such as `web.lcp` use the same workload scope and are evaluated only when
measurements exist. `network: none` means unthrottled; YAML also supports `slow-4g`, `fast-4g`, `wifi`
and a positive CPU slowdown multiplier. The wizard starts with unthrottled desktop execution.

API, browser and hybrid examples live in [`specification/examples/valid`](../../specification/examples/valid).
For a hybrid run, the browser workload uses `startWhen.workload` and `startWhen.stage` to wait for
an API workload’s stage. Test the API and browser halves individually first. Hybrid authoring is
currently YAML/API based; the guided browser wizard authors a browser-only run.

## Persistence and results

Validate without execution, save an immutable definition version, then trigger its version ID with
an idempotency key. Reusing the same key for the same request returns the same logical run. A new
key is a new experiment and real load. Cancel through the run API or console; a client disconnect
or CLI timeout does not cancel a run. Inspect recorded status and authorized evidence afterward.

The default global admission limit is five material runs and one run per team; operators can
configure it. This is an admission limit, not a benchmarked capacity guarantee. Keep browsers
small, calibrate the runner resources, then raise load deliberately.

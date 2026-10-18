# Deployment troubleshooting and acceptance

Use your private values file and the rendered manifest as the starting point. Logs, traces and
request bodies may contain company data; keep investigation artifacts in approved storage.

| Symptom | Check and resolution |
|---|---|
| Helm fails before rendering | Production requires existing auth/DB Secret references, artifact bucket, restricted CIDRs; branding theme/colors must be valid |
| Helm wants to adopt a pre-created runner namespace | Use `runnerCell.createNamespace=false` for an operator-owned dedicated namespace |
| API `ImagePullBackOff` | Registry address/tag, pull Secret in control-plane namespace, service-account pull references, registry/network trust |
| Runner `ImagePullBackOff` | `runnerCell.images`, approved pinned tag, runner-namespace pull Secret; raw upstream images do not implement this runner |
| API fails on Flyway/database | JDBC URL, PostgreSQL reachability/CA, Secret key names, migration privileges; do not switch to `ddl-auto:update` |
| UI loads but Connect fails | Same-origin API routing, token file/hash/scopes/team/expiry, exact `Bearer` credential; production rejects development identity |
| Logo missing / default brand remains | Public JSON at `/platform-config.json`, correct mounted path and logo key, allowed `/branding/...` or HTTPS URL, browser reload |
| Configuration changes do not take effect | Correct values file/release; inspect ConfigMap/mounts and duplicate env/YAML settings; external Secret env changes need a rollout |
| S3 access denied | Distinguish API identity from runner identity; bucket/prefix, KMS and trust policies; region/addressing; projected identity/STS network access |
| S3 endpoint works on API but runner fails | `runnerEndpoint`, runner DNS/routing/CNI allowlist, TLS CA, namespace credential Secret |
| Run queued indefinitely | Existing team run, five-run global limit, browser capacity, reconciler enabled, DB lease health and available cluster resources |
| Runner pending | CPU/memory requests, quotas, namespace policies, scheduling capacity and image availability; capacity is not created by admission settings |
| Target refused or unreachable | Registered team/application/environment, allowed destination/DNS, approved runner CIDRs, network routing and certificate trust |
| Browser source rejected | Reviewed self-contained script or Git repository + immutable SHA + path; no embedded secrets, moving branch or unapproved image |
| Browser metrics absent | Final-page collection support and samples; LCP may have fewer samples on a quickly closed page; unavailable is not zero or PASS |
| Download refused for an old JMeter run | Older runner uploads lacked fingerprints; use `5.6.3-3` for new runs. Do not invent a historical digest or disable verification |
| Whole-run rate below steady target | Ramp-up/down lower the average. Inspect a steady window in JTL and check the concurrency ceiling before blaming the target |
| Restart produces a second workload | Stop further tests and inspect durable Job UID/reconciliation behavior; material Job retries must remain disabled |

Useful commands (example resource names from `company-values.yaml`):

```bash
kubectl -n perf-platform get pods,events
kubectl -n perf-platform logs deployment/performance --tail=100
kubectl -n perf-runners get jobs,pods,events
kubectl -n perf-runners describe job JOB_NAME
kubectl -n perf-platform get serviceaccount performance-sa -o yaml
kubectl -n perf-runners get serviceaccount perf-runner -o yaml
kubectl -n perf-runners get networkpolicy
```

Do not paste credentials, full Job environments or sensitive application payloads into public issues.
For a support report include the selected code/image versions, sanitized failure code/run identity,
provider, expected behavior and a minimal reproduction against a disposable target.

Before consumer onboarding, verify one real run, objective breach, cancellation, private artifact
download, cross-team denial, expired-token denial, and control-plane restart recovery. Confirm
the application's actual cloud egress/identity/storage/TLS configuration. Revisit calibration when
you change runner resources, images, workload mix or infrastructure.

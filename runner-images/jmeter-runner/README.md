# JMeter runner image

Runs **one shard of one performance test**, then exits. It is the thing that actually generates load
in production; the control plane never does (CLAUDE.md §4).

It makes no decisions. It does not choose a target, a duration, a team or a plan — all of that is
fixed before the container starts. It fetches what it was told to run, runs it, uploads what it
produced, and stops.

## Build

```bash
docker build -t perfplatform/jmeter-runner:5.6.3-3 runner-images/jmeter-runner
```

Then add it to the cell's allowlist in `helm/performance-platform/values.yaml`:

```yaml
runnerCell:
  images:
    jmeter: "perfplatform/jmeter-runner:5.6.3-3"
```

**Tags must be pinned.** Startup refuses `latest`, `main`, `master`, `edge` or an untagged image: a
moving tag means two runs of the same immutable definition version can execute on different builds,
so the plan digest still matches while the thing executing it has changed.

## The contract

Everything comes from the environment, set by `RunnerJobManifest`. Change one here and change it
there; the manifest's contract tests are what keep the two honest.

| Variable | Meaning |
|---|---|
| `PERF_RUN_ID`, `PERF_TEAM_ID`, `PERF_SHARD_ID` | Identity. Also the correlation value in workload traffic |
| `PERF_SHARD_INDEX`, `PERF_SHARD_COUNT` | Which slice of the work this is |
| `PERF_PLAN_DIGEST` | Echoed back in the manifest; results that disagree are refused, not merged |
| `PERF_WORKLOAD_KEY` | Object key of the plan to execute |
| `PERF_ARTIFACT_BUCKET`, `PERF_ARTIFACT_PREFIX` | Where to read from and write to |
| `PERF_ARTIFACT_ENDPOINT`, `PERF_ARTIFACT_REGION`, `PERF_ARTIFACT_PATH_STYLE` | For an S3-compatible server; omit for AWS |
| `PERF_WORKSPACE` | The only writable path. The root filesystem is read-only |
| `PERF_DEADLINE_SECONDS` | Internal ceiling, slightly under the cluster's `activeDeadlineSeconds` |
| `PERF_OPERATION_LABELS` | The `workload/scenario` labels results must carry |
| `PERF_PROGRESS_INTERVAL_SECONDS` | How often to publish a live snapshot; `0` disables it |

**No credentials are passed.** The Pod's projected service-account token authorises the uploads. A
key in a Job manifest is a key in an audit log, and eventually in a ticket.

It uploads to `PERF_ARTIFACT_PREFIX`:

- `results.jtl` — the measurements
- `jmeter.log` — the runner's own log
- `runner-manifest.json` — identity, timings, exit code, and the plan digest
- `progress.json` — a rolling snapshot, overwritten every few seconds while it runs

Version `5.6.3-3` uploads a SHA-256 fingerprint (`perf-digest` S3 metadata) and explicit content
type for every durable evidence file. Authorized downloads verify that recorded fingerprint.
Older uploads without metadata remain unverifiable; do not bypass the download check or assign
an invented historical digest. The entrypoint upload contract is exercised in CI:

```bash
python3 -m unittest discover -s runner-images/jmeter-runner -p 'test_*.py'
```

## Live progress

Every `PERF_PROGRESS_INTERVAL_SECONDS`, `progress.py` reduces what has been written to `results.jtl`
so far and the entrypoint PUTs it as `progress.json`, overwriting the previous one. The control plane
reads and merges those snapshots; without them a run in flight shows a state and nothing else.

**It is a file, not a callback**, and that is the point: this container holds no API token and no
database credential by design, because a runner able to write run state could report its own failure
as a success. It already holds object-storage credentials, so publishing there costs nothing new.

**It never reports a percentile** — it reports latency bucket counts, because percentiles from two
shards cannot be added and the control plane has to merge them. It never decides anything about the
run either: no pass, no fail, no threshold.

Every failure in this path is swallowed. A snapshot that could not be reduced or uploaded costs a
point on a chart; nothing about it may affect the test, its results or its exit code. A final
snapshot is taken after JMeter exits, including on cancellation, because that one is the account of
what was happening when a run died.

Full contract: [docs/operations/live-monitoring.md](../../docs/operations/live-monitoring.md).

## Two things it deliberately does not do

**It does not judge the run.** JMeter exits 0 even when every request failed; it reports on
execution, not on the system under test. Deciding pass or fail from inside the thing being measured
would be the wrong place for that decision, so the control plane does it.

**It is never retried.** `backoffLimit: 0`, because a retried performance test is a second load test
against a real system and two overlapping sets of results — and it would look like an ordinary run.

On `SIGTERM` — cancellation, or the deadline — it stops JMeter and **still uploads what exists**.
Evidence from a run that failed is worth more than evidence from one that succeeded; a failed run
with nothing to look at is an incident nobody can diagnose.

## Verifying a change

The image is versioned separately from the Java code, so nothing but a test keeps its output
readable. `RunnerOutputContractTest` in `engine-jmeter` parses a fixture that this image genuinely
produced. If it fails after an image change, **the image is wrong until proven otherwise** — the
parser's expectations are the older contract.

To reproduce that fixture, or to check a change by hand:

```bash
docker build -f platform-persistence/src/test/resources/minio/Dockerfile \
  -t perfplatform/minio-test:2024-11-07-1 .
docker network create perf-e2e
docker run -d --rm --name perf-minio --network perf-e2e \
  -e MINIO_ROOT_USER=minio -e MINIO_ROOT_PASSWORD=minio123 -p 19000:9000 \
  perfplatform/minio-test:2024-11-07-1 server /data
docker run -d --rm --name perf-demo --network perf-e2e \
  -v "$PWD/demo-service/target:/app:ro" eclipse-temurin:17-jre-jammy \
  java -jar /app/demo-service-1.0.0.jar

export AWS_ACCESS_KEY_ID=minio AWS_SECRET_ACCESS_KEY=minio123 AWS_DEFAULT_REGION=us-east-1
aws --endpoint-url http://localhost:19000 s3 mb s3://perf-artifacts
aws --endpoint-url http://localhost:19000 s3 cp plan.jmx \
  s3://perf-artifacts/teams/t-1/runs/r-1/shards/s-1/shard-0.jmx

docker run --rm --network perf-e2e \
  --user 10001:0 --read-only --tmpfs /workspace:rw,size=256m,uid=10001,gid=0,mode=0770 \
  --cap-drop ALL --security-opt no-new-privileges \
  -e AWS_ACCESS_KEY_ID=minio -e AWS_SECRET_ACCESS_KEY=minio123 \
  -e PERF_RUN_ID=r-1 -e PERF_TEAM_ID=t-1 -e PERF_SHARD_ID=s-1 \
  -e PERF_SHARD_INDEX=0 -e PERF_SHARD_COUNT=1 -e PERF_ENGINE=jmeter \
  -e PERF_PLAN_ID=p-1 -e PERF_PLAN_DIGEST=sha256:deadbeef \
  -e PERF_WORKLOAD_KEY=teams/t-1/runs/r-1/shards/s-1/shard-0.jmx \
  -e PERF_WORKSPACE=/workspace -e PERF_DEADLINE_SECONDS=180 \
  -e PERF_ARTIFACT_BUCKET=perf-artifacts \
  -e PERF_ARTIFACT_ENDPOINT=http://perf-minio:9000 \
  -e PERF_ARTIFACT_REGION=us-east-1 \
  -e PERF_ARTIFACT_PREFIX=teams/t-1/runs/r-1/shards/s-1/ \
  perfplatform/jmeter-runner:5.6.3-3
```

The `--user`, `--read-only`, `--tmpfs` and `--cap-drop` flags mirror what the Job manifest enforces.
Run it without them and you are testing something the cluster will never run.

> Docker's `--tmpfs` does not apply an `fsGroup`, so the `uid=10001` mount option stands in for what
> Kubernetes does for an `emptyDir`. Without it the runner cannot write to its own workspace.

## Verified scope and remaining calibration

The single-shard arrival-rate path was exercised in kind on 19 October 2026 through the actual
production-profile console, PostgreSQL and private S3-compatible store. Run
`1ec111c4-b6da-4673-b6cf-38a393d355a0` produced 7,000 requests, zero errors, p95 2 ms and exactly
100 RPS in a 50-second window inside the steady stage. The concurrency ceiling was 20; the highest
reported thread count was 15. All four downloads (including the compiled plan) passed SHA-256
verification. Live progress and its plan identity were also read by the platform.

This verifies the bounded fixture flow, not an application's production capacity, five-team
resource calibration, cloud IAM or CNI enforcement. Representative multi-shard/distributed tests
and operator infrastructure verification remain necessary. The current renderer uses Ultimate
Thread Groups for staged users and Free-Form Arrivals for rates; see
[load models](../../docs/operations/load-models.md).

For a reviewed archive mirror set Docker build argument `JMETER_DISTRIBUTION_BASE_URL`. It must
serve `apache-jmeter-5.6.3.tgz` and its matching `.sha512`; downloads are bounded and checked.

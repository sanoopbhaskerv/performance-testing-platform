#!/usr/bin/env bash
set -euo pipefail

: "${RUN_ID:?RUN_ID is required}"
: "${SHARD_INDEX:?SHARD_INDEX is required}"
: "${SHARD_COUNT:?SHARD_COUNT is required}"
: "${PLAN_DIGEST:?PLAN_DIGEST is required}"
: "${WORKLOAD_OBJECT_KEY:?WORKLOAD_OBJECT_KEY is required}"
: "${ARTIFACT_UPLOAD_PREFIX:?ARTIFACT_UPLOAD_PREFIX is required}"

mkdir -p /workspace/output

RESULTS=/workspace/output/results.csv
PROGRESS=/workspace/output/progress.json
PROGRESS_STATE=/workspace/output/progress.state
PROGRESS_INTERVAL_SECONDS="${PERF_PROGRESS_INTERVAL_SECONDS:-5}"
RUNNER_STARTED_AT="$(date -u +%s)"

# ── Live progress ────────────────────────────────────────────────────────────
#
# The same contract the JMeter runner implements: reduce what has been measured so far, PUT it under
# this shard's prefix, overwrite the previous one. The control plane merges snapshots from both
# engines with the same code, which is what makes a browser cohort and a protocol workload appear in
# one live view rather than two.
#
# Not a callback: this container holds no control-plane credential by design. Every failure is
# swallowed — a missing snapshot costs a point on a chart and must never affect the run.
publish_progress_once() {
    [[ -f "${RESULTS}" ]] || return 0

    local now window elapsed previous_samples window_start
    now="$(date -u +%s)"
    read -r previous_samples window_start < "${PROGRESS_STATE}" 2>/dev/null \
        || { previous_samples=0; window_start="${now}"; }
    window=$(( now - window_start ))
    elapsed=$(( now - RUNNER_STARTED_AT ))

    if node /usr/local/bin/perf-progress "${RESULTS}" "${previous_samples}" \
            "${window}" "${elapsed}" \
            >"${PROGRESS}" 2>/workspace/output/progress.count; then
        previous_samples="$(tr -dc '0-9' < /workspace/output/progress.count)"
        printf '%s %s\n' "${previous_samples:-0}" "${now}" > "${PROGRESS_STATE}"
        aws s3 cp "${PROGRESS}" "${ARTIFACT_UPLOAD_PREFIX}/progress.json" >/dev/null 2>&1 || true
    fi
    return 0
}

publish_progress_loop() {
    while true; do
        sleep "${PROGRESS_INTERVAL_SECONDS}"
        publish_progress_once
    done
}

stop_progress() {
    [[ -n "${PROGRESS_PID:-}" ]] && kill -TERM "${PROGRESS_PID}" 2>/dev/null || true
    PROGRESS_PID=""
    [[ "${PROGRESS_INTERVAL_SECONDS}" != "0" ]] && publish_progress_once
    return 0
}
trap stop_progress EXIT

# The control plane renders this JSON from the immutable engine plan. It contains the resolved
# target URL, correlation header and run identifier; it does not contain secret values.
aws s3 cp "${WORKLOAD_OBJECT_KEY}" /workspace/playwright-plan.json

cat > "${RESULTS}" <<'CSV'
metric,operation,value,success,timestamp
CSV

# Started once the results file exists. A snapshot taken before it does says nothing, and one taken
# before the plan was fetched would say it while the run had not begun.
if [[ "${PROGRESS_INTERVAL_SECONDS}" != "0" ]]; then
    printf '0 %s\n' "$(date -u +%s)" > "${PROGRESS_STATE}"
    publish_progress_loop &
    PROGRESS_PID=$!
fi

# The production runner wrapper that executes the reviewed Playwright script is intentionally
# small and deterministic. It is responsible for:
#   - fetching the source URI named in the compiled plan,
#   - setting BASE_URL plus the correlation header/value as environment variables,
#   - running `npx playwright test` with one worker per browser session policy,
#   - appending browser.journey.duration, browser.step.duration and web.* rows to results.csv,
#   - writing sanitized Playwright JSON/HTML report artifacts.
#
# The placeholder below keeps the image contract buildable while S6 validates the control-plane
# path. Material execution requires the wrapper to be completed and calibrated with the runner cell.
node -e 'const plan=require("/workspace/playwright-plan.json"); console.log(JSON.stringify({runId:plan.runId,shardIndex:plan.shardIndex,target:plan.targetBaseUrl}))' \
  > /workspace/output/playwright-runner-summary.json

completed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
cat > /workspace/output/runner-manifest.json <<JSON
{
  "runId": "${RUN_ID}",
  "shardIndex": ${SHARD_INDEX},
  "shardCount": ${SHARD_COUNT},
  "engine": "playwright",
  "planDigest": "${PLAN_DIGEST}",
  "completedAt": "${completed_at}",
  "exitCode": 0,
  "status": "COMPLETED",
  "artifacts": []
}
JSON

stop_progress

aws s3 cp "${RESULTS}" "${ARTIFACT_UPLOAD_PREFIX}/results.csv"
aws s3 cp /workspace/output/runner-manifest.json "${ARTIFACT_UPLOAD_PREFIX}/runner-manifest.json"
aws s3 cp /workspace/output/playwright-runner-summary.json "${ARTIFACT_UPLOAD_PREFIX}/playwright-runner-summary.json"

#!/usr/bin/env bash
set -euo pipefail

: "${RUN_ID:?RUN_ID is required}"
: "${SHARD_INDEX:?SHARD_INDEX is required}"
: "${SHARD_COUNT:?SHARD_COUNT is required}"
: "${PLAN_DIGEST:?PLAN_DIGEST is required}"
: "${WORKLOAD_OBJECT_KEY:?WORKLOAD_OBJECT_KEY is required}"
: "${ARTIFACT_UPLOAD_PREFIX:?ARTIFACT_UPLOAD_PREFIX is required}"

mkdir -p /workspace/output

# The control plane renders this JSON from the immutable engine plan. It contains the resolved
# target URL, correlation header and run identifier; it does not contain secret values.
aws s3 cp "${WORKLOAD_OBJECT_KEY}" /workspace/playwright-plan.json

cat > /workspace/output/results.csv <<'CSV'
metric,operation,value,success,timestamp
CSV

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

aws s3 cp /workspace/output/results.csv "${ARTIFACT_UPLOAD_PREFIX}/results.csv"
aws s3 cp /workspace/output/runner-manifest.json "${ARTIFACT_UPLOAD_PREFIX}/runner-manifest.json"
aws s3 cp /workspace/output/playwright-runner-summary.json "${ARTIFACT_UPLOAD_PREFIX}/playwright-runner-summary.json"

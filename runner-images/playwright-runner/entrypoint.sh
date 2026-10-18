#!/usr/bin/env bash
# Execute exactly one approved shard. No retries and no control-plane/database credential.
set -Eeuo pipefail
: "${PERF_RUN_ID:?required}" "${PERF_TEAM_ID:?required}" "${PERF_SHARD_ID:?required}"
: "${PERF_SHARD_INDEX:?required}" "${PERF_SHARD_COUNT:?required}" "${PERF_PLAN_DIGEST:?required}"
: "${PERF_WORKLOAD_KEY:?required}" "${PERF_ARTIFACT_BUCKET:?required}" "${PERF_ARTIFACT_PREFIX:?required}"
export PERF_WORK_DIR="${PERF_WORKSPACE:-/workspace}"
runner_home="${PERF_WORK_DIR}/home"
export TMPDIR="${PERF_WORK_DIR}/tmp"
export npm_config_cache="${PERF_WORK_DIR}/npm-cache"
export PERF_PLAN_FILE="${PERF_WORK_DIR}/playwright-plan.json"
export PERF_RESULTS_FILE="${PERF_WORK_DIR}/results.csv"
export PERF_RUNTIME_ROOT=/opt/perf-runner
export PERF_CONTAINER=true
mkdir -p "${PERF_WORK_DIR}" "${runner_home}" "${TMPDIR}"
started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
started_seconds="$(date -u +%s)"
aws_args=()
[[ -n "${PERF_ARTIFACT_ENDPOINT:-}" ]] && aws_args+=(--endpoint-url "${PERF_ARTIFACT_ENDPOINT}")
[[ -n "${PERF_ARTIFACT_REGION:-}" ]] && aws_args+=(--region "${PERF_ARTIFACT_REGION}")
export AWS_DEFAULT_REGION="${PERF_ARTIFACT_REGION:-us-east-1}"
export AWS_EC2_METADATA_DISABLED=true
export AWS_CONFIG_FILE="${runner_home}/aws-config"
if [[ "${PERF_ARTIFACT_PATH_STYLE:-false}" == true ]]; then
    printf '[default]\ns3 =\n    addressing_style = path\n' > "${AWS_CONFIG_FILE}"
fi
s3() { aws "${aws_args[@]}" s3 "$@"; }
progress_pid=""
runner_pid=""
progress_once() {
    [[ -s "${PERF_RESULTS_FILE}" ]] || return 0
    local now elapsed previous window_start window count
    now="$(date -u +%s)"; elapsed=$((now - started_seconds))
    previous=0; window_start="${started_seconds}"
    if [[ -f "${PERF_WORK_DIR}/progress.state" ]]; then
        read -r previous window_start < "${PERF_WORK_DIR}/progress.state" || true
    fi
    window=$((now - window_start))
    if node /opt/perf-runner/progress.js "${PERF_RESULTS_FILE}" "${previous}" "${window}" "${elapsed}" > "${PERF_WORK_DIR}/progress.json" 2> "${PERF_WORK_DIR}/progress.count"; then
        count="$(tr -dc '0-9' < "${PERF_WORK_DIR}/progress.count")"
        printf '%s %s\n' "${count:-0}" "${now}" > "${PERF_WORK_DIR}/progress.state"
        s3 cp "${PERF_WORK_DIR}/progress.json" "s3://${PERF_ARTIFACT_BUCKET}/${PERF_ARTIFACT_PREFIX}progress.json" >/dev/null 2>&1 || true
    fi
}
finish() {
    local code=$?
    trap - EXIT
    [[ -z "${progress_pid}" ]] || kill "${progress_pid}" 2>/dev/null || true
    progress_once
    local status=FAILED
    [[ "${code}" == 0 ]] && status=COMPLETED
    # Test assertion failures still produce a complete measurement set. The browser.error.rate
    # objective judges them; setup failures/no tests/timeouts remain failed execution.
    node -e 'const fs=require("fs"); fs.writeFileSync(process.argv[1],JSON.stringify({runId:process.env.PERF_RUN_ID,shardId:process.env.PERF_SHARD_ID,shardIndex:+process.env.PERF_SHARD_INDEX,shardCount:+process.env.PERF_SHARD_COUNT,engine:"playwright",planDigest:process.env.PERF_PLAN_DIGEST,startedAt:process.argv[2],completedAt:new Date().toISOString(),exitCode:+process.argv[3],status:process.argv[4]}))' "${PERF_WORK_DIR}/runner-manifest.json" "${started_at}" "${code}" "${status}"
    if [[ -d "${PERF_WORK_DIR}/test-output" ]]; then
        tar -czf "${PERF_WORK_DIR}/browser-evidence.tar.gz" -C "${PERF_WORK_DIR}" test-output
    fi
    local upload_failed=0 file
    for file in results.csv playwright-report.json playwright-report.html browser-evidence.tar.gz runner.log runner-manifest.json; do
        [[ -f "${PERF_WORK_DIR}/${file}" ]] || continue
        local digest content_type
        digest="sha256:$(sha256sum "${PERF_WORK_DIR}/${file}" | cut -d ' ' -f1)"
        case "${file}" in
          *.json) content_type=application/json ;;
          *.html) content_type=text/html ;;
          *.csv) content_type=text/csv ;;
          *.tar.gz) content_type=application/gzip ;;
          *) content_type=text/plain ;;
        esac
        s3 cp "${PERF_WORK_DIR}/${file}" "s3://${PERF_ARTIFACT_BUCKET}/${PERF_ARTIFACT_PREFIX}${file}" \
            --content-type "${content_type}" --metadata "perf-digest=${digest}" >/dev/null || upload_failed=1
    done
    [[ "${upload_failed}" == 0 ]] || code=74
    exit "${code}"
}
stop() {
    trap - TERM INT
    [[ -z "${runner_pid}" ]] || kill -TERM "${runner_pid}" 2>/dev/null || true
    [[ -z "${runner_pid}" ]] || wait "${runner_pid}" || true
    exit 143
}
trap finish EXIT
trap stop TERM INT
s3 cp "s3://${PERF_ARTIFACT_BUCKET}/${PERF_WORKLOAD_KEY}" "${PERF_PLAN_FILE}" >/dev/null
# Validate the reference shape and team before it can become an S3 key.
source_key="$(node -e 'const p=JSON.parse(require("fs").readFileSync(process.env.PERF_PLAN_FILE));const c=typeof p.compiledPlan==="string"?JSON.parse(p.compiledPlan):p.compiledPlan;const w=c.workloads[0];if(w.sourceGitRepository)process.exit(0);const key=`teams/${process.env.PERF_TEAM_ID}/sources/playwright/${String(w.sourceDigest).slice(7)}/journey.spec.ts`;if(!/^sha256:[a-f0-9]{64}$/.test(w.sourceDigest)||w.sourceUri!==`artifact://${key}`)throw Error("Unowned browser source");process.stdout.write(key);')"
if [[ -n "${source_key}" ]]; then
    export PERF_SOURCE_FILE="${PERF_WORK_DIR}/journey.spec.ts"
    s3 cp "s3://${PERF_ARTIFACT_BUCKET}/${source_key}" "${PERF_SOURCE_FILE}" >/dev/null
fi
printf 'metric,operation,value,success,timestamp\n' > "${PERF_RESULTS_FILE}"
if [[ "${PERF_PROGRESS_INTERVAL_SECONDS:-5}" != 0 ]]; then
    (while sleep "${PERF_PROGRESS_INTERVAL_SECONDS:-5}"; do progress_once; done) &
    progress_pid=$!
fi
set +e
node /opt/perf-runner/perf-runner.mjs > "${PERF_WORK_DIR}/runner.log" 2>&1 &
runner_pid=$!
wait "${runner_pid}"
result=$?
set -e
# Exit 1 can mean an assertion failed; it is analyzable only if actual journeys were recorded.
if [[ "${result}" == 1 ]] && [[ -f "${PERF_WORK_DIR}/playwright-report.json" ]] && [[ "$(wc -l < "${PERF_RESULTS_FILE}")" -gt 1 ]]; then
    result=0
fi
exit "${result}"

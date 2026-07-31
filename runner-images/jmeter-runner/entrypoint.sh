#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
#  Run one shard of one performance test, then exit.
#
#  The whole contract with the control plane is the PERF_* environment set by
#  RunnerJobManifest, plus the artifacts this uploads. There is no callback, no
#  API token, and no database access — deliberately. A runner that could write
#  run state could mark its own failure as a success.
#
#  Exit code is the shard's outcome. Kubernetes turns a non-zero exit into a
#  failed Job, the control plane observes that, and the run fails. It is never
#  retried: backoffLimit is 0, because a retried performance test is a second
#  load test against a real system.
# ─────────────────────────────────────────────────────────────────────────────
set -Eeuo pipefail

log() { printf '%s [runner] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }

# Every one of these is required. A runner that guessed a default would run
# something other than the test that was approved.
: "${PERF_RUN_ID:?PERF_RUN_ID is required}"
: "${PERF_SHARD_ID:?PERF_SHARD_ID is required}"
: "${PERF_SHARD_INDEX:?PERF_SHARD_INDEX is required}"
: "${PERF_SHARD_COUNT:?PERF_SHARD_COUNT is required}"
: "${PERF_PLAN_DIGEST:?PERF_PLAN_DIGEST is required}"
: "${PERF_WORKLOAD_KEY:?PERF_WORKLOAD_KEY is required}"
: "${PERF_ARTIFACT_BUCKET:?PERF_ARTIFACT_BUCKET is required}"
: "${PERF_ARTIFACT_PREFIX:?PERF_ARTIFACT_PREFIX is required}"

WORKSPACE="${PERF_WORKSPACE:-/workspace}"
DEADLINE_SECONDS="${PERF_DEADLINE_SECONDS:-3600}"

PLAN="${WORKSPACE}/plan.jmx"
RESULTS="${WORKSPACE}/results.jtl"
JMETER_LOG="${WORKSPACE}/jmeter.log"
MANIFEST="${WORKSPACE}/runner-manifest.json"

STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# S3-compatible endpoints (MinIO, an appliance) need --endpoint-url; real AWS
# does not. Path-style addressing is likewise only for the former.
aws_args=()
[[ -n "${PERF_ARTIFACT_ENDPOINT:-}" ]] && aws_args+=(--endpoint-url "${PERF_ARTIFACT_ENDPOINT}")
[[ -n "${PERF_ARTIFACT_REGION:-}" ]] && aws_args+=(--region "${PERF_ARTIFACT_REGION}")

s3() { aws "${aws_args[@]}" s3 "$@"; }

# ── Upload, always ───────────────────────────────────────────────────────────
#
# Runs on every exit path, including failure and SIGTERM. Evidence from a run
# that failed is worth more than evidence from one that succeeded — a failed run
# with nothing to look at is an incident nobody can diagnose.
upload() {
    local exit_code=$1 status=$2

    cat > "${MANIFEST}" <<JSON
{
  "runId": "${PERF_RUN_ID}",
  "shardId": "${PERF_SHARD_ID}",
  "shardIndex": ${PERF_SHARD_INDEX},
  "shardCount": ${PERF_SHARD_COUNT},
  "engine": "${PERF_ENGINE:-jmeter}",
  "planDigest": "${PERF_PLAN_DIGEST}",
  "startedAt": "${STARTED_AT}",
  "completedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "exitCode": ${exit_code},
  "status": "${status}"
}
JSON

    # Uploaded individually rather than by syncing the directory: only these are
    # the run's evidence, and a sync would also ship the plan back and whatever
    # else JMeter happened to leave behind.
    for file in "${RESULTS}" "${JMETER_LOG}" "${MANIFEST}"; do
        [[ -f "${file}" ]] || continue
        if ! s3 cp "${file}" "s3://${PERF_ARTIFACT_BUCKET}/${PERF_ARTIFACT_PREFIX}$(basename "${file}")" \
                --only-show-errors; then
            log "WARNING: could not upload $(basename "${file}")"
        fi
    done

    # The manifest is uploaded last and its digest is checked by the control
    # plane before results are accepted, so a mismatch means the results describe
    # a different test and are refused rather than merged.
    log "uploaded evidence to s3://${PERF_ARTIFACT_BUCKET}/${PERF_ARTIFACT_PREFIX}"
}

on_exit() {
    local code=$?
    trap - EXIT
    if [[ ${code} -eq 0 ]]; then
        upload 0 COMPLETED
    else
        upload "${code}" FAILED
    fi
    exit "${code}"
}

# SIGTERM is how both cancellation and activeDeadlineSeconds arrive. Uploading
# what exists before dying is what makes a cancelled run still diagnosable.
on_term() {
    log "received SIGTERM; stopping and uploading partial evidence"
    [[ -n "${JMETER_PID:-}" ]] && kill -TERM "${JMETER_PID}" 2>/dev/null || true
    wait "${JMETER_PID:-}" 2>/dev/null || true
    upload 143 CANCELLED
    trap - EXIT
    exit 143
}

trap on_exit EXIT
trap on_term TERM INT

# ── Fetch the plan ───────────────────────────────────────────────────────────
log "run=${PERF_RUN_ID} shard=${PERF_SHARD_INDEX}/${PERF_SHARD_COUNT} fetching plan"
s3 cp "s3://${PERF_ARTIFACT_BUCKET}/${PERF_WORKLOAD_KEY}" "${PLAN}" --only-show-errors

[[ -s "${PLAN}" ]] || { log "FATAL: the fetched plan is empty"; exit 78; }

# ── Run it ───────────────────────────────────────────────────────────────────
#
# -n non-GUI, -f overwrite an existing results file. JMeter is backgrounded so
# the SIGTERM trap can reach it; a foreground child would leave the signal
# unhandled until it finished, which is the opposite of what cancelling means.
log "starting JMeter, deadline ${DEADLINE_SECONDS}s"

jmeter -n -f \
    -t "${PLAN}" \
    -l "${RESULTS}" \
    -j "${JMETER_LOG}" \
    -Jjmeter.save.saveservice.output_format=csv \
    -Jjmeter.save.saveservice.print_field_names=true \
    -Jjmeter.save.saveservice.timestamp_format=ms \
    -Jjmeter.save.saveservice.response_message=true \
    -Jjmeter.save.saveservice.successful=true \
    -Jjmeter.save.saveservice.label=true \
    -Jjmeter.save.saveservice.latency=true \
    -Jjmeter.save.saveservice.connect_time=true \
    &
JMETER_PID=$!

# A second ceiling inside the container. activeDeadlineSeconds is the cluster's
# hard stop; this one fires slightly earlier so the shard can upload its evidence
# rather than being killed mid-write with nothing to show.
( sleep "${DEADLINE_SECONDS}"; kill -TERM "${JMETER_PID}" 2>/dev/null || true ) &
WATCHDOG_PID=$!

set +e
wait "${JMETER_PID}"
JMETER_EXIT=$?
set -e

kill -TERM "${WATCHDOG_PID}" 2>/dev/null || true

# JMeter exits 0 even when every request failed — it reports on execution, not on
# the system under test. Judging the results is the control plane's job, and this
# deliberately does not attempt it: a runner that decided pass or fail would be
# deciding it from inside the thing being measured.
log "JMeter exited ${JMETER_EXIT}; $(wc -l < "${RESULTS}" 2>/dev/null || echo 0) result rows"
exit "${JMETER_EXIT}"

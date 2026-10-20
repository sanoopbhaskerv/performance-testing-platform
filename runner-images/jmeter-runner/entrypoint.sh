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
PROGRESS="${WORKSPACE}/progress.json"

# How often this publishes what it has measured so far. Five seconds is short enough that somebody
# watching sees a stage change while it is happening, and long enough that re-reducing the results
# file is never what keeps a core busy. Zero disables publishing entirely, which is the escape hatch
# if a run is ever large enough that the reduction itself is a cost worth avoiding.
PROGRESS_INTERVAL_SECONDS="${PERF_PROGRESS_INTERVAL_SECONDS:-5}"

STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
RUNNER_STARTED_AT="$(date -u +%s)"

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
    local uploaded=0 failed=0 digest content_type
    for file in "${RESULTS}" "${JMETER_LOG}" "${MANIFEST}"; do
        [[ -f "${file}" ]] || continue
        digest="sha256:$(sha256sum "${file}" | cut -d' ' -f1)"
        case "${file}" in
            *.json) content_type=application/json ;;
            *.jtl) content_type=text/csv ;;
            *) content_type=text/plain ;;
        esac
        if s3 cp "${file}" "s3://${PERF_ARTIFACT_BUCKET}/${PERF_ARTIFACT_PREFIX}$(basename "${file}")" \
                --metadata "perf-digest=${digest}" --content-type "${content_type}" \
                --only-show-errors; then
            uploaded=$((uploaded + 1))
        else
            failed=$((failed + 1))
            log "WARNING: could not upload $(basename "${file}")"
        fi
    done

    # Reported honestly. Saying "uploaded evidence" after every upload failed is
    # how a run with nothing behind it looks like a run with evidence.
    if [[ ${failed} -gt 0 ]]; then
        log "ERROR: ${failed} of $((uploaded + failed)) artifacts could not be uploaded to " \
            "s3://${PERF_ARTIFACT_BUCKET}/${PERF_ARTIFACT_PREFIX} — this run has incomplete evidence"
    else
        # The manifest carries the plan digest, which the control plane checks
        # before accepting results: a mismatch means they describe a different
        # test and are refused rather than merged.
        log "uploaded ${uploaded} artifacts to s3://${PERF_ARTIFACT_BUCKET}/${PERF_ARTIFACT_PREFIX}"
    fi
}

# ── Live progress ────────────────────────────────────────────────────────────
#
# Every PROGRESS_INTERVAL_SECONDS: reduce what has been written so far and PUT it under this shard's
# prefix, overwriting the previous snapshot. The control plane reads and merges these; it is the
# only way a run shows anything but a state while it is executing.
#
# Not a callback into the control plane, and that is the point. This container holds no API token
# and no database credential, deliberately — a runner able to write run state could report its own
# failures as successes. It already holds object-storage credentials, so this costs nothing new.
#
# Every failure here is swallowed. A snapshot that could not be reduced or uploaded costs a point on
# a chart; nothing about it may affect the test, its results or its exit code.
#
# The window state — cumulative samples and when the window opened — lives in a file rather than in
# shell variables. The loop below runs as a background subshell, so its variables are its own; the
# final snapshot is published by the parent after that loop is killed, and it needs the same window
# the loop was using or the last point on the chart would be a lifetime average rather than a rate.
PROGRESS_STATE="${WORKSPACE}/progress.state"

# One snapshot: reduce, upload, and remember the sample count. The reducer prints the document on
# stdout and the cumulative sample count on stderr, so this needs no second parse of what it wrote.
publish_progress_once() {
    [[ -f "${RESULTS}" ]] || return 0

    local now window elapsed previous_samples window_start
    now="$(date -u +%s)"
    read -r previous_samples window_start < "${PROGRESS_STATE}" 2>/dev/null \
        || { previous_samples=0; window_start="${now}"; }
    window=$(( now - window_start ))
    elapsed=$(( now - RUNNER_STARTED_AT ))

    if python3 /usr/local/bin/perf-progress "${RESULTS}" "${previous_samples}" \
            "${window}" "${elapsed}" \
            >"${PROGRESS}" 2>"${WORKSPACE}/progress.count"; then
        previous_samples="$(tr -dc '0-9' < "${WORKSPACE}/progress.count")"
        printf '%s %s\n' "${previous_samples:-0}" "${now}" > "${PROGRESS_STATE}"
        s3 cp "${PROGRESS}" \
            "s3://${PERF_ARTIFACT_BUCKET}/${PERF_ARTIFACT_PREFIX}progress.json" \
            --only-show-errors >/dev/null 2>&1 || true
    fi
    return 0
}

publish_progress_loop() {
    while true; do
        sleep "${PROGRESS_INTERVAL_SECONDS}"
        publish_progress_once
    done
}

on_exit() {
    local code=$?
    trap - EXIT
    stop_progress
    if [[ ${code} -eq 0 ]]; then
        upload 0 COMPLETED
    else
        upload "${code}" FAILED
    fi
    exit "${code}"
}

# Stops the rolling publisher and takes one last snapshot. The final one matters most: a run that
# failed leaves it as the only account of what was happening at the moment it did.
stop_progress() {
    [[ -n "${PROGRESS_PID:-}" ]] && kill -TERM "${PROGRESS_PID}" 2>/dev/null || true
    PROGRESS_PID=""
    [[ "${PROGRESS_INTERVAL_SECONDS}" != "0" ]] && publish_progress_once
    return 0
}

# SIGTERM is how both cancellation and activeDeadlineSeconds arrive. Uploading
# what exists before dying is what makes a cancelled run still diagnosable.
on_term() {
    log "received SIGTERM; stopping and uploading partial evidence"
    [[ -n "${JMETER_PID:-}" ]] && kill -TERM "${JMETER_PID}" 2>/dev/null || true
    wait "${JMETER_PID:-}" 2>/dev/null || true
    stop_progress
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

# ── Fetch this shard's data ──────────────────────────────────────────────────
#
# PERF_DATASETS is `fileName=objectKey` pairs, comma-separated. The control
# plane has already sliced each dataset so no two shards send the same row, so
# there is nothing to decide here — fetch, and put it where the plan looks.
#
# A dataset that cannot be fetched is fatal, before any load is generated. JMeter
# would otherwise start happily and send the literal text ${orderId} in every
# request: the target answers 404 to all of it in a millisecond or two, and the
# run reports excellent latency for a system that did no work.
if [[ -n "${PERF_DATASETS:-}" ]]; then
    IFS=',' read -ra datasets <<< "${PERF_DATASETS}"
    for entry in "${datasets[@]}"; do
        file_name="${entry%%=*}"
        object_key="${entry#*=}"
        [[ -n "${file_name}" && -n "${object_key}" && "${file_name}" != "${entry}" ]] || {
            log "FATAL: PERF_DATASETS entry is not fileName=objectKey"; exit 78; }
        # Refused rather than sanitised: the control plane builds these names and
        # a separator in one means something upstream is wrong, not that this
        # should guess a safe name.
        [[ "${file_name}" == *"/"* || "${file_name}" == *".."* ]] && {
            log "FATAL: dataset file name would escape the workspace"; exit 78; }

        log "fetching dataset ${file_name}"
        s3 cp "s3://${PERF_ARTIFACT_BUCKET}/${object_key}" "${WORKSPACE}/${file_name}" \
            --only-show-errors
        [[ -s "${WORKSPACE}/${file_name}" ]] || {
            log "FATAL: dataset ${file_name} is empty"; exit 78; }
    done
    log "fetched ${#datasets[@]} dataset partitions"
fi

# ── Run it ───────────────────────────────────────────────────────────────────
#
# -n non-GUI, -f overwrite an existing results file. JMeter is backgrounded so
# the SIGTERM trap can reach it; a foreground child would leave the signal
# unhandled until it finished, which is the opposite of what cancelling means.
log "starting JMeter, deadline ${DEADLINE_SECONDS}s"

# A CSVDataSet in the plan names a plain file, which JMeter resolves against the
# working directory. The image already sets WORKDIR to /workspace; this makes it
# true for a PERF_WORKSPACE pointing anywhere else, rather than leaving the data
# a directory away from where JMeter looks for it.
cd "${WORKSPACE}"

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
    -Jjmeter.save.saveservice.thread_counts=true \
    &
JMETER_PID=$!

# The rolling publisher, started only once JMeter is running: a snapshot taken before there is a
# results file says nothing, and one taken before the plan was fetched would say it while the run
# had not begun.
if [[ "${PROGRESS_INTERVAL_SECONDS}" != "0" ]]; then
    printf '0 %s\n' "$(date -u +%s)" > "${PROGRESS_STATE}"
    publish_progress_loop &
    PROGRESS_PID=$!
    log "publishing progress every ${PROGRESS_INTERVAL_SECONDS}s"
fi

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
stop_progress

# JMeter exits 0 even when every request failed — it reports on execution, not on
# the system under test. Judging the results is the control plane's job, and this
# deliberately does not attempt it: a runner that decided pass or fail would be
# deciding it from inside the thing being measured.
log "JMeter exited ${JMETER_EXIT}; $(wc -l < "${RESULTS}" 2>/dev/null || echo 0) result rows"
exit "${JMETER_EXIT}"

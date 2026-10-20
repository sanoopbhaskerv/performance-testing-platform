#!/usr/bin/env python3
"""Reduce a partial JMeter results file into one progress snapshot.

The runner's half of the live-monitoring contract. Every few seconds the entrypoint calls this,
uploads what it prints to ``progress.json`` under its own shard prefix, and the control plane merges
the shards' snapshots into the run's live view.

Why this exists as a file the runner writes rather than a callback the runner makes: a runner holds
no control-plane credential and no database access, by design, because a provider able to write run
state could report its own failures as successes. It already holds object-storage credentials, so
publishing there costs nothing new.

Three properties this must have, each of which is a decision:

* **It never reports a percentile.** It reports how many samples fell in each latency bucket, and
  the control plane reads percentiles off the merged distribution. Percentiles do not merge — the
  p95 of two shards is neither the mean nor the larger of their p95s — so a shard that reported one
  would force the control plane either to average them (a number no sample supports) or to discard
  the other shard.
* **It reads a file that is being appended to.** The last row is routinely half-written. A row that
  cannot be parsed is counted, not dropped and not fatal; it is complete by the next snapshot.
* **It never decides anything about the run.** No pass, no fail, no threshold. Judging results from
  inside the thing being measured is exactly what the separation of control and execution exists to
  prevent.

The document shape is versioned in the document itself, and is the same one
``ShardProgressJson`` writes in the control plane's own local provider.
"""

import csv
import json
import os
import sys
from datetime import datetime, timezone

SCHEMA = "perfplatform.progress/v1"

# Must match LatencyHistogram.DEFAULT_BOUNDS_MILLIS. They are also written into every document, so a
# control plane reading a snapshot from an older image reads that image's edges rather than assuming
# these — but keeping them identical is what makes a live percentile and a final one comparable.
BOUNDS_MILLIS = [
    1, 2, 3, 5, 8, 10, 15, 20, 30, 50, 75, 100, 150, 200, 300, 500, 750,
    1000, 1500, 2000, 3000, 5000, 7500, 10000, 15000, 20000, 30000, 60000,
]


def bucket_of(latency_millis):
    """The index of the bucket a latency falls in; the last index is the unbounded overflow."""
    for index, bound in enumerate(BOUNDS_MILLIS):
        if latency_millis <= bound:
            return index
    return len(BOUNDS_MILLIS)


class Metrics:
    """Running totals for one operation, or for everything."""

    def __init__(self):
        self.sample_count = 0
        self.error_count = 0
        self.unparsed_count = 0
        self.min_millis = None
        self.max_millis = 0
        self.total_millis = 0
        self.buckets = [0] * (len(BOUNDS_MILLIS) + 1)

    def add(self, elapsed_millis, success):
        self.sample_count += 1
        if not success:
            self.error_count += 1
        self.min_millis = elapsed_millis if self.min_millis is None \
            else min(self.min_millis, elapsed_millis)
        self.max_millis = max(self.max_millis, elapsed_millis)
        self.total_millis += elapsed_millis
        self.buckets[bucket_of(elapsed_millis)] += 1

    def as_document(self, throughput_per_second_milli):
        return {
            "sampleCount": self.sample_count,
            "errorCount": self.error_count,
            "unparsedCount": self.unparsed_count,
            "minMillis": self.min_millis or 0,
            "maxMillis": self.max_millis,
            "meanMillis": self.total_millis // self.sample_count if self.sample_count else 0,
            "throughputPerSecondMilli": throughput_per_second_milli,
            "latencyBucketBoundsMillis": BOUNDS_MILLIS,
            "latencyBucketCounts": self.buckets,
        }


def reduce_results(path):
    """Read what has been written so far.

    Returns ``(overall, by_operation, active_users)``, or ``None`` when the file has no readable
    header yet — JMeter writes the header before the first sample, so that is the first second of a
    run rather than a problem.
    """
    overall = Metrics()
    by_operation = {}
    active_users = 0

    try:
        with open(path, "r", encoding="utf-8", errors="replace", newline="") as results:
            reader = csv.reader(results)
            try:
                header = next(reader)
            except StopIteration:
                return None

            columns = {name.strip(): index for index, name in enumerate(header)}
            for required in ("elapsed", "label", "success"):
                if required not in columns:
                    return None

            threads_column = columns.get("allThreads")

            for row in reader:
                try:
                    elapsed = int(row[columns["elapsed"]])
                    label = row[columns["label"]] or "unlabelled"
                    # Anything other than an explicit "true" is a failure. A row whose success
                    # column is blank is not evidence of success, and defaulting it would report a
                    # healthier run the more badly it was going.
                    success = row[columns["success"]].strip().lower() == "true"
                    if elapsed < 0:
                        raise ValueError("negative elapsed")
                except (IndexError, ValueError):
                    overall.unparsed_count += 1
                    continue

                overall.add(elapsed, success)
                by_operation.setdefault(label, Metrics()).add(elapsed, success)

                if threads_column is not None:
                    try:
                        active_users = max(0, int(row[threads_column]))
                    except (IndexError, ValueError):
                        pass

    except FileNotFoundError:
        return None

    return overall, by_operation, active_users


def main():
    results_path = sys.argv[1]
    previous_sample_count = int(sys.argv[2])
    window_seconds = max(1, int(sys.argv[3]))
    elapsed_seconds = max(0, int(sys.argv[4]))

    reduced = reduce_results(results_path)
    if reduced is None:
        return 1

    overall, by_operation, active_users = reduced

    # Throughput over the window since the previous snapshot, not over the run. "How fast is it
    # going" is a question about now; a lifetime average hides a generator that stopped two minutes
    # ago behind the traffic it sent before it did.
    window_samples = max(0, overall.sample_count - previous_sample_count)
    throughput_per_second_milli = (window_samples * 1000) // window_seconds

    def share(part):
        return (throughput_per_second_milli * part) // overall.sample_count \
            if overall.sample_count else 0

    document = {
        "schema": SCHEMA,
        "runId": os.environ["PERF_RUN_ID"],
        "shardId": os.environ["PERF_SHARD_ID"],
        "shardIndex": int(os.environ.get("PERF_SHARD_INDEX", "0")),
        "shardCount": int(os.environ.get("PERF_SHARD_COUNT", "1")),
        "engine": os.environ.get("PERF_ENGINE", "jmeter"),
        # Echoed back so the control plane can refuse a snapshot describing a different plan, for
        # the same reason the runner manifest echoes it: a stale object under a reused key would
        # otherwise contribute real-looking numbers to the wrong run's live view.
        "planDigest": os.environ["PERF_PLAN_DIGEST"],
        "capturedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "elapsedSeconds": elapsed_seconds,
        "windowSeconds": window_seconds,
        "activeUsers": active_users,
        "overall": overall.as_document(throughput_per_second_milli),
        "byOperation": {
            name: metrics.as_document(share(metrics.sample_count))
            # Sorted, so two snapshots list their operations in the same order. A live table whose
            # rows reshuffled between polls would be unreadable for the one thing it is for.
            for name, metrics in sorted(by_operation.items())
        },
    }

    json.dump(document, sys.stdout, separators=(",", ":"))
    # The sample count, on stderr, so the entrypoint can pass it back as the next window's baseline
    # without parsing the document it just wrote.
    print(overall.sample_count, file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())

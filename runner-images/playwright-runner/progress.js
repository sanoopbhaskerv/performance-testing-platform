#!/usr/bin/env node
'use strict';

/**
 * Reduce a partial browser results file into one progress snapshot.
 *
 * The browser half of the same contract the JMeter runner implements in Python. Both write the same
 * document, because the control plane merges them with the same code: live monitoring is
 * engine-neutral by construction, and nothing in the format mentions samplers or thread groups.
 *
 * Rows are `metric,operation,value,success,timestamp`, exactly as `PlaywrightResultParser` reads
 * them at the end of a run, and web-vital rows are labelled `metric@operation` for the same reason
 * they are there — an objective scoped to a vital has to find its measurements under a name that
 * cannot collide with a journey's.
 *
 * The reduction is deliberately identical in shape to the protocol one, including reporting bucket
 * counts rather than percentiles. A browser cohort is small — five sessions, up to twenty — so its
 * distribution is coarse either way; the reason to keep buckets is that the control plane must not
 * need to know which engine produced a snapshot in order to merge it.
 *
 * Usage: progress.js <results.csv> <previousSampleCount> <windowSeconds> <elapsedSeconds>
 */

const fs = require('fs');

const SCHEMA = 'perfplatform.progress/v1';

// Must match LatencyHistogram.DEFAULT_BOUNDS_MILLIS. Written into every document as well, so a
// control plane reading an older image's snapshot reads that image's edges rather than assuming.
const BOUNDS_MILLIS = [
  1, 2, 3, 5, 8, 10, 15, 20, 30, 50, 75, 100, 150, 200, 300, 500, 750,
  1000, 1500, 2000, 3000, 5000, 7500, 10000, 15000, 20000, 30000, 60000,
];

const WEB_METRIC_PREFIX = 'web.';

function bucketOf(value) {
  for (let index = 0; index < BOUNDS_MILLIS.length; index++) {
    if (value <= BOUNDS_MILLIS[index]) return index;
  }
  return BOUNDS_MILLIS.length;
}

function newMetrics() {
  return {
    sampleCount: 0,
    errorCount: 0,
    unparsedCount: 0,
    minMillis: null,
    maxMillis: 0,
    totalMillis: 0,
    buckets: new Array(BOUNDS_MILLIS.length + 1).fill(0),
  };
}

function add(metrics, value, success) {
  metrics.sampleCount += 1;
  if (!success) metrics.errorCount += 1;
  metrics.minMillis = metrics.minMillis === null ? value : Math.min(metrics.minMillis, value);
  metrics.maxMillis = Math.max(metrics.maxMillis, value);
  metrics.totalMillis += value;
  metrics.buckets[bucketOf(value)] += 1;
}

function asDocument(metrics, throughputPerSecondMilli) {
  return {
    sampleCount: metrics.sampleCount,
    errorCount: metrics.errorCount,
    unparsedCount: metrics.unparsedCount,
    minMillis: metrics.minMillis || 0,
    maxMillis: metrics.maxMillis,
    meanMillis: metrics.sampleCount ? Math.floor(metrics.totalMillis / metrics.sampleCount) : 0,
    throughputPerSecondMilli,
    latencyBucketBoundsMillis: BOUNDS_MILLIS,
    latencyBucketCounts: metrics.buckets,
  };
}

function main() {
  const [resultsPath, previousSamples, windowSeconds, elapsedSeconds] = process.argv.slice(2);

  let content;
  try {
    content = fs.readFileSync(resultsPath, 'utf8');
  } catch (error) {
    return 1;
  }

  const lines = content.split(/\r?\n/);
  if (lines.length < 1 || !lines[0].startsWith('metric,')) return 1;

  const overall = newMetrics();
  const byOperation = new Map();

  for (let index = 1; index < lines.length; index++) {
    const line = lines[index];
    if (!line.trim()) continue;

    const columns = line.split(',');
    if (columns.length < 4) {
      // A half-written final row. Counted, because it was an attempt, and complete by the next
      // snapshot — never silently dropped, which would report a healthier run than happened.
      overall.unparsedCount += 1;
      continue;
    }

    const metric = columns[0].trim();
    const operation = columns[1].trim();
    const value = Number.parseInt(columns[2], 10);
    const success = columns[3].trim().toLowerCase() === 'true';

    if (!metric || !operation || Number.isNaN(value) || value < 0) {
      overall.unparsedCount += 1;
      continue;
    }

    const key = metric.startsWith(WEB_METRIC_PREFIX) ? `${metric}@${operation}` : operation;
    if (!byOperation.has(key)) byOperation.set(key, newMetrics());
    add(byOperation.get(key), value, success);

    // Only journey durations reach the aggregate. A web vital is a page measurement on a different
    // scale — a CLS score and a journey duration averaged together would be a number describing
    // nothing — so vitals stay in their own named rows and out of the headline.
    if (metric === 'browser.journey.duration') {
      add(overall, value, success);
    }
  }

  const window = Math.max(1, Number.parseInt(windowSeconds, 10) || 1);
  const windowSamples = Math.max(0, overall.sampleCount - (Number.parseInt(previousSamples, 10) || 0));
  const throughputPerSecondMilli = Math.floor((windowSamples * 1000) / window);

  const operations = {};
  // Sorted, so two snapshots list their rows in the same order. A live table that reshuffled
  // between polls would be unreadable for the one thing it is for.
  for (const key of [...byOperation.keys()].sort()) {
    const metrics = byOperation.get(key);
    const share = overall.sampleCount
      ? Math.floor((throughputPerSecondMilli * metrics.sampleCount) / overall.sampleCount)
      : 0;
    operations[key] = asDocument(metrics, share);
  }

  const document = {
    schema: SCHEMA,
    runId: process.env.PERF_RUN_ID || process.env.RUN_ID,
    shardId: process.env.PERF_SHARD_ID || process.env.SHARD_ID,
    shardIndex: Number.parseInt(process.env.PERF_SHARD_INDEX || process.env.SHARD_INDEX || '0', 10),
    shardCount: Number.parseInt(process.env.PERF_SHARD_COUNT || process.env.SHARD_COUNT || '1', 10),
    engine: 'playwright',
    // Echoed so the control plane can refuse a snapshot describing a different plan, exactly as it
    // does for the runner manifest.
    planDigest: process.env.PERF_PLAN_DIGEST || process.env.PLAN_DIGEST || '',
    capturedAt: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
    elapsedSeconds: Math.max(0, Number.parseInt(elapsedSeconds, 10) || 0),
    windowSeconds: window,
    // Browser sessions currently executing, which is what "users" means for this engine. Reported
    // by the wrapper; absent until it does, and 0 reads as "not reported" rather than "none".
    activeUsers: Math.max(0, Number.parseInt(process.env.PERF_ACTIVE_SESSIONS || '0', 10) || 0),
    overall: asDocument(overall, throughputPerSecondMilli),
    byOperation: operations,
  };

  process.stdout.write(JSON.stringify(document));
  // The cumulative sample count on stderr, so the entrypoint can carry it into the next window
  // without parsing the document it just wrote.
  process.stderr.write(String(overall.sampleCount));
  return 0;
}

process.exit(main());

import type { ProgressPoint, RunProgress } from './api';

/**
 * Turning live measurements into something a person can read at a glance.
 *
 * Pure functions, separated from the component for the usual reason and one specific one: the
 * platform's rule about scaled integers ends here. Throughput, error rates and percentiles all
 * arrive scaled by 1,000 so that a threshold comparison is exact, and every one of them has to be
 * unscaled before a human sees it. Doing that in one file, tested, is how "21400" stops being able
 * to reach a screen.
 */

/** Requests per second, from the scaled integer the API sends. */
export function rate(throughputPerSecondMilli: number): number {
  return throughputPerSecondMilli / 1000;
}

/**
 * A rate for display: two significant decimals when it is small, none when it is large.
 *
 * "0.42/s" and "1,204/s" are both useful; "0.00/s" and "1,204.37/s" are not.
 */
export function formatRate(throughputPerSecondMilli: number): string {
  const value = rate(throughputPerSecondMilli);
  if (value === 0) return '0';
  if (value < 10) return value.toFixed(2).replace(/0+$/, '').replace(/\.$/, '');
  return Math.round(value).toLocaleString();
}

/** A percentage, from the scaled integer: 1500 becomes "1.5". */
export function formatPercent(milliPercent: number): string {
  const value = milliPercent / 1000;
  if (value === 0) return '0';
  if (value < 10) return value.toFixed(2).replace(/0+$/, '').replace(/\.$/, '');
  return value.toFixed(1);
}

/** A latency, in the unit that reads best at its size. */
export function formatMillis(millis: number): string {
  if (millis < 1000) return `${Math.round(millis)} ms`;
  return `${(millis / 1000).toFixed(millis < 10000 ? 2 : 1)} s`;
}

/** A length of time. */
export function formatDuration(seconds: number): string {
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ${seconds % 60}s`;
  return `${Math.floor(minutes / 60)}h ${minutes % 60}m`;
}

/** How long ago something happened. */
export function formatAge(seconds: number): string {
  return `${formatDuration(seconds)} ago`;
}

/**
 * How trustworthy the numbers on screen are as a picture of *now*.
 *
 * The whole value of a live view is that it is current, so its currency has to be visible. A view
 * that silently kept showing sixty-second-old numbers would be worse than one that showed nothing:
 * somebody would make a decision from it.
 *
 * ## Calibrated to the whole pipeline, not to one hop
 *
 * A number reaches this screen through two independent cadences: a generator publishes a snapshot
 * every five seconds, and the control plane merges what it finds on each reconciliation pass, ten
 * seconds apart. So a perfectly healthy view is fifteen-odd seconds behind by construction, and a
 * badge calibrated to the publishing interval alone would read "lagging" for the entire life of
 * every run — which is the same as reading nothing at all.
 *
 * @param expectedLagSeconds the pipeline's own lag: one publishing interval plus one merge interval
 */
export function freshness(
  ageSeconds: number,
  expectedLagSeconds = 15,
): { state: 'live' | 'lagging' | 'stale'; label: string } {
  // Ten seconds of slack past the expected lag — one more merge pass — so a single missed pass does
  // not flip the badge while nothing is actually wrong.
  if (ageSeconds <= expectedLagSeconds + 10) {
    return { state: 'live', label: 'Live' };
  }
  // Beyond a minute the numbers describe a different moment than the one being looked at, and the
  // badge stops implying currency entirely.
  if (ageSeconds <= 60) {
    return { state: 'lagging', label: `${ageSeconds}s behind` };
  }
  return { state: 'stale', label: `No update for ${formatDuration(ageSeconds)}` };
}

/**
 * An SVG path through a series, scaled to a box.
 *
 * Drawn by hand rather than with a charting library: this is one polyline, and adding a dependency
 * whose bundle exceeds the feature would be the wrong trade. The y-axis is always anchored at zero —
 * a sparkline scaled to its own minimum exaggerates a flat series into a dramatic one, which is
 * exactly the confident-but-wrong impression this platform keeps refusing to give.
 */
export function sparkline(
  points: ProgressPoint[],
  value: (point: ProgressPoint) => number,
  width: number,
  height: number,
): string {
  if (points.length < 2) return '';

  const values = points.map(value);
  const max = Math.max(...values, 0);
  if (max <= 0) return '';

  const step = width / (points.length - 1);
  return values
    .map((current, index) => {
      const x = index * step;
      const y = height - (current / max) * height;
      return `${index === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(' ');
}

/**
 * The generators that have stopped publishing, for marking rows in the table.
 *
 * Deliberately a *lower* threshold than the server's heartbeat tolerance, which is minutes: that one
 * decides whether to declare a generator lost and fail the run, and it is conservative because the
 * consequence is severe. This one only shades a row, so it can say "this has not been heard from in
 * a while" much sooner. The server's own observation remains the thing that states a generator is
 * gone, so the two can only disagree by this marking a row before the warning appears — never by
 * showing a warning with no row to explain it.
 */
export function quietShards(progress: RunProgress, toleranceSeconds = 45): number[] {
  return progress.shards
    .filter((shard) => shard.ageSeconds > toleranceSeconds)
    .map((shard) => shard.shardIndex);
}

/**
 * Operations worth showing first: the slowest, then the busiest.
 *
 * Somebody watching a run wants the problem, not an alphabetical list. Sorting by p95 puts whatever
 * is struggling at the top; sample count breaks ties so a single slow outlier does not outrank an
 * operation carrying the run.
 */
export function rankedOperations(progress: RunProgress): RunProgress['byOperation'] {
  return [...progress.byOperation].sort(
    (left, right) => right.p95Millis - left.p95Millis || right.sampleCount - left.sampleCount,
  );
}

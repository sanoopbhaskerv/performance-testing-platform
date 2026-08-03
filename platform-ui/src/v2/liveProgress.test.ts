import { describe, it, expect } from 'vitest';
import {
  formatAge,
  formatMillis,
  formatPercent,
  formatRate,
  freshness,
  quietShards,
  rankedOperations,
  sparkline,
} from './liveProgress';
import type { LiveMetrics, ProgressPoint, RunProgress } from './api';

/**
 * The unscaling layer, tested because it is the last place a scaled integer can leak.
 *
 * Everything the platform measures travels as an integer scaled by 1,000, so that a threshold
 * comparison is exact and reproducible. That is right for a verdict and unreadable for a person:
 * "21400" on a screen means nothing, and — worse — is plausible. These are the functions that stop
 * it reaching one.
 */

const point = (over: Partial<ProgressPoint> = {}): ProgressPoint => ({
  capturedAt: '2026-08-04T10:00:00Z',
  elapsedSeconds: 60,
  activeUsers: 25,
  sampleCount: 1000,
  errorCount: 0,
  errorRateMilliPercent: 0,
  throughputPerSecondMilli: 20_000,
  p95Millis: 120,
  ...over,
});

const metrics = (over: Partial<LiveMetrics> = {}): LiveMetrics => ({
  name: 'checkout/browse',
  sampleCount: 100,
  errorCount: 0,
  unparsedCount: 0,
  errorRateMilliPercent: 0,
  minMillis: 10,
  maxMillis: 500,
  meanMillis: 90,
  p50Millis: 75,
  p90Millis: 150,
  p95Millis: 200,
  p99Millis: 500,
  throughputPerSecondMilli: 10_000,
  ...over,
});

const progress = (over: Partial<RunProgress> = {}): RunProgress => ({
  runId: 'r-1',
  engine: 'jmeter',
  capturedAt: '2026-08-04T10:00:00Z',
  observedAt: '2026-08-04T10:00:02Z',
  ageSeconds: 2,
  shardsExpected: 1,
  shardsReporting: 1,
  elapsedSeconds: 60,
  activeUsers: 25,
  overall: metrics({ name: '*' }),
  byOperation: [],
  shards: [],
  series: [],
  provisionalObjectives: [],
  observations: [],
  ...over,
});

describe('formatRate', () => {
  it('unscales the integer the API sends', () => {
    expect(formatRate(21_400)).toBe('21');
  });

  it('keeps decimals for a slow rate, where rounding would print zero', () => {
    expect(formatRate(420)).toBe('0.42');
  });

  it('prints an exact zero rather than "0.00"', () => {
    expect(formatRate(0)).toBe('0');
  });

  it('groups thousands, because a five-digit rate is unreadable without it', () => {
    expect(formatRate(1_204_000)).toBe('1,204');
  });
});

describe('formatPercent', () => {
  it('unscales: 1500 is 1.5%', () => {
    expect(formatPercent(1500)).toBe('1.5');
  });

  it('keeps a small rate visible rather than rounding it to nothing', () => {
    expect(formatPercent(120)).toBe('0.12');
  });

  it('prints zero as zero', () => {
    expect(formatPercent(0)).toBe('0');
  });
});

describe('formatMillis', () => {
  it('uses milliseconds below a second', () => {
    expect(formatMillis(750)).toBe('750 ms');
  });

  it('switches to seconds above one, where four digits of milliseconds stop reading', () => {
    expect(formatMillis(4200)).toBe('4.20 s');
    expect(formatMillis(64_000)).toBe('64.0 s');
  });
});

describe('freshness', () => {
  it('is live within the pipeline\'s own lag', () => {
    // Fifteen-odd seconds behind is what a perfectly healthy run looks like: a generator publishes
    // every five seconds and the control plane merges every ten. A badge calibrated to the
    // publishing interval alone would read "lagging" for the entire life of every run.
    expect(freshness(7).state).toBe('live');
    expect(freshness(19).state).toBe('live');
  });

  it('says how far behind it is once it lags, rather than still claiming live', () => {
    expect(freshness(40)).toEqual({ state: 'lagging', label: '40s behind' });
  });

  it('stops implying currency entirely once nothing has arrived for a long time', () => {
    // The failure this exists to prevent: numbers from two minutes ago, presented as now, while
    // somebody decides whether to stop the test.
    expect(freshness(150)).toEqual({ state: 'stale', label: 'No update for 2m 30s' });
  });
});

describe('formatAge', () => {
  it('reads in seconds, then minutes, then hours', () => {
    expect(formatAge(45)).toBe('45s ago');
    expect(formatAge(125)).toBe('2m 5s ago');
    expect(formatAge(3720)).toBe('1h 2m ago');
  });
});

describe('sparkline', () => {
  it('draws nothing from a single point, which has no shape', () => {
    expect(sparkline([point()], (p) => p.throughputPerSecondMilli, 100, 20)).toBe('');
  });

  it('draws nothing when every value is zero, rather than a flat line at the top', () => {
    expect(
      sparkline(
        [point({ throughputPerSecondMilli: 0 }), point({ throughputPerSecondMilli: 0 })],
        (p) => p.throughputPerSecondMilli,
        100,
        20,
      ),
    ).toBe('');
  });

  it('anchors the axis at zero, so a small change looks small', () => {
    // 100 then 110. Scaled to its own range this would climb the full height; anchored at zero it
    // moves about a tenth of it — which is what actually happened.
    const path = sparkline(
      [point({ throughputPerSecondMilli: 100 }), point({ throughputPerSecondMilli: 110 })],
      (p) => p.throughputPerSecondMilli,
      100,
      20,
    );

    expect(path).toBe('M0.0,1.8 L100.0,0.0');
  });
});

describe('quietShards', () => {
  it('names the generators that have stopped publishing', () => {
    const view = progress({
      shards: [
        { shardIndex: 0, capturedAt: '', ageSeconds: 120, sampleCount: 5, errorCount: 0, activeUsers: 25 },
        { shardIndex: 1, capturedAt: '', ageSeconds: 3, sampleCount: 900, errorCount: 0, activeUsers: 25 },
      ],
    });

    expect(quietShards(view)).toEqual([0]);
  });

  it('names none when they are all reporting', () => {
    const view = progress({
      shards: [
        { shardIndex: 0, capturedAt: '', ageSeconds: 4, sampleCount: 900, errorCount: 0, activeUsers: 25 },
      ],
    });

    expect(quietShards(view)).toEqual([]);
  });
});

describe('rankedOperations', () => {
  it('puts the slowest first, because that is what somebody watching is looking for', () => {
    const view = progress({
      byOperation: [
        metrics({ name: 'checkout/browse', p95Millis: 90, sampleCount: 900 }),
        metrics({ name: 'checkout/pay', p95Millis: 1500, sampleCount: 100 }),
      ],
    });

    expect(rankedOperations(view).map((operation) => operation.name)).toEqual([
      'checkout/pay',
      'checkout/browse',
    ]);
  });

  it('breaks a tie by volume, so an outlier does not outrank the operation carrying the run', () => {
    const view = progress({
      byOperation: [
        metrics({ name: 'rare', p95Millis: 200, sampleCount: 3 }),
        metrics({ name: 'busy', p95Millis: 200, sampleCount: 5000 }),
      ],
    });

    expect(rankedOperations(view)[0].name).toBe('busy');
  });

  it('does not reorder the caller\'s array', () => {
    const original = [
      metrics({ name: 'a', p95Millis: 10 }),
      metrics({ name: 'b', p95Millis: 900 }),
    ];
    const view = progress({ byOperation: original });

    rankedOperations(view);

    expect(original.map((operation) => operation.name)).toEqual(['a', 'b']);
  });
});

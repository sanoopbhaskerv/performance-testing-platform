import { describe, it, expect } from 'vitest';
import { progressOf, describeStage } from './progress';
import type { Stage } from './api';

/**
 * Progress through a plan, tested by placing a run anywhere in its life.
 *
 * `now` is a parameter rather than a clock read, which is what makes every case below a single
 * assertion instead of a wait. The awkward cases — clock skew, an overrunning run, a plan with a
 * zero-length stage — are exactly the ones a UI gets wrong and nobody notices until a real run
 * behaves badly, which is the worst moment to discover it.
 */

const START = '2026-08-03T12:00:00Z';
const at = (seconds: number) => new Date(Date.parse(START) + seconds * 1000);

/** 30s ramp to 10, 60s hold at 10, 15s down to 0 — the studio's default shape. */
const STAGES: Stage[] = [
  { durationSeconds: 30, targetUsers: 10 },
  { durationSeconds: 60, targetUsers: 10 },
  { durationSeconds: 15, targetUsers: 0 },
];

describe('progressOf', () => {
  it('returns nothing before the run has started', () => {
    expect(progressOf(null, STAGES, at(0))).toBeNull();
  });

  it('returns nothing for a plan with no stages', () => {
    expect(progressOf(START, [], at(10))).toBeNull();
  });

  it('returns nothing for a plan whose stages have no duration', () => {
    expect(progressOf(START, [{ durationSeconds: 0, targetUsers: 5 }], at(10))).toBeNull();
  });

  it('returns nothing for an unparseable start, rather than NaN on screen', () => {
    expect(progressOf('not a timestamp', STAGES, at(10))).toBeNull();
  });

  it.each([
    [0, 0, 105],
    [15, 14, 90],
    [52, 50, 53],
    [105, 100, 0],
  ])('at %is reports %i%% and %is remaining', (elapsed, percent, remaining) => {
    const progress = progressOf(START, STAGES, at(elapsed));

    expect(progress?.percent).toBe(percent);
    expect(progress?.remainingSeconds).toBe(remaining);
    expect(progress?.plannedSeconds).toBe(105);
  });

  it.each([
    [5, 0, 10],
    [30, 1, 10],
    [60, 1, 10],
    [95, 2, 0],
  ])('at %is is in stage %i, targeting %i users', (elapsed, index, targetUsers) => {
    const stage = progressOf(START, STAGES, at(elapsed))?.currentStage;

    expect(stage?.index).toBe(index);
    expect(stage?.targetUsers).toBe(targetUsers);
  });

  it('places each stage at its own offset, so a bar can be drawn against the profile', () => {
    expect(progressOf(START, STAGES, at(0))?.currentStage?.startsAtSeconds).toBe(0);
    expect(progressOf(START, STAGES, at(45))?.currentStage?.startsAtSeconds).toBe(30);
    expect(progressOf(START, STAGES, at(95))?.currentStage?.startsAtSeconds).toBe(90);
  });

  describe('a run that outlasts its plan', () => {
    it('caps at 100% rather than reporting more', () => {
      expect(progressOf(START, STAGES, at(400))?.percent).toBe(100);
    });

    it('flags itself as overrunning, so the UI can stop quoting a percentage', () => {
      // Ramp-down, artifact upload and analysis all sit outside the stage arithmetic, so this is
      // not an error. It is the signal that a number has stopped meaning anything — and showing
      // "100%" for twenty minutes while a run hangs is the confident wrong answer to avoid.
      expect(progressOf(START, STAGES, at(104))?.overrunning).toBe(false);
      expect(progressOf(START, STAGES, at(106))?.overrunning).toBe(true);
    });

    it('has no current stage once the plan is spent', () => {
      expect(progressOf(START, STAGES, at(200))?.currentStage).toBeNull();
    });

    it('never reports negative time remaining', () => {
      expect(progressOf(START, STAGES, at(9999))?.remainingSeconds).toBe(0);
    });
  });

  it('floors elapsed at zero when the clocks disagree', () => {
    // The run's recorded start and the browser's clock belong to different machines. A few seconds
    // of skew must not render as a negative elapsed time or a negative-width bar.
    const progress = progressOf(START, STAGES, at(-5));

    expect(progress?.elapsedSeconds).toBe(0);
    expect(progress?.percent).toBe(0);
  });

  it('skips a zero-length stage rather than getting stuck in it', () => {
    const withEmpty: Stage[] = [
      { durationSeconds: 0, targetUsers: 5 },
      { durationSeconds: 30, targetUsers: 10 },
    ];

    expect(progressOf(START, withEmpty, at(1))?.currentStage?.index).toBe(1);
  });
});

describe('describeStage', () => {
  it.each([
    [0, 'Building up'],
    [1, 'Holding steady'],
    [2, 'Winding down'],
  ])('describes stage %i as %s', (index, expected) => {
    expect(describeStage(STAGES, index)).toBe(expected);
  });

  it('calls a partial reduction easing off, not winding down', () => {
    const stages: Stage[] = [
      { durationSeconds: 30, targetUsers: 100 },
      { durationSeconds: 30, targetUsers: 40 },
    ];

    expect(describeStage(stages, 1)).toBe('Easing off');
  });

  it('falls back rather than throwing on an index that is not there', () => {
    expect(describeStage(STAGES, 99)).toBe('Running');
  });
});

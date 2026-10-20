import type { Stage } from './api';

/**
 * How far through its plan a run is.
 *
 * <p>Derived entirely from data the platform already persists — the plan's stage arithmetic and the
 * moment the run started — so this needs no change to any runner and no new measurement. That is
 * what makes it affordable in release 1 while live metrics (which do need a runner change) wait for
 * S7.
 *
 * <h2>An estimate, and it says so</h2>
 * This is what the plan <em>describes</em>, not what a generator has reported. A run can overrun:
 * image pulls, a slow ramp, a target that will not answer. So the model never silently pins itself
 * at 100% — {@link RunProgress#overrunning} exists precisely so the UI can stop claiming a
 * percentage and say the run is taking longer than planned. Showing "100%" for twenty minutes while
 * a run hangs is the kind of confident wrong answer this platform keeps refusing to give.
 */

export interface StageProgress {
  index: number;
  /** Zero-based position within the plan, and what the stage asks for. */
  targetUsers: number;
  targetRate?: number | null;
  durationSeconds: number;
  /** Seconds from the run's start at which this stage begins. */
  startsAtSeconds: number;
}

export interface RunProgress {
  elapsedSeconds: number;
  /** Total wall-clock the stages describe. Zero when the plan declares none. */
  plannedSeconds: number;
  /** 0–100, clamped. Never exceeds 100 even when the run has overrun. */
  percent: number;
  /** Seconds still to go by the plan, floored at zero. */
  remainingSeconds: number;
  /** The stage the run should be in now, or null once the plan's time is spent. */
  currentStage: StageProgress | null;
  /**
   * True when the run has been going longer than its stages describe.
   *
   * <p>Not an error — ramp-down, artifact upload and analysis all sit outside the stage arithmetic.
   * It is the signal to stop showing a percentage that has stopped meaning anything.
   */
  overrunning: boolean;
}

/**
 * Progress through a plan's stages.
 *
 * @param startedAt when the run began, as the API reports it, or null if it has not
 * @param stages    the plan's stages, in order
 * @param now       the current instant, passed in rather than read, so this stays a pure function
 *                  and a test can place a run anywhere in its life without waiting
 * @returns null when there is nothing to show — no start, or a plan with no stages
 */
export function progressOf(
  startedAt: string | null,
  stages: Stage[],
  now: Date,
): RunProgress | null {
  if (!startedAt || stages.length === 0) return null;

  const started = Date.parse(startedAt);
  if (Number.isNaN(started)) return null;

  const plannedSeconds = stages.reduce((total, stage) => total + Math.max(0, stage.durationSeconds), 0);
  if (plannedSeconds <= 0) return null;

  // Clamped at zero: the control plane's clock and the run's recorded start belong to different
  // machines, and a few seconds of skew must not render as a negative elapsed time.
  const elapsedSeconds = Math.max(0, Math.round((now.getTime() - started) / 1000));

  let startsAtSeconds = 0;
  let currentStage: StageProgress | null = null;
  for (let index = 0; index < stages.length; index++) {
    const durationSeconds = Math.max(0, stages[index].durationSeconds);
    if (elapsedSeconds < startsAtSeconds + durationSeconds) {
      currentStage = {
        index,
        targetUsers: stages[index].targetUsers,
        targetRate: stages[index].targetRate,
        durationSeconds,
        startsAtSeconds,
      };
      break;
    }
    startsAtSeconds += durationSeconds;
  }

  return {
    elapsedSeconds,
    plannedSeconds,
    percent: Math.min(100, Math.round((elapsedSeconds / plannedSeconds) * 100)),
    remainingSeconds: Math.max(0, plannedSeconds - elapsedSeconds),
    currentStage,
    overrunning: elapsedSeconds > plannedSeconds,
  };
}

/**
 * What a stage is doing, in a consumer's words.
 *
 * <p>Derived from the shape rather than from a name, because the platform's stages carry no names
 * yet (`LoadStage` gains one in S10 for hybrid coordination). Comparing a stage's target against the
 * one before it is enough to say whether load is climbing, holding or winding down — and those three
 * words are what someone watching actually wants to know.
 */
export function describeStage(stages: Stage[], index: number): string {
  const stage = stages[index];
  if (!stage) return 'Running';

  const target = stage.targetRate ?? stage.targetUsers;
  if (target === 0) return 'Winding down';

  const previous = index === 0 ? 0 : stages[index - 1].targetRate ?? stages[index - 1].targetUsers;
  if (target > previous) return 'Building up';
  if (target < previous) return 'Easing off';
  return 'Holding steady';
}

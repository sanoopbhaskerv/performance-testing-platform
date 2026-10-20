import { Clock } from 'lucide-react';
import type { RunReport, TestRun } from './api';
import { isActive } from './api';
import { describeStage, progressOf } from './progress';

/**
 * Where a run is in its plan.
 *
 * <p>Answers the question somebody actually has while waiting — *how much longer?* — which the
 * state machine alone does not. "Running" is true for twenty minutes and says nothing about whether
 * to keep watching.
 *
 * <p>Everything here is derived from data the platform already stores: the plan's stages and the
 * instant the run started. No runner reports it, nothing new is measured, and no image changes.
 * Live throughput, latency and error rate are a different thing entirely — they need a runner that
 * publishes progress, which is S7.
 *
 * <h2>It stops claiming a percentage when the percentage stops meaning anything</h2>
 * A plan says how long the stages take. It does not know about image pulls, a slow ramp, or a
 * target that will not answer. Once a run outlasts its plan the bar holds at full and the label
 * changes to say so, rather than showing "100%" beside a run that is still going — a progress bar
 * that lies at the end is worse than none at all.
 */
export default function RunProgressBar({
  run,
  report,
}: {
  run: TestRun;
  report: RunReport | null;
}) {
  // Only while it is actually generating load. A queued run has not started, and a finished one has
  // a report — neither is helped by a bar, and one showing 100% next to a failed run reads as
  // success.
  if (!isActive(run.status)) return null;

  const stages = report?.test?.workloads?.[0]?.stages ?? [];
  const progress = progressOf(run.startedAt, stages, new Date());
  if (!progress) return null;

  const label = progress.overrunning
    ? 'Taking longer than planned'
    : progress.currentStage
      ? describeStage(stages, progress.currentStage.index)
      : 'Finishing up';

  const remaining = progress.overrunning
    ? 'past its planned finish'
    : `about ${spoken(progress.remainingSeconds)} to go`;

  return (
    <div className="run-progress">
      <div className="run-progress-head">
        <span className="run-progress-label">
          <Clock size={14} aria-hidden /> {label}
          {progress.currentStage && !progress.overrunning && (
            <span className="muted">
              {' '}
              — {progress.currentStage.targetRate != null
                ? `${progress.currentStage.targetRate.toLocaleString()} requests/second`
                : `${progress.currentStage.targetUsers.toLocaleString()} users`}
            </span>
          )}
        </span>
        <span className="muted">{remaining}</span>
      </div>

      {/*
        A progress bar with an accessible role, so the figure is available without seeing the fill.
        `aria-valuetext` carries the sentence rather than the bare number: "62%" alone tells a screen
        reader user less than "62%, about 40 seconds to go".
      */}
      <div
        className={progress.overrunning ? 'run-progress-track overrun' : 'run-progress-track'}
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={progress.percent}
        aria-valuetext={`${progress.percent}%, ${remaining}`}
        aria-label="Progress through the test plan"
      >
        <div className="run-progress-fill" style={{ width: `${progress.percent}%` }} />
      </div>

      <p className="run-progress-note muted">
        {spoken(progress.elapsedSeconds)} elapsed of a planned {spoken(progress.plannedSeconds)}.
        {progress.overrunning
          ? ' Ramp-down, uploading results and analysis all happen after the stages finish, so this is normal for a short while.'
          : ''}
      </p>
    </div>
  );
}

/** A duration for a sentence, not a field: "1m 30s" reads worse here than "1 minute 30 seconds". */
function spoken(seconds: number): string {
  if (seconds < 60) return `${seconds} second${seconds === 1 ? '' : 's'}`;
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  const minutePart = `${minutes} minute${minutes === 1 ? '' : 's'}`;
  return rest === 0 ? minutePart : `${minutePart} ${rest} second${rest === 1 ? '' : 's'}`;
}

import type { StudioDraft, TestType } from './draft';
import { includedJourneys } from './draft';

/**
 * The studio's steps, and what each one needs before it is finished.
 *
 * Kept out of the components so "can this test be saved yet" is a function rather than a condition
 * scattered across six render bodies. The studio uses it twice — to decide whether *Next* is
 * available, and to tell someone what is still missing — and those two must never disagree: a
 * disabled button with no explanation is the most common way a wizard strands somebody.
 */

export type StepId =
  | 'sources'
  | 'about'
  | 'refine'
  | 'rules'
  | 'load'
  | 'objectives'
  | 'validate'
  | 'review';

export interface Step {
  id: StepId;
  /** The question the step answers, in the consumer's words. */
  title: string;
  /** One line under the title. Says what the step is for, not what it contains. */
  hint: string;
}

const API_LOAD_STEPS: Step[] = [
  { id: 'sources', title: 'Journeys', hint: 'What requests should the test send?' },
  { id: 'about', title: 'About', hint: 'What is this test called, and what does it point at?' },
  { id: 'refine', title: 'Review journeys', hint: 'Keep what matters, drop the noise.' },
  { id: 'rules', title: 'Rules', hint: 'Correlate values, check responses and add pauses.' },
  { id: 'load', title: 'Load', hint: 'How many people, and for how long?' },
  { id: 'objectives', title: 'Objectives', hint: 'What counts as fast enough?' },
  { id: 'validate', title: 'Validate', hint: 'Try one journey before booking load.' },
  { id: 'review', title: 'Save and run', hint: 'Check the test, then start it.' },
];

const WEB_EXPERIENCE_STEPS: Step[] = [
  { id: 'sources', title: 'Journey', hint: 'Which pages should the test measure?' },
  { id: 'about', title: 'About', hint: 'What is this test called, and what does it point at?' },
  { id: 'refine', title: 'Review journey', hint: 'Check what will be measured.' },
  { id: 'rules', title: 'Rules', hint: 'Correlate values, check responses and add pauses.' },
  { id: 'load', title: 'Browsers', hint: 'How many real browsers, and how many times?' },
  { id: 'objectives', title: 'Objectives', hint: 'What counts as fast enough?' },
  { id: 'validate', title: 'Validate', hint: 'Try one journey before booking load.' },
  { id: 'review', title: 'Save and run', hint: 'Check the test, then start it.' },
];

export function stepsFor(testType: TestType): Step[] {
  return testType === 'web-experience' ? WEB_EXPERIENCE_STEPS : API_LOAD_STEPS;
}

/**
 * What is stopping this step from being complete, or null when nothing is.
 *
 * Phrased as the thing to do, never as the rule that was broken. "Add at least one journey" is
 * actionable; "journeys.length must be greater than 0" is a stack trace with better manners.
 */
export function blockerFor(step: StepId, draft: StudioDraft): string | null {
  switch (step) {
    case 'sources':
      return draft.journeys.length === 0
        ? 'Import a source, or add a request by hand, so the test has something to send.'
        : null;

    /**
     * Asked early because the Validator needs it.
     *
     * These used to live on the last screen, which put them *after* Validate — and the Validator
     * resolves its target from team, application and environment, so it failed with
     * "metadata.team is required" on a draft the studio had said nothing about. A step cannot
     * depend on an answer the wizard has not asked for yet.
     */
    case 'about':
      if (!draft.name.trim()) return 'The test needs a name.';
      if (!draft.team.trim()) return 'The test needs a team — it decides where the traffic may go.';
      if (!draft.application.trim()) return 'The test needs an application to point at.';
      return null;

    case 'refine':
      return includedJourneys(draft).length === 0
        ? 'Keep at least one journey. A test with none would send nothing and measure nothing.'
        : null;

    case 'rules':
      return null;

    case 'load':
      if (draft.load.model === 'rate') {
        if (draft.load.peakRate < 1) {
          return 'The test needs at least one request per second.';
        }
        // Not a nicety. A rate model with no ceiling accumulates in-flight requests whenever the
        // target slows, until the load generator fails and the run reports that as the target's
        // failure — so the platform refuses the specification outright, and this says so before
        // somebody reaches Save and meets the refusal there.
        if (draft.load.maxConcurrency < 1) {
          return 'A rate test needs a limit on how many requests can be waiting at once.';
        }
      } else if (draft.load.peakUsers < 1) {
        return 'The test needs at least one user.';
      }
      if (
        draft.load.rampUpSeconds + draft.load.steadySeconds + draft.load.rampDownSeconds <= 0
      ) {
        return 'The test needs a duration. Give it at least a steady-state period.';
      }
      return null;

    case 'objectives':
      // Deliberately not required. A test with no objectives still measures, and forcing someone to
      // invent a threshold before their first run produces a number they made up and then trust.
      return null;

    case 'validate':
      return null;

    case 'review':
      // Nothing of its own: everything it needs was asked for on About, and the studio reports
      // outstanding blockers across all steps rather than only the current one.
      return null;
  }
}

/** Every blocker between here and saving, so the studio can say what is left rather than only what is next. */
export function outstandingBlockers(draft: StudioDraft): Array<{ step: StepId; blocker: string }> {
  return stepsFor(draft.testType)
    .map((step) => ({ step: step.id, blocker: blockerFor(step.id, draft) }))
    .filter((entry): entry is { step: StepId; blocker: string } => entry.blocker !== null);
}

/** True when the draft could be saved right now. */
export function isSaveable(draft: StudioDraft): boolean {
  return outstandingBlockers(draft).length === 0;
}

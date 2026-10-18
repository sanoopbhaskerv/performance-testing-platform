import { useEffect, useMemo, useState } from 'react';
import { AlertCircle, ArrowLeft, ArrowRight, Check, Gauge, MonitorSmartphone } from 'lucide-react';
import type { V2Client } from '../api';
import { describeError } from '../api';
import type { Journey, StudioDraft, TestType } from './draft';
import { newDraft } from './draft';
import type { StepId } from './steps';
import { blockerFor, stepsFor } from './steps';
import SourcesStep from './SourcesStep';
import { BrowserSourceStep, BrowserCohortStep, BrowserCheckStep } from './BrowserSteps';
import RefineStep from './RefineStep';
import {
  AboutStep,
  LoadStep,
  ObjectivesStep,
  ReviewStep,
  RulesStep,
  ValidateStep,
} from './StepScreens';
import './studio.css';

/**
 * The studio: one place to describe a performance test without knowing how one is written.
 *
 * <p>The bar this is built to (PLAN.md): someone with no performance testing experience should be
 * able to use it and trust what it tells them. That is stricter than "has a UI", and it shows up as
 * three rules applied everywhere — every field is named for what a consumer knows, every default is
 * a working value, and nothing is ever disabled without saying why beside it.
 *
 * <p>What it produces is not a second way to describe a test. It is the same canonical
 * specification the REST API and YAML in Git produce (CLAUDE.md §32), emitted by
 * {@link toSpecification} and proven byte-identical to a hand-written document by its own tests.
 */
export default function Studio({
  client,
  team,
  seed,
  onSaved,
  onCancel,
}: {
  client: V2Client;
  team: string;
  /**
   * Journeys the studio should open with, from a recording.
   *
   * When present the studio skips both the start screen and the sources screen and opens on Refine,
   * because both questions are already answered: the person chose what to record and then recorded
   * it. Asking them to pick a test type and a source afterwards would be asking them to repeat
   * themselves.
   */
  seed?: {
    testType: TestType;
    journeys: Journey[];
    /** Carried from the recording, so About opens answered rather than blank. */
    team?: string;
    application?: string;
    environment?: string;
    name?: string;
    /**
     * The recording itself, kept so the Validator can be run against it.
     *
     * Without it the Validator can only report that an extraction produced a value. With it, it can
     * say whether each accepted correlation carried a *different* value from the one the recording
     * captured — which is the difference between a rule that works and a rule that was applied to
     * nothing and passed because the captured literal had not expired yet.
     */
    recordingSessionId?: string;
  } | null;
  /** Called with the created definition's name once it exists, so the console can refresh. */
  onSaved: (name: string) => void;
  onCancel: () => void;
}) {
  const [draft, setDraft] = useState<StudioDraft | null>(
    seed
      ? {
          ...newDraft(seed.testType, seed.team ?? team),
          journeys: seed.journeys,
          // A recording already knows what it was pointed at. Asking again would be asking someone
          // to repeat the choice they made when they pressed "Start guarded recording".
          application: seed.application ?? '',
          environment: seed.environment ?? 'performance',
          name: seed.name ?? '',
          recordingSessionId: seed.recordingSessionId ?? null,
        }
      : null,
  );
  const [knownTeams, setKnownTeams] = useState<string[]>([]);

  // Suggestions for the team field. Best effort: a deployment that will not list its teams simply
  // gets a plain text box, which is what this was before.
  useEffect(() => {
    let cancelled = false;
    client
      .listTeamNames()
      .then((teams) => {
        if (!cancelled) {
          setKnownTeams(teams);
          if (teams.length === 1) setDraft(current => current && !current.team ? { ...current, team: teams[0] } : current);
        }
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [client]);
  const [stepId, setStepId] = useState<StepId>(seed ? 'refine' : 'sources');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const steps = useMemo(() => (draft ? stepsFor(draft.testType) : []), [draft]);
  const index = steps.findIndex((step) => step.id === stepId);
  const step = steps[index];
  const blocker = draft && step ? blockerFor(step.id, draft) : null;

  function begin(testType: TestType) {
    setDraft(newDraft(testType, team));
    setStepId(testType === 'web-experience' ? 'about' : 'sources');
    setError(null);
  }

  async function save(document: string) {
    if (!draft) return;
    setSaving(true);
    setError(null);
    try {
      const definition = await client.createDefinition(document);
      onSaved(definition.name);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSaving(false);
    }
  }

  if (!draft) {
    return <StartScreen onChoose={begin} onCancel={onCancel} />;
  }

  return (
    <div className="studio">
      {/*
        An ordered list because the order is the meaning: it shows someone the whole job before they
        start it. Steps already passed stay reachable — a wizard that will not let you go back to
        check something makes people restart rather than revise.
      */}
      <nav aria-label="Steps">
        <ol className="studio-steps">
          {steps.map((candidate, position) => {
            const state =
              position === index ? 'current' : position < index ? 'done' : 'ahead';
            return (
              <li key={candidate.id}>
                <button
                  type="button"
                  className={`studio-step studio-step-${state}`}
                  // Reachable once seen. Jumping ahead past an unmet blocker would land somebody on
                  // a step that cannot be completed, with the reason two screens behind them.
                  disabled={position > index}
                  aria-current={position === index ? 'step' : undefined}
                  onClick={() => setStepId(candidate.id)}
                >
                  <span className="studio-step-index" aria-hidden>
                    {position + 1}
                  </span>
                  <span>
                    <span className="sr-only">Step {position + 1} of {steps.length}: </span>
                    {candidate.title}
                  </span>
                </button>
              </li>
            );
          })}
        </ol>
      </nav>

      <header className="studio-head">
        <h2>{step.title}</h2>
        <p>{step.hint}</p>
      </header>

      {error && (
        <div className="banner banner-bad" role="alert">
          <AlertCircle size={16} /> {error}
        </div>
      )}

      <div className="studio-body">
        {step.id === 'sources' && (
          draft.testType === 'web-experience'
            ? <BrowserSourceStep client={client} draft={draft} onChange={setDraft} />
            : <SourcesStep client={client} draft={draft} onChange={setDraft} />
        )}
        {step.id === 'about' && (
          <AboutStep client={client} draft={draft} onChange={setDraft} knownTeams={knownTeams} />
        )}
        {step.id === 'refine' && <RefineStep draft={draft} onChange={setDraft} />}
        {step.id === 'rules' && <RulesStep draft={draft} onChange={setDraft} />}
        {step.id === 'load' && (draft.testType === 'web-experience'
          ? <BrowserCohortStep draft={draft} onChange={setDraft} />
          : <LoadStep draft={draft} onChange={setDraft} />)}
        {step.id === 'objectives' && <ObjectivesStep draft={draft} onChange={setDraft} />}
        {step.id === 'validate' && (
          draft.testType === 'web-experience'
            ? <BrowserCheckStep client={client} draft={draft} onChange={setDraft} />
            : <ValidateStep client={client} draft={draft} onChange={setDraft} />
        )}
        {step.id === 'review' && (
          <ReviewStep client={client} draft={draft} onChange={setDraft} onSave={save} saving={saving} />
        )}
      </div>

      <footer className="studio-foot">
        <button
          type="button"
          className="btn btn-quiet"
          onClick={() => (index === 0 ? onCancel() : setStepId(steps[index - 1].id))}
        >
          <ArrowLeft size={14} /> {index === 0 ? 'Cancel' : 'Back'}
        </button>

        <div className="studio-foot-spacer" />

        {/*
          The reason sits beside the button, not inside a tooltip on it. A disabled control whose
          explanation is hidden behind a hover is the single most common way a wizard strands
          somebody — and it is invisible on a touch screen entirely.
        */}
        {blocker && (
          <p className="studio-blocker" role="status">
            <AlertCircle size={15} aria-hidden /> {blocker}
          </p>
        )}

        {step.id !== 'review' && (
          <button
            type="button"
            className="btn"
            disabled={blocker !== null}
            onClick={() => setStepId(steps[index + 1].id)}
          >
            Next <ArrowRight size={14} />
          </button>
        )}
      </footer>
    </div>
  );
}

/**
 * The first screen: which question is being asked.
 *
 * Each card leads with the question rather than the technology. Somebody who has never done this
 * does not know whether they want "protocol load" — they know whether they are asking *can it take
 * the traffic* or *is the page slow*, and those are genuinely different tests with different
 * engines, different costs and different evidence.
 */
function StartScreen({
  onChoose,
  onCancel,
}: {
  onChoose: (testType: TestType) => void;
  onCancel: () => void;
}) {
  return (
    <div className="studio">
      <header className="studio-head">
        <h2>What do you want to find out?</h2>
        <p>Both kinds of test run on the same platform and produce the same kind of report.</p>
      </header>

      <div className="studio-types">
        <button type="button" className="studio-type" onClick={() => onChoose('api-load')}>
          <Gauge size={22} aria-hidden />
          <h3>Test an API</h3>
          <p className="studio-type-question">“Can these endpoints handle the load?”</p>
          <p>
            Sends real traffic to one or more APIs and measures response time, errors and
            throughput. Bring an OpenAPI document, a Postman collection, a cURL command or a
            recording of real traffic.
          </p>
        </button>

        <button type="button" className="studio-type" onClick={() => onChoose('web-experience')}>
          <MonitorSmartphone size={22} aria-hidden />
          <h3>Test web performance</h3>
          <p className="studio-type-question">“Is the page fast for a real person?”</p>
          <p>
            Opens your application in real browsers and measures what a visitor actually
            experiences — how quickly the page appears, responds and settles.
          </p>
        </button>
      </div>

      <footer className="studio-foot">
        <button type="button" className="btn btn-quiet" onClick={onCancel}>
          <ArrowLeft size={14} /> Cancel
        </button>
        <div className="studio-foot-spacer" />
        <p className="studio-hint">
          <Check size={14} aria-hidden /> You can change everything later — nothing runs until you
          say so.
        </p>
      </footer>
    </div>
  );
}

import { useEffect, useMemo, useState } from 'react';
import {
  AlertCircle,
  AlertTriangle,
  CheckCircle2,
  Clock3,
  Download,
  Loader2,
  Play,
  Save,
} from 'lucide-react';
import type { ValidationFinding, ValidatorResult, V2Client } from '../api';
import { describeError } from '../api';
import type { ObjectiveDraft, ObjectiveMetric, StudioDraft } from './draft';
import { includedJourneys, toResourceName } from './draft';
import { downloadSpecification } from './download';
import CorrelationProofs from './CorrelationProofs';
import {
  approximateDuration,
  toSpecification,
  totalDurationSeconds,
  IncompleteDraftError,
} from './specification';

/** The shape every step shares: it edits the draft and hands the whole thing back. */
interface StepProps {
  draft: StudioDraft;
  onChange: (draft: StudioDraft) => void;
}

/**
 * Correlation, response checks and pauses.
 *
 * The key rule is the one in the title: nothing is applied silently. Imported suggestions arrive
 * as evidence and stay pending until a person accepts or rejects them.
 */
export function RulesStep({ draft, onChange }: StepProps) {
  const journeys = includedJourneys(draft);
  const suggestions = journeys.flatMap((journey) =>
    journey.correlationSuggestions.map((suggestion) => ({ journey, suggestion })),
  );

  const updateSuggestion = (journeyName: string, id: string, decision: 'accepted' | 'rejected') =>
    onChange({
      ...draft,
      journeys: draft.journeys.map((journey) =>
        journey.name === journeyName
          ? {
              ...journey,
              correlationSuggestions: journey.correlationSuggestions.map((suggestion) =>
                suggestion.id === id ? { ...suggestion, decision } : suggestion,
              ),
            }
          : journey,
      ),
    });

  const updateStep = (
    journeyName: string,
    stepName: string,
    patch: Partial<StudioDraft['journeys'][number]['steps'][number]>,
  ) =>
    onChange({
      ...draft,
      journeys: draft.journeys.map((journey) =>
        journey.name === journeyName
          ? {
              ...journey,
              steps:
                journey.steps.length > 0
                  ? journey.steps.map((step) =>
                      step.name === stepName ? { ...step, ...patch } : step,
                    )
                  : [
                      {
                        name: journey.name,
                        method: journey.method ?? 'GET',
                        path: journey.path ?? '/',
                        headers: journey.headers,
                        query: journey.query,
                        body: journey.body,
                        expectStatus: false,
                        expectedStatus: null,
                        thinkTimeSeconds: 0,
                        ...patch,
                      },
                    ],
            }
          : journey,
      ),
    });

  return (
    <>
      <section className="studio-section">
        <h3>Correlation</h3>
        <p className="studio-hint">
          The platform can point out values that appeared in a response and were reused later. It
          does not wire them into the test until you accept the rule.
        </p>

        {suggestions.length === 0 ? (
          <p className="studio-summary">
            No correlation suggestions were found in the imported journeys. You can still write
            correlation in YAML, and the Validator step will exercise it before a load run.
          </p>
        ) : (
          <div className="rule-list">
            {suggestions.map(({ journey, suggestion }) => (
              <article className="rule-card" key={suggestion.id}>
                <div>
                  <h4>{suggestion.variableName}</h4>
                  <p className="studio-hint">
                    From <strong>{suggestion.extraction.stepName}</strong>{' '}
                    ({suggestion.extraction.from} {suggestion.extraction.expression}) with value{' '}
                    <code>{suggestion.extraction.valuePreview}</code>.
                  </p>
                  <ul className="rule-evidence">
                    {suggestion.replacements.map((replacement, index) => (
                      <li key={index}>
                        Used by <strong>{replacement.stepName}</strong> in {replacement.location}
                        {replacement.name ? ` "${replacement.name}"` : ''}.
                      </li>
                    ))}
                  </ul>
                </div>
                <div className="rule-actions" aria-label={`Decision for ${suggestion.variableName}`}>
                  <button
                    type="button"
                    className={suggestion.decision === 'accepted' ? 'btn' : 'btn btn-quiet'}
                    onClick={() => updateSuggestion(journey.name, suggestion.id, 'accepted')}
                  >
                    Accept
                  </button>
                  <button
                    type="button"
                    className={suggestion.decision === 'rejected' ? 'btn' : 'btn btn-quiet'}
                    onClick={() => updateSuggestion(journey.name, suggestion.id, 'rejected')}
                  >
                    Reject
                  </button>
                </div>
              </article>
            ))}
          </div>
        )}
      </section>

      <section className="studio-section">
        <h3>Verification and think time</h3>
        <p className="studio-hint">
          A fast error page is still an error. Add status checks where you know the expected result,
          and add pauses where a person would stop before the next request.
        </p>

        <div className="table-scroll">
          <table className="metrics">
            <caption className="sr-only">Per-step checks and pauses</caption>
            <thead>
              <tr>
                <th scope="col">Journey</th>
                <th scope="col">Step</th>
                <th scope="col">Status check</th>
                <th scope="col">Pause after</th>
              </tr>
            </thead>
            <tbody>
              {journeys.flatMap((journey) =>
                effectiveSteps(journey).map((step) => (
                  <tr key={`${journey.name}:${step.name}`}>
                    <td>{journey.name}</td>
                    <td>
                      <code>
                        {step.method} {step.path}
                      </code>
                    </td>
                    <td>
                      <label className="inline-control">
                        <input
                          type="checkbox"
                          checked={step.expectStatus}
                          onChange={(event) =>
                            updateStep(journey.name, step.name, {
                              expectStatus: event.target.checked,
                              expectedStatus: step.expectedStatus ?? 200,
                            })
                          }
                        />
                        Expect
                      </label>{' '}
                      <input
                        type="number"
                        min={100}
                        max={599}
                        value={step.expectedStatus ?? 200}
                        disabled={!step.expectStatus}
                        onChange={(event) =>
                          updateStep(journey.name, step.name, {
                            expectedStatus: Number(event.target.value),
                          })
                        }
                        aria-label={`Expected status for ${journey.name} ${step.name}`}
                        style={{ width: '5rem' }}
                      />
                    </td>
                    <td>
                      <label className="inline-control">
                        <Clock3 size={14} aria-hidden />
                        <input
                          type="number"
                          min={0}
                          value={step.thinkTimeSeconds}
                          onChange={(event) =>
                            updateStep(journey.name, step.name, {
                              thinkTimeSeconds: Number(event.target.value),
                            })
                          }
                          aria-label={`Pause after ${journey.name} ${step.name}`}
                          style={{ width: '5rem' }}
                        />{' '}
                        seconds
                      </label>
                    </td>
                  </tr>
                )),
              )}
            </tbody>
          </table>
        </div>
      </section>
    </>
  );
}

function effectiveSteps(journey: StudioDraft['journeys'][number]) {
  return journey.steps.length > 0
    ? journey.steps
    : [
        {
          name: journey.name,
          method: journey.method ?? 'GET',
          path: journey.path ?? '/',
          headers: journey.headers,
          query: journey.query,
          body: journey.body,
          expectStatus: false,
          expectedStatus: null,
          thinkTimeSeconds: 0,
        },
      ];
}

/**
 * How much load, and for how long.
 *
 * Asked as peak users plus three durations, not as a stage list. Somebody who has never run a
 * performance test knows roughly how many people they expect and how long they want to hold it; they
 * do not know that a stage list has to end at zero users, and a form that asked for one would be
 * teaching JMeter rather than asking a question.
 */
export function LoadStep({ draft, onChange }: StepProps) {
  const set = (patch: Partial<StudioDraft['load']>) =>
    onChange({ ...draft, load: { ...draft.load, ...patch } });

  const total = totalDurationSeconds(draft);
  const browser = draft.testType === 'web-experience';
  // A browser cohort is a fixed number of real browsers measuring the experience. Asking for a
  // rate of browser journeys would be asking for a number of machines, not a load shape.
  const rate = !browser && draft.load.model === 'rate';

  // Little's law, which is the whole of what the ceiling means: sustaining R requests a second with
  // at most C in flight requires every response inside C/R seconds. Shown rather than validated
  // silently, because only the person typing it knows how fast their endpoint is.
  const impliedBudgetMillis =
    draft.load.peakRate > 0
      ? Math.round((draft.load.maxConcurrency * 1000) / draft.load.peakRate)
      : 0;

  return (
    <>
      {!browser && (
        <fieldset className="studio-field studio-choice">
          <legend>What are you holding steady?</legend>
          <label>
            <input
              type="radio"
              name="load-model"
              checked={draft.load.model === 'users'}
              onChange={() => set({ model: 'users' })}
            />
            <span>
              <strong>A number of people</strong>
              <span className="studio-hint">
                Each one waits for a response before sending the next request, so if the system
                slows down the test sends less. This is what most load tests want.
              </span>
            </span>
          </label>
          <label>
            <input
              type="radio"
              name="load-model"
              checked={rate}
              onChange={() => set({ model: 'rate' })}
            />
            <span>
              <strong>A number of requests per second</strong>
              <span className="studio-hint">
                Requests keep arriving at the rate you ask for whatever the system does with them.
                Use this for traffic the system does not control — a queue, a batch, a public
                endpoint — or to ask whether it can sustain a specific rate.
              </span>
            </span>
          </label>
        </fieldset>
      )}

      <div className="studio-grid">
        {rate ? (
          <>
            <div className="studio-field">
              <label htmlFor="peak-rate">Requests per second</label>
              <input
                id="peak-rate"
                type="number"
                min={1}
                value={draft.load.peakRate}
                onChange={(e) => set({ peakRate: Number(e.target.value) })}
                aria-describedby="peak-rate-hint"
              />
              <p className="studio-hint" id="peak-rate-hint">
                The rate at the busiest point. The test builds up to it and holds it.
              </p>
            </div>

            <div className="studio-field">
              <label htmlFor="max-concurrency">At most this many at once</label>
              <input
                id="max-concurrency"
                type="number"
                min={1}
                value={draft.load.maxConcurrency}
                onChange={(e) => set({ maxConcurrency: Number(e.target.value) })}
                aria-describedby="max-concurrency-hint"
              />
              <p className="studio-hint" id="max-concurrency-hint">
                A safety limit on requests waiting for an answer. If the system stalls, requests
                pile up — without a limit the load generator runs out of memory and the test
                reports its own failure as the system&apos;s.
                {impliedBudgetMillis > 0 && (
                  <>
                    {' '}
                    At {draft.load.peakRate}/s this one is reached if responses take longer than{' '}
                    <strong>{impliedBudgetMillis} ms</strong>; past that, the limit rather than your
                    rate is what the test measures.
                  </>
                )}
              </p>
            </div>
          </>
        ) : (
        <div className="studio-field">
          <label htmlFor="peak-users">{browser ? 'Browsers at once' : 'People at once'}</label>
          <input
            id="peak-users"
            type="number"
            min={1}
            max={browser ? 20 : 1000}
            value={draft.load.peakUsers}
            onChange={(e) => set({ peakUsers: Number(e.target.value) })}
            aria-describedby="peak-users-hint"
          />
          <p className="studio-hint" id="peak-users-hint">
            {browser
              ? /*
                 * The ceiling is explained, not merely enforced. A greyed-out field teaches nothing;
                 * a sentence about why a browser costs a hundred times what a virtual user costs is
                 * the thing that stops somebody asking for 500 and concluding the tool is broken.
                 */
                'Each one is a real browser using about a gigabyte of memory, so this is capped at ' +
                '20. Browsers measure the experience; they are not how load is generated.'
              : 'The number of simultaneous users the test simulates at its busiest.'}
          </p>
        </div>
        )}

        <div className="studio-field">
          <label htmlFor="ramp-up">Ramp up over (seconds)</label>
          <input
            id="ramp-up"
            type="number"
            min={0}
            value={draft.load.rampUpSeconds}
            onChange={(e) => set({ rampUpSeconds: Number(e.target.value) })}
            aria-describedby="ramp-up-hint"
          />
          <p className="studio-hint" id="ramp-up-hint">
            Building up gradually rather than all at once, which is both more realistic and easier
            on the system under test.
          </p>
        </div>

        <div className="studio-field">
          <label htmlFor="steady">Hold for (seconds)</label>
          <input
            id="steady"
            type="number"
            min={0}
            value={draft.load.steadySeconds}
            onChange={(e) => set({ steadySeconds: Number(e.target.value) })}
            aria-describedby="steady-hint"
          />
          <p className="studio-hint" id="steady-hint">
            The part that matters. Measurements taken while load is still climbing describe the ramp,
            not the system.
          </p>
        </div>

        <div className="studio-field">
          <label htmlFor="ramp-down">Ramp down over (seconds)</label>
          <input
            id="ramp-down"
            type="number"
            min={0}
            value={draft.load.rampDownSeconds}
            onChange={(e) => set({ rampDownSeconds: Number(e.target.value) })}
            aria-describedby="ramp-down-hint"
          />
          <p className="studio-hint" id="ramp-down-hint">
            Winding down rather than stopping dead, so the last samples are not taken mid-teardown.
          </p>
        </div>
      </div>

      <p className="studio-summary">
        This test will run for about <strong>{approximateDuration(total)}</strong>, reaching{' '}
        <strong>
          {rate
            ? `${draft.load.peakRate} requests per second`
            : `${draft.load.peakUsers} ${browser ? 'browsers' : 'people'}`}
        </strong>{' '}
        at its busiest
        {rate && <> , with at most {draft.load.maxConcurrency} waiting for an answer at any moment</>}
        .
      </p>
    </>
  );
}

/** What each metric is called on screen, and what its number means. */
const METRICS: Array<{ metric: ObjectiveMetric; label: string; unit: string; hint: string }> = [
  {
    metric: 'http.response.time',
    label: 'Response time',
    unit: 'ms',
    hint: 'How long requests take. Judged at a percentile rather than an average, because an average hides the slow tail that people actually notice.',
  },
  {
    metric: 'http.error.rate',
    label: 'Error rate',
    unit: '%',
    hint: 'The share of requests that failed.',
  },
  {
    metric: 'http.throughput',
    label: 'Throughput',
    unit: 'per second',
    hint: 'How many requests completed per second.',
  },
];

/**
 * What counts as fast enough.
 *
 * Optional on purpose. A test with no objectives still measures everything, and demanding a
 * threshold before somebody's first run produces a number they invented and then trust — which is
 * worse than no number, because it arrives wearing a verdict.
 */
export function ObjectivesStep({ draft, onChange }: StepProps) {
  const journeys = includedJourneys(draft);
  const browser = draft.testType === 'web-experience';
  const metrics = browser ? [
    { metric: 'browser.journey.duration', label: 'Whole journey duration', unit: 'ms', hint: 'Time to complete the reviewed browser journey, including its checks.' },
    { metric: 'browser.error.rate', label: 'Failed journeys', unit: '%', hint: 'Share of browser journeys that failed. Start with zero tolerated failures.' },
    { metric: 'web.lcp', label: 'Largest Contentful Paint', unit: 'ms', hint: 'When the largest visible content appeared on the final open page. An unavailable measurement remains inconclusive.' },
    { metric: 'web.cls', label: 'Cumulative Layout Shift', unit: 'score', hint: 'Unexpected movement on the final open page. This is a score, not a duration; a common starting ceiling is 0.1.' },
    { metric: 'web.ttfb', label: 'Time to First Byte', unit: 'ms', hint: 'Navigation time until the first response byte on the final open page.' },
  ] : METRICS;

  const update = (position: number, patch: Partial<ObjectiveDraft>) =>
    onChange({
      ...draft,
      objectives: draft.objectives.map((objective, i) =>
        i === position ? { ...objective, ...patch } : objective,
      ),
    });

  const remove = (position: number) =>
    onChange({ ...draft, objectives: draft.objectives.filter((_, i) => i !== position) });

  return (
    <>
      {draft.objectives.length === 0 && (
        <p className="studio-hint">
          No objectives yet. The test will still measure everything — it just will not say whether
          the result is good or bad.
        </p>
      )}

      {draft.objectives.map((objective, position) => {
        const metric = metrics.find((m) => m.metric === objective.metric) ?? metrics[0];
        return (
          <div className="studio-grid" key={position}>
            <div className="studio-field">
              <label htmlFor={`metric-${position}`}>Measure</label>
              <select
                id={`metric-${position}`}
                value={objective.metric}
                onChange={(e) =>
                  update(position, {
                    metric: e.target.value as ObjectiveMetric,
                    // A percentile is meaningless on a rate, and leaving a stale one behind would
                    // emit a document whose objective reads as something nobody chose.
                    percentile: ['http.response.time', 'browser.journey.duration', 'web.lcp', 'web.cls', 'web.ttfb'].includes(e.target.value) ? 95 : null,
                    value: e.target.value === 'web.cls' ? 0.1 : e.target.value.endsWith('error.rate') ? 0 : e.target.value === 'browser.journey.duration' ? 8000 : 1000,
                  })
                }
              >
                {metrics.map((m) => (
                  <option key={m.metric} value={m.metric}>
                    {m.label}
                  </option>
                ))}
              </select>
              <p className="studio-hint">{metric.hint}</p>
            </div>

            {objective.percentile !== null && (
              <div className="studio-field">
                <label htmlFor={`percentile-${position}`}>For this share of {browser ? 'journeys' : 'requests'}</label>
                <select
                  id={`percentile-${position}`}
                  value={objective.percentile}
                  onChange={(e) => update(position, { percentile: Number(e.target.value) })}
                >
                  <option value={50}>50% — the typical {browser ? 'journey' : 'request'}</option>
                  <option value={90}>90%</option>
                  <option value={95}>95% — the usual choice</option>
                  <option value={99}>99% — the worst experiences</option>
                </select>
              </div>
            )}

            <div className="studio-field">
              <label htmlFor={`value-${position}`}>{objective.metric === 'http.throughput' ? 'At least' : 'No worse than'} ({metric.unit})</label>
              <input
                id={`value-${position}`}
                type="number"
                min={0}
                step={objective.metric === 'web.cls' ? 0.01 : 1}
                value={objective.value}
                onChange={(e) => update(position, { value: Number(e.target.value) })}
              />
            </div>

            <div className="studio-field">
              <label htmlFor={`scope-${position}`}>Applies to</label>
              <select
                id={`scope-${position}`}
                value={objective.journey ?? ''}
                onChange={(e) => update(position, { journey: e.target.value || null })}
              >
                <option value="">The whole test</option>
                {journeys.map((journey) => (
                  <option key={journey.name} value={journey.name}>
                    {journey.name}
                  </option>
                ))}
              </select>
              <button type="button" className="link" onClick={() => remove(position)}>
                Remove this objective
              </button>
            </div>
          </div>
        );
      })}

      <div>
        <button
          type="button"
          className="btn btn-quiet"
          onClick={() =>
            onChange({
              ...draft,
              objectives: [
                ...draft.objectives,
                { metric: browser ? 'browser.journey.duration' : 'http.response.time', percentile: 95, journey: null, value: browser ? 8000 : 1000 },
              ],
            })
          }
        >
          Add an objective
        </button>
      </div>
    </>
  );
}

/**
 * One live pass before any load slot is booked.
 */
export function ValidateStep({ client, draft, onChange }: StepProps & { client: V2Client }) {
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const document = useMemo(() => {
    try {
      const validationDraft: StudioDraft = {
        ...draft,
        name: draft.name.trim() || 'validation-draft',
        application: draft.application.trim() || 'demo-service',
      };
      return { text: toSpecification(validationDraft), problem: null as string | null };
    } catch (err) {
      return {
        text: null,
        problem: err instanceof IncompleteDraftError ? err.message : String(err),
      };
    }
  }, [draft]);

  const current =
    draft.validator && draft.validator.forDocument === document.text ? draft.validator.result : null;

  async function runValidator() {
    if (!document.text) return;
    setRunning(true);
    setError(null);
    try {
      // Against the recording when there is one. Same iteration either way; the recording is what
      // lets the platform say whether each accepted correlation actually carried a fresh value,
      // rather than only that a value came out of the response.
      const result = draft.recordingSessionId
        ? await client.validateAgainstRecording(draft.recordingSessionId, document.text)
        : await client.validateTarget(document.text);
      onChange({ ...draft, validator: { forDocument: document.text, result } });
    } catch (err) {
      setError(describeError(err));
    } finally {
      setRunning(false);
    }
  }

  return (
    <>
      <p className="studio-summary">
        The Validator sends one iteration to the resolved target and shows what happened before a
        load run is saved or queued.
      </p>

      {document.problem && (
        <div className="banner banner-bad" role="alert">
          <AlertCircle size={16} /> {document.problem}
        </div>
      )}

      {error && (
        <div className="banner banner-bad" role="alert">
          <AlertCircle size={16} /> Could not run the Validator: {error}
        </div>
      )}

      {current && <ValidatorSummary result={current} />}

      {!current && !document.problem && (
        <p className="studio-hint">
          This version has not been validated yet. If you change a journey, rule, load setting or
          objective, run the Validator again so Save applies to the current test.
        </p>
      )}

      <div className="studio-button-row">
        <button
          type="button"
          className="btn"
          disabled={running || !document.text}
          onClick={runValidator}
        >
          {running ? <Loader2 size={14} className="spin" /> : <Play size={14} />}
          {running ? 'Validating...' : 'Run Validator'}
        </button>

        {/*
          The same document the studio would save, as a file. Offered here rather than only at Save
          because this is the step where somebody decides whether the test is right — and the answer
          is often "let me read it properly" or "let me put this in Git".
        */}
        <button
          type="button"
          className="btn btn-quiet"
          disabled={!document.text}
          onClick={() => document.text && downloadSpecification(document.text, draft.name)}
        >
          <Download size={14} /> Download YAML
        </button>
      </div>

      {document.text && (
        <details className="advanced">
          <summary>Show the test definition this produces</summary>
          <pre className="validation">{document.text}</pre>
        </details>
      )}
    </>
  );
}

function ValidatorSummary({ result }: { result: ValidatorResult }) {
  const errors = result.findings.filter((finding) => finding.severity === 'ERROR');
  const warnings = result.findings.filter((finding) => finding.severity === 'WARNING');
  return (
    <section className="studio-section">
      <h3>{result.valid ? 'Validated' : 'Needs attention'}</h3>
      <p className="studio-summary">
        {result.valid
          ? 'Every executed step passed in the single-iteration check.'
          : 'At least one step failed or the target could not be validated.'}
        {result.target ? ` Target: ${result.target}.` : ''}
      </p>

      {(errors.length > 0 || warnings.length > 0) && (
        <ul className="findings">
          {[...errors, ...warnings].map((finding, i) => (
            <li className="finding" key={i}>
              {finding.severity === 'ERROR' ? (
                <AlertCircle size={15} className="tone-bad" aria-hidden />
              ) : (
                <AlertTriangle size={15} className="tone-warn" aria-hidden />
              )}
              <div>
                <p>{finding.message}</p>
                {finding.remediation && <p className="muted">{finding.remediation}</p>}
              </div>
            </li>
          ))}
        </ul>
      )}

      <div className="validator-list">
        {result.journeys.map((journey) => (
          <article className="validator-journey" key={journey.name}>
            <h4>
              {journey.passed ? (
                <CheckCircle2 size={15} className="tone-good" aria-hidden />
              ) : (
                <AlertCircle size={15} className="tone-bad" aria-hidden />
              )}
              {journey.name}
            </h4>
            {journey.steps.map((step) => (
              <details className="validator-step" key={step.name} open={!step.passed}>
                <summary>
                  <span>
                    {step.passed ? 'Passed' : 'Failed'}: {step.method} {step.path}
                  </span>
                  <span>{step.durationMillis} ms</span>
                </summary>
                {step.error && <p className="studio-hint">{step.error}</p>}
                <div className="validator-grid">
                  <div>
                    <h5>Request</h5>
                    <p>
                      <code>{step.request.url}</code>
                    </p>
                    {step.request.bodyPreview && <pre>{step.request.bodyPreview}</pre>}
                  </div>
                  <div>
                    <h5>Response</h5>
                    <p>Status {step.response.status}</p>
                    {step.response.bodyPreview && <pre>{step.response.bodyPreview}</pre>}
                  </div>
                </div>
                {(step.extractions.length > 0 || step.assertions.length > 0) && (
                  <ul className="rule-evidence">
                    {step.extractions.map((extraction) => (
                      <li key={`extract-${extraction.name}`}>
                        Extraction <strong>{extraction.name}</strong>{' '}
                        {extraction.resolved ? 'resolved' : 'did not resolve'}
                        {extraction.valuePreview ? ` to "${extraction.valuePreview}"` : ''}.
                      </li>
                    ))}
                    {step.assertions.map((assertion, index) => (
                      <li key={`assert-${index}`}>
                        Assertion {assertion.subject} {assertion.operator} {assertion.expected}{' '}
                        {assertion.passed ? 'passed' : `failed; observed ${assertion.observed}`}.
                      </li>
                    ))}
                  </ul>
                )}
              </details>
            ))}
            <CorrelationProofs proofs={journey.correlations ?? []} />
          </article>
        ))}
      </div>
    </section>
  );
}

/**
 * Name it, check it, save it.
 *
 * The specification is validated **by the server** before Save is offered, not by a rule
 * reimplemented here. The platform already owns a validator; using it means what the studio shows
 * is what the platform actually thinks, and a divergence between the two is impossible rather than
 * merely unlikely.
 */
export function ReviewStep({
  client,
  draft,
  onSave,
  saving,
}: StepProps & {
  client: V2Client;
  onSave: (document: string) => void;
  saving: boolean;
}) {
  /**
   * The platform's verdict, tagged with the document it describes.
   *
   * Tagged rather than cleared, because the alternative is an effect that resets state when the
   * document changes — and a findings list that briefly belongs to the previous draft is exactly
   * the kind of stale reassurance this studio must not show. Comparing `forDocument` against the
   * current text makes that impossible rather than merely unlikely.
   */
  const [checked, setChecked] = useState<{
    forDocument: string;
    findings: ValidationFinding[] | null;
    error: string | null;
  } | null>(null);

  const document = useMemo(() => {
    try {
      return { text: toSpecification(draft), problem: null as string | null };
    } catch (err) {
      return {
        text: null,
        problem: err instanceof IncompleteDraftError ? err.message : String(err),
      };
    }
  }, [draft]);

  // Re-validated whenever the document changes, debounced. Every state update happens inside the
  // timer or a promise handler rather than in the effect body, so React is never asked to re-render
  // synchronously from an effect.
  useEffect(() => {
    const text = document.text;
    if (!text) return;

    let cancelled = false;
    const timer = setTimeout(() => {
      client
        .validate(text)
        .then((result) => {
          if (!cancelled) setChecked({ forDocument: text, findings: result.findings, error: null });
        })
        .catch((err) => {
          if (!cancelled) {
            setChecked({ forDocument: text, findings: null, error: describeError(err) });
          }
        });
    }, 350);

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [client, document.text]);

  // Only trust a verdict that was issued for the document currently on screen.
  const current = checked && checked.forDocument === document.text ? checked : null;
  const findings = current?.findings ?? null;
  const checkError = current?.error ?? null;
  const checking = document.text !== null && current === null;

  const errors = findings?.filter((f) => f.severity === 'ERROR') ?? [];
  const warnings = findings?.filter((f) => f.severity === 'WARNING') ?? [];
  const validatorCurrent =
    draft.validator && draft.validator.forDocument === document.text ? draft.validator.result : null;
  const validatorPassed = validatorCurrent?.valid === true;
  const ready = document.text !== null && findings !== null && !checkError && errors.length === 0 && !checking && validatorPassed;


  return (
    <>

      <p className="studio-summary">
        {draft.testType === 'web-experience' ? (
          <>{draft.browser.users} browsers × {draft.browser.iterations} iterations of each test.
            Stops within {draft.browser.maxMinutes} minutes.</>
        ) : draft.load.model === 'rate' ? (
          <>{includedJourneys(draft).length} journey{includedJourneys(draft).length === 1 ? '' : 's'},
            targeting {draft.load.peakRate} requests per second with at most {draft.load.maxConcurrency} requests in flight,
            for about {approximateDuration(totalDurationSeconds(draft))}.</>
        ) : (
          <>{includedJourneys(draft).length} journey{includedJourneys(draft).length === 1 ? '' : 's'}, reaching{' '}
            <strong>{draft.load.peakUsers}</strong> at once, for about{' '}
            <strong>{approximateDuration(totalDurationSeconds(draft))}</strong>.</>
        )}
      </p>

      {document.problem && (
        <div className="banner banner-bad" role="alert">
          <AlertCircle size={16} /> {document.problem}
        </div>
      )}

      {checkError && (
        <div className="banner banner-bad" role="alert">
          <AlertCircle size={16} /> Could not check this test: {checkError}
        </div>
      )}

      {errors.length > 0 && (
        <ul className="findings">
          {errors.map((finding, i) => (
            <li className="finding" key={i}>
              <AlertCircle size={15} className="tone-bad" aria-hidden />
              <div>
                <p>{finding.message}</p>
                {finding.remediation && <p className="muted">{finding.remediation}</p>}
              </div>
            </li>
          ))}
        </ul>
      )}

      {warnings.length > 0 && (
        <ul className="findings">
          {warnings.map((finding, i) => (
            <li className="finding" key={i}>
              <AlertTriangle size={15} className="tone-warn" aria-hidden />
              <div>
                <p>{finding.message}</p>
                {finding.remediation && <p className="muted">{finding.remediation}</p>}
              </div>
            </li>
          ))}
        </ul>
      )}

      {findings !== null && errors.length === 0 && !checking && (
        <p className="studio-blocker" style={{ color: 'var(--pf-verdict-pass-fg)' }}>
          <CheckCircle2 size={15} aria-hidden /> Checked by the platform — this test is valid.
        </p>
      )}

      {document.text && !validatorPassed && (
        <div className="banner banner-warn" role="status">
          <AlertTriangle size={16} /> {draft.testType === 'web-experience' ? 'Check this browser configuration before saving.' : 'Run the Validator successfully for this version before saving.'}
        </div>
      )}

      {/*
        The generated document, collapsed. CLAUDE.md §24 permits an advanced read-only view, and it
        is how somebody builds trust in a plan they did not write by hand.
      */}
      {document.text && (
        <details className="advanced">
          <summary>Show the test definition this produces</summary>
          <pre className="validation">{document.text}</pre>
        </details>
      )}

      <div>
        <button
          type="button"
          className="btn"
          disabled={!ready || saving}
          onClick={() => document.text && onSave(document.text)}
        >
          {saving ? <Loader2 size={14} className="spin" /> : <Save size={14} />}
          {saving ? 'Saving…' : 'Save test'}
        </button>
        {checking && <span className="studio-hint"> Checking with the platform…</span>}
      </div>
    </>
  );
}

/**
 * What the test is called, whose it is, and where it points.
 *
 * These four answers used to be asked on the very last screen, which put them **after** Validate.
 * The Validator resolves its target from team, application and environment, so it failed with
 * `metadata.team is required` against a draft the studio had never asked about — a step depending on
 * an answer the wizard had not requested yet.
 *
 * Registering a target belongs here for the same reason. "Where does this point" is one question,
 * and the Validator cannot send its single iteration anywhere until it has been answered.
 */
export function AboutStep({
  client,
  draft,
  onChange,
  knownTeams,
}: StepProps & {
  client: V2Client;
  /**
   * Team names this deployment knows about, offered as suggestions.
   *
   * Suggestions rather than a closed list: nothing yet reports the *caller's own* team, so picking
   * one for them would be a guess. A datalist narrows the typing without pretending to know.
   */
  knownTeams: string[];
}) {
  const [targetBaseUrl, setTargetBaseUrl] = useState('');
  const [targetBusy, setTargetBusy] = useState(false);
  const [targetStatus, setTargetStatus] = useState<string | null>(null);
  const [targetError, setTargetError] = useState<string | null>(null);

  async function registerTarget() {
    if (!targetBaseUrl.trim()) return;
    setTargetBusy(true);
    setTargetStatus(null);
    setTargetError(null);
    try {
      const target = await client.registerTarget({
        team: draft.team,
        application: draft.application,
        environment: draft.environment,
        baseUrl: targetBaseUrl,
        targetClass: 'NON_PRODUCTION',
      });
      setTargetStatus(`Registered ${target.application} ${target.environment}: ${target.baseUrl}`);
    } catch (err) {
      setTargetError(describeError(err));
    } finally {
      setTargetBusy(false);
    }
  }

  return (
    <>
      <div className="studio-grid">
        <div className="studio-field">
          <label htmlFor="test-name">Name this test</label>
          <input
            id="test-name"
            type="text"
            value={draft.name}
            placeholder="checkout-baseline"
            // Normalized as they type rather than rejected on save. A validation error about label
            // syntax is a demand that a consumer learn Kubernetes' naming rules to run a test.
            onChange={(e) => onChange({ ...draft, name: toResourceName(e.target.value) })}
            aria-describedby="test-name-hint"
          />
          <p className="studio-hint" id="test-name-hint">
            Lowercase letters, numbers and hyphens. You will find the test by this name later.
          </p>
        </div>

        <div className="studio-field">
          <label htmlFor="team">Your team</label>
          <input
            id="team"
            type="text"
            list="known-teams"
            value={draft.team}
            placeholder="local-dev"
            onChange={(e) => onChange({ ...draft, team: e.target.value })}
            aria-describedby="team-hint"
          />
          <datalist id="known-teams">
            {knownTeams.map((team) => (
              <option key={team} value={team} />
            ))}
          </datalist>
          <p className="studio-hint" id="team-hint">
            The team this access token may act for. A single team is filled in automatically; choose the owning team if you belong to several.
          </p>
        </div>

        <div className="studio-field">
          <label htmlFor="application">Which application?</label>
          <input
            id="application"
            type="text"
            value={draft.application}
            placeholder="demo-service"
            onChange={(e) => onChange({ ...draft, application: e.target.value })}
            aria-describedby="application-hint"
          />
          <p className="studio-hint" id="application-hint">
            The platform looks up where to send the traffic from this. A test cannot choose its own
            address — that is what stops it being pointed somewhere it should not go.
          </p>
        </div>

        <div className="studio-field">
          <label htmlFor="environment">Environment</label>
          <input
            id="environment"
            type="text"
            value={draft.environment}
            placeholder="performance"
            onChange={(e) => onChange({ ...draft, environment: e.target.value })}
          />
        </div>
      </div>

      <section className="studio-section">
        <h3>Target</h3>
        <p className="studio-hint">
          Where this application lives, if the platform does not already know. Already-registered
          targets need nothing here.
        </p>
        <div className="studio-grid">
          <div className="studio-field">
            <label htmlFor="target-base-url">Base URL</label>
            <input
              id="target-base-url"
              type="text"
              value={targetBaseUrl}
              placeholder="https://demo-service.perf.internal"
              onChange={(e) => setTargetBaseUrl(e.target.value)}
            />
          </div>
          <div className="studio-field studio-action-field">
            <label aria-hidden="true">&nbsp;</label>
            <button
              type="button"
              className="btn btn-quiet"
              disabled={targetBusy || !targetBaseUrl.trim()}
              onClick={registerTarget}
            >
              {targetBusy ? <Loader2 size={14} className="spin" /> : <Save size={14} />}
              {targetBusy ? 'Registering...' : 'Register target'}
            </button>
          </div>
        </div>
        {targetStatus && (
          <div className="banner banner-info" role="status">
            <CheckCircle2 size={16} /> {targetStatus}
          </div>
        )}
        {targetError && (
          <div className="banner banner-bad" role="alert">
            <AlertCircle size={16} /> {targetError}
          </div>
        )}
      </section>
    </>
  );
}

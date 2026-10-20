import { scenariosToYaml } from '../scenarioYaml';
import type { ImportedScenario } from '../api';
import type { Journey, JourneyStep, ObjectiveDraft, StudioDraft } from './draft';
import { includedJourneys, normalizeShares } from './draft';

/**
 * Turns what the studio holds into the canonical specification.
 *
 * This is the whole point of the studio: the UI, the REST API and YAML in Git all produce the same
 * versioned document (CLAUDE.md §32). The studio is not a second way to describe a test — it is a
 * way to write this one without knowing its syntax. `specification.test.ts` proves that by
 * comparing the output against a hand-written document character for character.
 *
 * ## Why YAML text rather than an object
 *
 * The definition endpoint takes `application/yaml` and the platform computes the digest from the
 * document it received. Emitting text means what the studio previews is exactly what is stored, and
 * exactly what the validate endpoint judged — there is no serialization step in between where the
 * two could differ.
 *
 * Escaping is not reimplemented here: journeys go through `scenariosToYaml`, whose quoting rules are
 * already pinned by tests. A second escaper is a second set of bugs.
 */

/** The one API version and kind this schema release understands. */
const API_VERSION = 'performance.ameriprise.com/v1alpha1';
const KIND = 'PerformanceTest';

/**
 * A duration the platform's parser accepts, in the largest unit that stays exact.
 *
 * Exactness matters more than brevity: 90 seconds is emitted as `90s`, not rounded to `2m`, because
 * the number a consumer typed is the number the test should run for.
 */
export function humanDuration(seconds: number): string {
  if (seconds <= 0) return '0s';
  if (seconds % 3600 === 0) return `${seconds / 3600}h`;
  if (seconds % 60 === 0) return `${seconds / 60}m`;
  return `${seconds}s`;
}

/**
 * A duration for a sentence a person reads, as opposed to one the platform parses.
 *
 * Deliberately not {@link humanDuration}. That one keeps 165 seconds as `165s`, because the
 * specification must run for exactly as long as somebody asked. This one is for the studio's
 * summary line, which begins with the word "about" — and "about 165s" is a number a reader has to
 * do arithmetic on before it means anything, which is the opposite of what a summary is for.
 */
export function approximateDuration(seconds: number): string {
  if (seconds < 60) return `${seconds} seconds`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} minute${minutes === 1 ? '' : 's'}`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest === 0
    ? `${hours} hour${hours === 1 ? '' : 's'}`
    : `${hours}h ${rest}m`;
}

/** The unit an objective's number carries, decided by its metric rather than typed by a consumer. */
export function objectiveThreshold(objective: ObjectiveDraft): string {
  switch (objective.metric) {
    case 'browser.journey.duration':
    case 'web.lcp':
    case 'web.ttfb':
    case 'http.response.time':
      return `${objective.value}ms`;
    case 'web.cls':
      return `${objective.value}score`;
    case 'browser.error.rate':
    case 'http.error.rate':
      return `${objective.value}%`;
    case 'http.throughput':
      return `${objective.value}/s`;
  }
}

/**
 * The three stages a load shape becomes.
 *
 * Ramp-up and ramp-down are omitted when zero rather than emitted as `0s`. A stage with no duration
 * is not a shorter stage, it is a malformed one, and the validator says so — which would surface to
 * a consumer as an error about a field the studio never showed them.
 *
 * The final stage targets zero users. That is what expresses ramp-down, and forgetting it is the
 * single most common mistake in a hand-written load model: the test simply stops at full load, and
 * the last samples are taken while the generator is being torn down.
 */
/**
 * The model declaration, and the ceiling an open model cannot legally omit.
 *
 * The ceiling is emitted only for a rate model, because the validator refuses it on a staged one —
 * where the user count already *is* the concurrency, a second limit would be either redundant or a
 * contradiction, and the specification would rather say so than pick one.
 */
function modelLines(draft: StudioDraft): string[] {
  if (draft.load.model !== 'rate') {
    return ['        type: stages'];
  }
  return [
    '        type: arrival-rate',
    `        maxConcurrency: ${draft.load.maxConcurrency}`,
  ];
}

function stageLines(draft: StudioDraft): string[] {
  const { model, peakUsers, peakRate, rampUpSeconds, steadySeconds, rampDownSeconds } = draft.load;
  const peak = model === 'rate' ? peakRate : peakUsers;
  // Which field the stages carry. Emitting the other one is not a harmless extra: the validator
  // refuses a stage that sets the field its model does not read, because whoever wrote it believes
  // something about the run that will not be true.
  const field = model === 'rate' ? 'targetRate' : 'targetUsers';

  const stages: Array<[number, number]> = [];

  if (rampUpSeconds > 0) stages.push([rampUpSeconds, peak]);
  if (steadySeconds > 0) stages.push([steadySeconds, peak]);
  if (rampDownSeconds > 0) stages.push([rampDownSeconds, 0]);
  // A shape with no duration anywhere would emit an empty stage list, which the validator refuses.
  // Holding at peak is the least surprising reading of "no ramp, no ramp-down".
  if (stages.length === 0) stages.push([steadySeconds > 0 ? steadySeconds : 60, peak]);

  return stages.flatMap(([seconds, target]) => [
    `          - duration: ${humanDuration(seconds)}`,
    `            ${field}: ${target}`,
  ]);
}

function objectiveLines(draft: StudioDraft, workloadName: string): string[] {
  return draft.objectives.flatMap((objective) => {
    const scope = objective.journey ? `${workloadName}/${objective.journey}` : workloadName;
    const lines = [
      `    - metric: ${objective.metric}`,
      `      scope: ${scope}`,
    ];
    if (objective.percentile !== null) {
      lines.push(`      percentile: ${objective.percentile}`);
    }
    lines.push(`      threshold: ${objectiveThreshold(objective)}`);
    return lines;
  });
}

/** A journey in the shape `scenariosToYaml` already knows how to escape. */
function asScenario(journey: Journey): ImportedScenario {
  const steps = journey.steps.length > 0 ? materializedSteps(journey) : [];
  return {
    name: journey.name,
    weight: journey.share,
    operationId: journey.operationId,
    method: journey.method,
    path: journey.path,
    headers: journey.headers,
    query: journey.query,
    body: journey.body,
    steps,
  };
}

function materializedSteps(journey: Journey): ImportedScenario['steps'] {
  const accepted = journey.correlationSuggestions.filter((rule) => rule.decision === 'accepted');
  return journey.steps.map((step) => {
    const extract = accepted
      .filter((rule) => rule.extraction.stepName === step.name)
      .map((rule) => ({
        name: rule.variableName,
        from: rule.extraction.from,
        expression: rule.extraction.expression,
        required: true,
      }));

    return {
      name: step.name,
      method: step.method,
      path: replacePath(step, accepted),
      headers: replaceMap(step, 'header', accepted),
      query: replaceMap(step, 'query', accepted),
      body: replaceBody(step, accepted),
      extract,
      expect:
        step.expectStatus && step.expectedStatus !== null
          ? [{ subject: 'status', operator: 'equals', value: step.expectedStatus }]
          : [],
      thinkTime: step.thinkTimeSeconds > 0 ? humanDuration(step.thinkTimeSeconds) : null,
    };
  });
}

function replacePath(
  step: JourneyStep,
  rules: Journey['correlationSuggestions'],
): string {
  let path = step.path;
  for (const rule of rules) {
    for (const replacement of rule.replacements) {
      if (replacement.stepName === step.name && replacement.location === 'path') {
        path = path.replaceAll(replacement.currentValue, `\${${rule.variableName}}`);
      }
    }
  }
  return path;
}

function replaceMap(
  step: JourneyStep,
  location: 'query' | 'header',
  rules: Journey['correlationSuggestions'],
): Record<string, string> {
  const source = location === 'query' ? step.query : step.headers;
  const next = { ...source };
  for (const rule of rules) {
    for (const replacement of rule.replacements) {
      if (replacement.stepName === step.name && replacement.location === location && replacement.name) {
        next[replacement.name] = next[replacement.name].replaceAll(
          replacement.currentValue,
          `\${${rule.variableName}}`,
        );
      }
    }
  }
  return next;
}

function replaceBody(
  step: JourneyStep,
  rules: Journey['correlationSuggestions'],
): JourneyStep['body'] {
  if (!step.body) return null;
  let content = step.body.content;
  for (const rule of rules) {
    for (const replacement of rule.replacements) {
      if (replacement.stepName === step.name && replacement.location === 'body') {
        content = content.replaceAll(replacement.currentValue, `\${${rule.variableName}}`);
      }
    }
  }
  return { ...step.body, content };
}

/** Raised when a draft cannot describe a test at all. The studio blocks Save rather than emitting one. */
export class IncompleteDraftError extends Error {}

/**
 * The canonical specification for this draft.
 *
 * @throws IncompleteDraftError when the draft is missing something no default can supply
 */
export function toSpecification(draft: StudioDraft): string {
  const name = draft.name.trim();
  if (!name) throw new IncompleteDraftError('The test needs a name.');
  if (!draft.application.trim()) throw new IncompleteDraftError('The test needs an application.');

  if (draft.testType === 'web-experience') return browserSpecification(draft);
  const journeys = normalizeShares(includedJourneys(draft));
  if (journeys.length === 0) {
    throw new IncompleteDraftError(
      'The test needs at least one journey. Import a source, or add a request by hand.',
    );
  }

  const workloadName = `${name}-load`;

  const lines = [
    `apiVersion: ${API_VERSION}`,
    `kind: ${KIND}`,
    '',
    'metadata:',
    `  name: ${name}`,
    `  team: ${draft.team}`,
    `  application: ${draft.application.trim()}`,
    '',
    'spec:',
    '  environment:',
    `    name: ${draft.environment}`,
    `    runnerCell: ${draft.runnerCell}`,
    '    targetClass: non-production',
    '',
    '  workloads:',
    `    - name: ${workloadName}`,
    '      type: protocol',
    '      engine: jmeter',
    '      model:',
    ...modelLines(draft),
    '        stages:',
    ...stageLines(draft),
    '',
    '      scenarios:',
    // Indented to sit under `scenarios:` at six spaces, which is where the key above puts it.
    ...scenariosToYaml(journeys.map(asScenario), 6).split('\n'),
    '',
    '  objectives:',
    ...objectiveLines(draft, workloadName),
    '',
    '  governance:',
    `    maxDuration: ${humanDuration(governanceCeilingSeconds(draft))}`,
    '',
  ];

  return lines.join('\n');
}

/**
 * The ceiling the platform enforces on this run, independent of what the stages ask for.
 *
 * The studio states it rather than leaving it to the platform default, because the validator warns
 * when it is absent and a warning a consumer cannot act on is worse than no warning — they either
 * learn to ignore findings, which is the one habit this platform cannot afford, or they go looking
 * for a governance concept the studio never showed them.
 *
 * Ten minutes above the stages, matching the platform's own execution grace. It has to exceed the
 * plan comfortably: startup, ramp-down and artifact upload all happen outside the stage arithmetic,
 * and a ceiling that clipped them would kill healthy runs at the moment they were writing evidence.
 *
 * Rounded up to a whole minute. A ceiling does not need second precision, and `12m` is a number
 * somebody can hold in their head where `705s` is one they have to do arithmetic on. Up rather than
 * down, because rounding a ceiling down could cross below the run it is meant to permit.
 */
export function governanceCeilingSeconds(draft: StudioDraft): number {
  return Math.ceil((totalDurationSeconds(draft) + 600) / 60) * 60;
}

/**
 * The total wall-clock time this test will take, for the studio to show before anyone commits a slot.
 *
 * Someone who has never run a performance test has no intuition for how long a stage list adds up
 * to, and "this will take 23 minutes" is the difference between an informed click and a surprise.
 */
export function totalDurationSeconds(draft: StudioDraft): number {
  const { rampUpSeconds, steadySeconds, rampDownSeconds } = draft.load;
  return Math.max(0, rampUpSeconds) + Math.max(0, steadySeconds) + Math.max(0, rampDownSeconds);
}

/** Browser source, capacity and objectives remain the canonical API/YAML model. */
function browserSpecification(draft: StudioDraft): string {
  const b = draft.browser;
  if (!b.reviewed) throw new IncompleteDraftError('Review the browser journey before saving.');
  if (b.mode === 'upload' && (!b.uri || b.uploadedForTeam !== draft.team)) {
    throw new IncompleteDraftError('Store the reviewed script for the selected team.');
  }
  if (b.mode === 'git' && (!/^https:\/\//.test(b.repository) || !/^[a-f0-9]{40}$/i.test(b.ref) || !b.path)) {
    throw new IncompleteDraftError('Provide a repository, complete commit SHA and test path.');
  }
  const quote = (value: string) => JSON.stringify(value);
  const workload = `${draft.name.trim()}-browser`;
  return [
    `apiVersion: ${API_VERSION}`, `kind: ${KIND}`, '', 'metadata:',
    `  name: ${quote(draft.name.trim())}`, `  team: ${quote(draft.team)}`,
    `  application: ${quote(draft.application.trim())}`, '', 'spec:',
    '  environment:', `    name: ${quote(draft.environment)}`, `    runnerCell: ${quote(draft.runnerCell)}`,
    '    targetClass: non-production', '', '  sources:', '    - name: browser-journey', '      type: playwright',
    ...(b.mode === 'upload' ? [`      uri: ${quote(b.uri)}`, `      digest: ${quote(b.digest)}`] : [
      '      git:', `        repository: ${quote(b.repository)}`, `        ref: ${quote(b.ref)}`, `        path: ${quote(b.path)}`,
    ]), '', '  workloads:', `    - name: ${workload}`, '      type: browser', '      engine: playwright',
    '      sourceRef: browser-journey', `      browserUsers: ${b.users}`, `      iterationsPerUser: ${b.iterations}`,
    '      browserProfile:', `        device: ${b.device}`, '        network: none', '        cpuThrottle: 1',
    '', '  objectives:', ...objectiveLines(draft, workload), '', '  governance:', `    maxDuration: ${b.maxMinutes}m`, '',
  ].join('\n');
}

import axios from 'axios';

/**
 * Typed client for the V2 API.
 *
 * <p>All V2 access goes through here rather than through axios calls scattered across components
 * (CLAUDE.md §8). One place knows the paths, the headers and the response shapes, so a contract
 * change breaks the build in one file instead of failing at runtime in several.
 */

export interface TestDefinition {
  id: string;
  teamId: string;
  applicationId: string;
  name: string;
  latestVersion: number;
  createdBy: string;
  createdAt: string;
  version: VersionSummary | null;
  findings: ValidationFinding[];
}

export interface VersionSummary {
  id: string;
  versionNumber: number;
  digest: string;
  /** False when the specification failed validation. Such a version can never be run. */
  executable: boolean;
  createdBy: string;
  createdAt: string;
}

export interface ValidationFinding {
  code: string;
  severity: 'ERROR' | 'WARNING' | 'INFO';
  path: string;
  message: string;
  remediation: string | null;
}

export interface TestRun {
  id: string;
  teamId: string;
  definitionVersionId: string;
  executionPlanId: string | null;
  status: RunStatus;
  environment: string;
  requestedBy: string;
  requestedAt: string;
  queuedAt: string | null;
  startedAt: string | null;
  completedAt: string | null;
  /** True after someone has asked the run to stop but before it actually has. */
  cancellationRequested: boolean;
  failureCode: string | null;
  failureReason: string | null;
  /**
   * True when no load was generated and every measurement this run produced is invented.
   *
   * Never render a number from such a run without the banner. The whole reason this field is on the
   * run rather than buried in an artifact is that the caveat has to travel with the number.
   */
  synthetic: boolean;
}

/**
 * What this deployment actually does when a run is triggered.
 *
 * Read once at startup and shown permanently in the header. A deployment running a development
 * provider produces runs indistinguishable from real ones apart from the numbers being invented, so
 * a consumer cannot be expected to work it out for themselves.
 */
export interface ExecutionCapability {
  providerId: string;
  generatesRealLoad: boolean;
  summary: string;
}

export type RunStatus =
  | 'VALIDATING'
  | 'REJECTED'
  | 'AWAITING_APPROVAL'
  | 'QUEUED'
  | 'PROVISIONING'
  | 'RUNNING'
  | 'ANALYZING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'
  | 'EXPIRED';

export interface RunEvent {
  sequence: number;
  type: string;
  previousState: string | null;
  newState: string | null;
  actor: string;
  detail: Record<string, string>;
  occurredAt: string;
}

export interface ObjectiveOutcome {
  objectiveId: string;
  metric: string;
  scope: string;
  /** INDETERMINATE means it could not be judged — not that it failed. */
  verdict: 'PASS' | 'BREACH' | 'INDETERMINATE';
  observed: number;
  threshold: number;
  detail: string;
}

export interface Artifact {
  id: string;
  type: string;
  objectKey: string;
  digest: string;
  contentType: string;
  sizeBytes: number;
  storedAt: string;
}

export interface TargetRegistrationRequest {
  team: string;
  application: string;
  environment: string;
  baseUrl: string;
  targetClass?: 'NON_PRODUCTION' | 'PRODUCTION';
}

export interface Target {
  teamId: string;
  applicationId: string;
  application: string;
  environment: string;
  targetClass: 'NON_PRODUCTION' | 'PRODUCTION';
  baseUrl: string;
  createdBy: string;
  createdAt: string;
}

export type RecorderSessionState =
  | 'PENDING_BROWSER_BRIDGE'
  | 'STARTING'
  | 'LIVE'
  | 'CLOSING'
  | 'CLOSED'
  | 'FAILED'
  | 'CANCELLED'
  | 'EXPIRED';

export interface RecordingSession {
  id: string;
  teamId: string;
  application: string;
  environment: string;
  target: string;
  status: RecorderSessionState;
  browserUrl: string | null;
  /** The surface pointer coordinates must be scaled to. Never assume the rendered image size. */
  viewportWidth: number;
  viewportHeight: number;
  /**
   * What this deployment runs the browser with.
   *
   * A session stuck in PENDING_BROWSER_BRIDGE is not broken — this says why, and the console shows
   * it rather than leaving someone waiting for a browser that will never arrive.
   */
  bridge: string;
  failureCode: string | null;
  failureReason: string | null;
  capturedCount: number;
  blockedCount: number;
  events: RecorderAuditEvent[];
}

/** The fixed vocabulary the bridge accepts. There is no way to name a DevTools method. */
export type RecorderInputKind =
  | 'mouse-moved'
  | 'mouse-pressed'
  | 'mouse-released'
  | 'mouse-wheel'
  | 'key-down'
  | 'key-up'
  | 'text'
  | 'navigate'
  | 'reload'
  | 'navigate-back';

export interface RecorderInput {
  kind: RecorderInputKind;
  x?: number;
  y?: number;
  button?: 'none' | 'left' | 'middle' | 'right';
  clickCount?: number;
  deltaX?: number;
  deltaY?: number;
  key?: string;
  code?: string;
  text?: string;
  modifiers?: number;
  url?: string;
}

export interface RecorderFrame {
  sequence: number;
  /** Base64 JPEG, no data-URI prefix. */
  imageBase64: string;
  width: number;
  height: number;
  capturedAt: string;
}

export interface RecorderBlockedDestination {
  sequence: number;
  destination: string;
  kind: string;
  code: string;
  reason: string;
  blockedAt: string;
}

export interface RecorderCapturedExchange {
  sequence: number;
  method: string;
  url: string;
  requestContentType: string | null;
  responseContentType: string | null;
  findingCount: number;
  capturedAt: string;
}

/** One poll. `frame` is null when the client is already current — not an error. */
export interface RecorderLiveUpdate {
  status: RecorderSessionState;
  viewportWidth: number;
  viewportHeight: number;
  frame: RecorderFrame | null;
  blocked: RecorderBlockedDestination[];
  captures: RecorderCapturedExchange[];
  failureCode: string | null;
  failureReason: string | null;
}

export interface RecorderAuditEvent {
  sequence: number;
  type: string;
  actor: string;
  detail: Record<string, string>;
  occurredAt: string;
}

/**
 * A run's report.
 *
 * `measurements` and `execution` are `null` rather than zeroed when a run has not reached them. Show
 * "not measured yet", never a zero — a zero reads as a finished test that found nothing wrong.
 */
export interface RunReport {
  run: TestRun;
  /** Repeated at the top level on purpose: the caveat on every number below it. */
  synthetic: boolean;
  syntheticNotice: string | null;
  test: TestedDefinition | null;
  execution: ExecutionDetail | null;
  objectives: ObjectiveOutcome[];
  summary: ObjectiveSummary;
  measurements: Measurements | null;
  timeline: RunEvent[];
  artifacts: Artifact[];
}

export interface TestedDefinition {
  name: string;
  application: string;
  versionNumber: number;
  digest: string;
  workloads: WorkloadProfile[];
}

export interface WorkloadProfile {
  name: string;
  type: string | null;
  peakUsers: number;
  iterationsPerUser?: number | null;
  modelType?: 'STAGES' | 'ARRIVAL_RATE' | null;
  maxConcurrency?: number | null;
  peakArrivalRate?: number;
  totalDurationSeconds: number;
  stages: Stage[];
  scenarios: Scenario[];
}

export interface Stage {
  durationSeconds: number;
  targetUsers: number;
  targetRate?: number | null;
}

export interface Scenario {
  name: string;
  weightPercent: number;
  operationId: string;
}

/** The platform's own view of the run. Shown only in the advanced section (CLAUDE.md §24). */
export interface ExecutionDetail {
  engine: string;
  planDigest: string;
  shardCount: number;
  peakVirtualUsers: number;
  estimatedDurationSeconds: number;
}

export interface ObjectiveSummary {
  passed: number;
  breached: number;
  indeterminate: number;
  /** True only when the run generated real load, asked at least one question, and all passed. */
  demonstrated: boolean;
}

export interface Measurements {
  engine: string;
  measuredFrom: string | null;
  measuredTo: string | null;
  shardsExpected: number;
  shardsReported: number;
  /** False when some load generators never reported — the numbers describe a smaller test. */
  complete: boolean;
  overall: OperationMetrics;
  operations: OperationMetrics[];
}

export interface OperationMetrics {
  /** Null/absent on summaries created before exact p75 was stored. */
  p75Millis?: number | null;
  name: string;
  sampleCount: number;
  errorCount: number;
  /** Results that could not be read at all. Counted in the error rate, shown so it is visible. */
  unparsedCount: number;
  errorRatePercent: number;
  minMillis: number;
  maxMillis: number;
  meanMillis: number;
  p50Millis: number;
  p90Millis: number;
  p95Millis: number;
  p99Millis: number;
  throughputPerSecond: number;
}

/** The sources this release can read. Matches the importers the server has deployed. */
export type SourceType = 'curl' | 'openapi' | 'har' | 'postman';

/**
 * One draft scenario an importer produced.
 *
 * `operationId` is what the source called the operation and `name` is its identifier form, because
 * a name reaches artifact object keys and Kubernetes labels. Null for sources with no such concept.
 */
export interface ImportedScenario {
  name: string;
  weight: number;
  operationId: string | null;
  method: string | null;
  path: string | null;
  headers: Record<string, string>;
  query: Record<string, string>;
  body: { contentType: string; content: string } | null;
  steps: ImportedStep[];
}

export interface ImportedStep {
  name: string;
  method: string;
  path: string;
  headers: Record<string, string>;
  query: Record<string, string>;
  body: { contentType: string; content: string } | null;
  extract?: Array<{
    name: string;
    from: 'json-body' | 'regex-body' | 'header' | 'set-cookie' | 'status';
    expression: string;
    required?: boolean;
  }>;
  expect?: Array<{ subject: 'status' | 'body'; operator: 'equals' | 'contains' | 'matches'; value: string | number }>;
  thinkTime?: string | null;
}

export interface CorrelationSuggestion {
  id: string;
  scenarioName: string;
  variableName: string;
  extraction: CorrelationExtractionEvidence;
  replacements: CorrelationReplacementEvidence[];
}

export interface CorrelationExtractionEvidence {
  stepName: string;
  from: 'json-body' | 'regex-body' | 'header' | 'set-cookie' | 'status';
  expression: string;
  valuePreview: string;
}

export interface CorrelationReplacementEvidence {
  stepName: string;
  location: 'path' | 'query' | 'header' | 'body';
  name: string | null;
  currentValue: string;
}

/**
 * What an import produced.
 *
 * The findings are as much the answer as the scenarios. An importer strips credentials, drops hosts
 * and refuses to invent data, and what it *could not* import is what stands between this draft and a
 * test that measures something — so never render the scenarios without them.
 */
export interface ImportResponse {
  sourceType: string;
  scenarios: ImportedScenario[];
  findings: ValidationFinding[];
  correlationSuggestions: CorrelationSuggestion[];
}

export interface ValidatorResult {
  valid: boolean;
  target: string | null;
  findings: ValidationFinding[];
  journeys: ValidatorJourney[];
}

export interface ValidatorJourney {
  name: string;
  passed: boolean;
  steps: ValidatorStep[];
  correlations: CorrelationProof[];
}

/**
 * Whether one correlation rule actually did anything.
 *
 * Not the same question as the extraction result on a step, which only says whether a value came
 * out. A rule can produce a value, be applied to nothing, and leave the journey sending the literal
 * it was supposed to replace — every step passes and the rule is decorative.
 *
 * `proven` needs a recording to compare against: it means the value this iteration extracted was
 * *different* from the one the recording captured, so the journey demonstrably carried a fresh
 * value rather than replaying the capture. Without a recording the verdict stops at `used`.
 */
export type CorrelationVerdict = 'proven' | 'unproven' | 'used' | 'unused' | 'not-extracted';

export interface CorrelationProof {
  variable: string;
  fromStep: string;
  usedBySteps: string[];
  verdict: CorrelationVerdict;
  livePreview: string | null;
  message: string;
}

export interface ValidatorStep {
  name: string;
  method: string;
  path: string;
  passed: boolean;
  durationMillis: number;
  request: ValidatorRequestEvidence;
  response: ValidatorResponseEvidence;
  extractions: ValidatorExtraction[];
  assertions: ValidatorAssertion[];
  error: string | null;
}

export interface ValidatorRequestEvidence {
  url: string;
  headers: Record<string, string>;
  bodyPreview: string | null;
}

export interface ValidatorResponseEvidence {
  status: number;
  headers: Record<string, string>;
  bodyPreview: string | null;
}

export interface ValidatorExtraction {
  name: string;
  from: string;
  expression: string;
  resolved: boolean;
  valuePreview: string | null;
  message: string | null;
}

export interface ValidatorAssertion {
  subject: string;
  operator: string;
  expected: string;
  passed: boolean;
  observed: string | null;
  message: string | null;
}

/** RFC 7807 problem detail, which every V2 error uses. */
export interface Problem {
  title: string;
  status: number;
  detail: string;
  code: string;
  correlationId: string;
  findings?: ValidationFinding[];
  existingRunId?: string;
  field?: string;
}

/** A terminal run is never going to change again, so polling can stop. */
/**
 * What a run is doing right now.
 *
 * Every number here is provisional and the type says so in its field names. Percentiles are bucket
 * upper bounds, counts come from partial runner output, and `provisionalObjectives` is never the
 * run's verdict — that is computed from the complete results once every generator has finished, and
 * arrives on the report.
 */
export interface RunProgress {
  runId: string;
  engine: string;
  /** The freshest generator snapshot in this view, by the generator's clock. */
  capturedAt: string;
  /** The control plane's clock when it assembled this. */
  observedAt: string;
  ageSeconds: number;
  shardsExpected: number;
  shardsReporting: number;
  elapsedSeconds: number;
  /** Users or browser sessions actually applying load, not what the stage asked for. */
  activeUsers: number;
  overall: LiveMetrics;
  byOperation: LiveMetrics[];
  shards: ShardSnapshot[];
  series: ProgressPoint[];
  provisionalObjectives: ObjectiveOutcome[];
  observations: Observation[];
}

export interface LiveMetrics {
  name: string;
  sampleCount: number;
  errorCount: number;
  unparsedCount: number;
  /** Percentage scaled by 1,000: 1500 means 1.5%. */
  errorRateMilliPercent: number;
  minMillis: number;
  maxMillis: number;
  meanMillis: number;
  p50Millis: number;
  p90Millis: number;
  /** An upper bound — the top of the bucket the sample fell in. Never interpolated. */
  p95Millis: number;
  p99Millis: number;
  /** Requests per second scaled by 1,000, over the most recent window rather than the whole run. */
  throughputPerSecondMilli: number;
}

/**
 * One generator's own last word.
 *
 * Present because the merged totals cannot show a generator that stopped: the survivors keep adding
 * to them, so a run that lost half its load looks like a run that is merely slower.
 */
export interface ShardSnapshot {
  shardIndex: number;
  capturedAt: string;
  ageSeconds: number;
  sampleCount: number;
  errorCount: number;
  activeUsers: number;
}

export interface ProgressPoint {
  capturedAt: string;
  elapsedSeconds: number;
  activeUsers: number;
  sampleCount: number;
  errorCount: number;
  errorRateMilliPercent: number;
  throughputPerSecondMilli: number;
  p95Millis: number;
}

/** A rule-based observation, and the measurement it came from. */
export interface Observation {
  code: string;
  severity: 'INFO' | 'WARNING' | 'CRITICAL';
  message: string;
  /** Always present: a message without its numbers is an opinion. */
  evidence: string;
}

export const isTerminal = (status: RunStatus): boolean =>
  ['COMPLETED', 'FAILED', 'CANCELLED', 'REJECTED', 'EXPIRED'].includes(status);

/** A run that is holding a concurrency slot, and is therefore worth watching closely. */
export const isActive = (status: RunStatus): boolean =>
  ['PROVISIONING', 'RUNNING', 'ANALYZING'].includes(status);

export class V2Client {
  private readonly apiKey: string;

  constructor(apiKey: string) {
    this.apiKey = apiKey;
  }

  private get headers() {
    return { 'X-API-Key': this.apiKey, Authorization: `Bearer ${this.apiKey.replace(/^Bearer\s+/, '')}` };
  }

  uploadBrowserSource(team: string, script: string) {
    return axios.post<{ uri: string; digest: string; sizeBytes: number }>(
      '/api/v2/browser-sources', script, {
        params: { team, reviewed: true }, headers: { ...this.headers, 'Content-Type': 'text/plain' },
      }).then(r => r.data);
  }

  listDefinitions() {
    return axios
      .get<TestDefinition[]>('/api/v2/test-definitions', { headers: this.headers })
      .then((r) => r.data);
  }

  listVersions(definitionId: string) {
    return axios
      .get<VersionSummary[]>(`/api/v2/test-definitions/${definitionId}/versions`, {
        headers: this.headers,
      })
      .then((r) => r.data);
  }

  /** Side-effect free: safe to call on every keystroke pause while editing. */
  validate(document: string) {
    return axios
      .post<{ valid: boolean; digest: string | null; findings: ValidationFinding[] }>(
        '/api/v2/test-definitions/validate',
        document,
        { headers: { ...this.headers, 'Content-Type': 'application/yaml' } },
      )
      .then((r) => r.data);
  }

  /** One live iteration against the server-side resolved target. Creates no run. */
  validateTarget(document: string) {
    return axios
      .post<ValidatorResult>('/api/v2/test-definitions/validator', document, {
        headers: { ...this.headers, 'Content-Type': 'application/yaml' },
      })
      .then((r) => r.data);
  }

  /**
   * The same live iteration, judged against the recording the draft came from.
   *
   * Worth a separate call rather than a flag on `validateTarget`, because it answers a question the
   * ordinary Validator cannot: a specification no longer holds the literal a correlation replaced —
   * accepting the rule is what removed it — so only the recording can say whether the value this
   * iteration carried was fresh or was the captured one replayed.
   *
   * Creates nothing and books no load slot, exactly like `validateTarget`.
   */
  validateAgainstRecording(sessionId: string, document: string) {
    return axios
      .post<ValidatorResult>(`/api/v2/recordings/${sessionId}/validator`, document, {
        headers: { ...this.headers, 'Content-Type': 'application/yaml' },
      })
      .then((r) => r.data);
  }

  /**
   * Reads a source into draft scenarios.
   *
   * Creates nothing — no definition, no version, no run — which is what makes it safe to call
   * repeatedly while someone iterates, and what keeps an import from becoming an executable test
   * without a person having looked at it.
   *
   * Sent as `text/plain` whatever the source is: the server reads the body itself and a browser
   * would otherwise attach a charset the endpoint does not accept.
   */
  importSource(sourceType: SourceType, document: string) {
    return axios
      .post<ImportResponse>(`/api/v2/imports/${sourceType}`, document, {
        headers: { ...this.headers, 'Content-Type': 'text/plain' },
      })
      .then((r) => r.data);
  }

  createDefinition(document: string) {
    return axios
      .post<TestDefinition>('/api/v2/test-definitions', document, {
        headers: { ...this.headers, 'Content-Type': 'application/yaml' },
      })
      .then((r) => r.data);
  }

  /**
   * Triggers a run.
   *
   * The idempotency key is generated per click. Two clicks are two intentional runs; a retry of the
   * *same* click — a dropped response, a double-submit — reuses the key and returns the original run
   * rather than starting a second load test.
   */
  triggerRun(
    definitionVersionId: string,
    idempotencyKey: string,
    credentials: Record<string, string> = {},
  ) {
    return axios
      .post<TestRun>(
        '/api/v2/test-runs',
        { definitionVersionId, credentials },
        { headers: { ...this.headers, 'Idempotency-Key': idempotencyKey } },
      )
      .then((r) => r.data);
  }

  registerTarget(request: TargetRegistrationRequest) {
    return axios
      .post<Target>('/api/v2/targets', request, { headers: this.headers })
      .then((r) => r.data);
  }

  listTargets() {
    return axios
      .get<Target[]>('/api/v2/targets', { headers: this.headers })
      .then((r) => r.data);
  }

  startRecording(
    teamId: string,
    application: string,
    environment: string,
    viewport?: { width: number; height: number },
  ) {
    return axios
      .post<RecordingSession>(
        '/api/v2/recordings',
        {
          teamId,
          application,
          environment,
          viewportWidth: viewport?.width,
          viewportHeight: viewport?.height,
        },
        { headers: this.headers },
      )
      .then((r) => r.data);
  }

  getRecording(sessionId: string) {
    return axios
      .get<RecordingSession>(`/api/v2/recordings/${sessionId}`, { headers: this.headers })
      .then((r) => r.data);
  }

  /**
   * Polls the live recorder surface.
   *
   * Sequences are echoed back so a slow client resumes where it left off instead of replaying the
   * whole session. Only the newest frame comes back: showing someone the past while they are
   * clicking the present is worse than dropping frames.
   *
   * `waitMillis` long-polls: the server holds the request until something happens. Chrome emits a
   * frame only when the page repaints, so reading a static page produces none — without the wait,
   * a client spends the whole session asking for nothing several times a second.
   */
  pollRecording(
    sessionId: string,
    after: { frame: number; blocked: number; capture: number },
    waitMillis = 0,
  ) {
    return axios
      .get<RecorderLiveUpdate>(`/api/v2/recordings/${sessionId}/live`, {
        headers: this.headers,
        // Above the server's own ceiling, so the server is what ends the wait. A client timeout
        // firing first would abort a healthy held connection and look like a failure.
        timeout: waitMillis > 0 ? waitMillis + 10_000 : 0,
        params: {
          afterFrame: after.frame,
          afterBlocked: after.blocked,
          afterCapture: after.capture,
          waitMillis,
        },
      })
      .then((r) => r.data);
  }

  sendRecorderInput(sessionId: string, input: RecorderInput) {
    return axios
      .post<void>(`/api/v2/recordings/${sessionId}/input`, input, { headers: this.headers })
      .then(() => undefined);
  }

  /**
   * Turns a recording into draft scenarios.
   *
   * Creates nothing, so it is safe to call while the recording is still running. The response is the
   * same shape `importSource` returns — underneath, the recording is rendered as a HAR and read by
   * the ordinary HAR importer, so anything that can display an import can display a recording.
   */
  /**
   * Team names this deployment knows about.
   *
   * A V1 endpoint, deliberately: it is the only thing that lists teams, and the studio uses it for
   * suggestions rather than authority. Nothing yet reports the *caller's own* team.
   */
  listTeamNames() {
    return axios
      .get<{ teams: string[] }>('/api/v2/platform/caller', { headers: this.headers })
      .then((r) => r.data.teams);
  }

  recordingDraft(sessionId: string) {
    return axios
      .get<ImportResponse>(`/api/v2/recordings/${sessionId}/draft`, { headers: this.headers })
      .then((r) => r.data);
  }

  stopRecording(sessionId: string) {
    return axios
      .post<RecordingSession>(`/api/v2/recordings/${sessionId}/stop`, {}, { headers: this.headers })
      .then((r) => r.data);
  }

  listRuns(limit = 25) {
    return axios
      .get<TestRun[]>(`/api/v2/test-runs?limit=${limit}`, { headers: this.headers })
      .then((r) => r.data);
  }

  getRun(runId: string) {
    return axios
      .get<TestRun>(`/api/v2/test-runs/${runId}`, { headers: this.headers })
      .then((r) => r.data);
  }

  listEvents(runId: string) {
    return axios
      .get<RunEvent[]>(`/api/v2/test-runs/${runId}/events`, { headers: this.headers })
      .then((r) => r.data);
  }

  listObjectives(runId: string) {
    return axios
      .get<ObjectiveOutcome[]>(`/api/v2/test-runs/${runId}/objectives`, { headers: this.headers })
      .then((r) => r.data);
  }

  listArtifacts(runId: string) {
    return axios
      .get<Artifact[]>(`/api/v2/test-runs/${runId}/artifacts`, { headers: this.headers })
      .then((r) => r.data);
  }

  cancelRun(runId: string, reason: string) {
    return axios
      .post<{ outcome: string; run: TestRun }>(
        `/api/v2/test-runs/${runId}/cancel?reason=${encodeURIComponent(reason)}`,
        null,
        { headers: this.headers },
      )
      .then((r) => r.data);
  }

  getReport(runId: string) {
    return axios
      .get<RunReport>(`/api/v2/test-runs/${runId}/report`, { headers: this.headers })
      .then((r) => r.data);
  }

  /**
   * What a run is doing right now.
   *
   * Returns `null` when nothing has been published yet — the endpoint answers `204`, deliberately,
   * because "not measured yet" and "measured nothing" are different facts and a body of zeros would
   * let this render the second while the first is true.
   *
   * Never throws for a run that is simply quiet: a failed poll is handled by the caller as a gap in
   * the live view, not as an error about the run.
   */
  getRunProgress(runId: string) {
    return axios
      .get<RunProgress | ''>(`/api/v2/test-runs/${runId}/progress`, { headers: this.headers })
      .then((r) => (r.status === 204 || !r.data ? null : (r.data as RunProgress)));
  }

  /** What this deployment does when a run is triggered. Shown permanently in the header. */
  getExecutionCapability() {
    return axios
      .get<ExecutionCapability>('/api/v2/platform/execution', { headers: this.headers })
      .then((r) => r.data);
  }

  /**
   * Downloads one artifact.
   *
   * Fetched through this client rather than linked with an `<a href>`, because the request needs the
   * API key header and the server issues no pre-signed URL to link to (CLAUDE.md §17). The blob URL
   * is created and revoked around the click, so nothing durable is minted here either.
   */
  async downloadArtifact(runId: string, artifact: Artifact, fileName: string) {
    const response = await axios.get<Blob>(
      `/api/v2/test-runs/${runId}/artifacts/${artifact.id}/content`,
      { headers: this.headers, responseType: 'blob' },
    );

    const url = URL.createObjectURL(response.data);
    try {
      const link = window.document.createElement('a');
      link.href = url;
      link.download = fileName;
      link.rel = 'noopener';
      window.document.body.appendChild(link);
      link.click();
      link.remove();
    } finally {
      // Revoked immediately: a blob URL left alive keeps the whole file in memory, and a results
      // file from a real run is not small.
      URL.revokeObjectURL(url);
    }
  }
}

/** Turns an axios failure into the problem detail the API actually returned, where there is one. */
export function toProblem(err: unknown): Problem | null {
  const response = (err as { response?: { data?: unknown } })?.response;
  const data = response?.data as Problem | undefined;
  return data && typeof data === 'object' && 'code' in data ? data : null;
}

/** A readable message for any failure, problem detail or not. */
export function describeError(err: unknown): string {
  const problem = toProblem(err);
  if (problem) {
    const findings = problem.findings?.length
      ? ` (${problem.findings.map((f) => `${f.path || '/'}: ${f.message}`).join('; ')})`
      : '';
    return `${problem.detail}${findings}`;
  }
  const status = (err as { response?: { status?: number } })?.response?.status;
  if (status === 403) {
    return 'Forbidden. Check the API key, and that its team is registered.';
  }
  return (err as Error)?.message ?? 'Something went wrong.';
}

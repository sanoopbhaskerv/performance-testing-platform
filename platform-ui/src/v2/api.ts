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
  totalDurationSeconds: number;
  stages: Stage[];
  scenarios: Scenario[];
}

export interface Stage {
  durationSeconds: number;
  targetUsers: number;
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
    return { 'X-API-Key': this.apiKey };
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
  triggerRun(definitionVersionId: string, idempotencyKey: string) {
    return axios
      .post<TestRun>(
        '/api/v2/test-runs',
        { definitionVersionId },
        { headers: { ...this.headers, 'Idempotency-Key': idempotencyKey } },
      )
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

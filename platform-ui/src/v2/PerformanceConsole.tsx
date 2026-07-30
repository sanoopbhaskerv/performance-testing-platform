import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  AlertCircle,
  CheckCircle2,
  ChevronRight,
  Clock,
  FileText,
  HelpCircle,
  Loader2,
  Play,
  Plus,
  RefreshCw,
  Square,
  XCircle,
} from 'lucide-react';
import type {
  Artifact,
  ObjectiveOutcome,
  RunEvent,
  RunStatus,
  TestDefinition,
  TestRun,
  VersionSummary,
} from './api';
import { V2Client, describeError, isTerminal } from './api';
import './console.css';

/**
 * The V2 self-service console.
 *
 * <p>Consumer language throughout (CLAUDE.md §24): expected users, ramp-up, response-time objective.
 * Nothing here mentions thread groups, JMX, samplers or Kubernetes — those are the platform's
 * business, and a screen that exposed them would make every consumer learn JMeter to run a test.
 *
 * <p>Progress is shown as the run's own state machine, not a spinner. "Queued" and "Provisioning"
 * mean different things to someone waiting, and collapsing them into "in progress" loses the only
 * information that would tell them whether to wait or investigate.
 */

const STARTER_SPECIFICATION = `apiVersion: performance.ameriprise.com/v1alpha1
kind: PerformanceTest

metadata:
  # These names must already be registered. The team comes from your API key's team,
  # and the application must match one of that team's configured targets.
  name: checkout-baseline
  team: alpha
  application: alpha-service

spec:
  environment:
    name: performance
    runnerCell: local
    targetClass: non-production

  workloads:
    - name: checkout-load
      type: protocol
      engine: jmeter
      model:
        type: stages
        stages:
          # Ramp to 50 expected users, hold, then ramp down.
          - duration: 2m
            targetUsers: 50
          - duration: 5m
            targetUsers: 100
          - duration: 1m
            targetUsers: 0
      # Weights produce a realistic journey mix rather than an even spread.
      scenarios:
        - name: browse
          weight: 70
          operationId: browseCatalogue
        - name: checkout
          weight: 30
          operationId: submitOrder

  objectives:
    - metric: http.response.time
      scope: checkout-load
      percentile: 95
      threshold: 750ms
    - metric: http.error.rate
      scope: checkout-load
      threshold: 1%

  observability:
    provider: dynatrace
    correlationHeader: X-Performance-Test-Run-Id

  governance:
    maxDuration: 30m
    approvalPolicy: non-production-standard
    retention: 30d
`;

/** How a run's state reads to someone waiting on it, and how urgent it is. */
const STATUS_COPY: Record<RunStatus, { label: string; hint: string; tone: string }> = {
  VALIDATING: { label: 'Validating', hint: 'Checking the test before anything runs.', tone: 'wait' },
  REJECTED: { label: 'Rejected', hint: 'Refused before any load was generated.', tone: 'bad' },
  AWAITING_APPROVAL: {
    label: 'Awaiting approval',
    hint: 'Needs a human decision before any load is generated.',
    tone: 'wait',
  },
  QUEUED: {
    label: 'Queued',
    hint: 'Waiting for a free slot. Your team runs one test at a time by default.',
    tone: 'wait',
  },
  PROVISIONING: { label: 'Starting', hint: 'Creating the load generator.', tone: 'busy' },
  RUNNING: { label: 'Running', hint: 'Generating load now.', tone: 'busy' },
  ANALYZING: { label: 'Analyzing', hint: 'Load has stopped; measuring the results.', tone: 'busy' },
  COMPLETED: { label: 'Passed', hint: 'Finished and met every objective.', tone: 'good' },
  FAILED: { label: 'Failed', hint: 'See the reason below.', tone: 'bad' },
  CANCELLED: { label: 'Cancelled', hint: 'Stopped on request.', tone: 'neutral' },
  EXPIRED: { label: 'Expired', hint: 'Aged out before it ever ran.', tone: 'neutral' },
};

function StatusPill({ status }: { status: RunStatus }) {
  const copy = STATUS_COPY[status];
  return (
    <span className={`pill pill-${copy.tone}`} title={copy.hint}>
      {copy.tone === 'busy' && <Loader2 size={12} className="spin" />}
      {copy.label}
    </span>
  );
}

function VerdictIcon({ verdict }: { verdict: ObjectiveOutcome['verdict'] }) {
  if (verdict === 'PASS') return <CheckCircle2 size={16} className="tone-good" />;
  if (verdict === 'BREACH') return <XCircle size={16} className="tone-bad" />;
  // Indeterminate is deliberately not a failure icon: nothing was proven either way.
  return <HelpCircle size={16} className="tone-neutral" />;
}

export default function PerformanceConsole({ apiKey }: { apiKey: string }) {
  const client = useMemo(() => new V2Client(apiKey), [apiKey]);

  const [definitions, setDefinitions] = useState<TestDefinition[]>([]);
  const [versions, setVersions] = useState<Record<string, VersionSummary[]>>({});
  const [runs, setRuns] = useState<TestRun[]>([]);
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [events, setEvents] = useState<RunEvent[]>([]);
  const [objectives, setObjectives] = useState<ObjectiveOutcome[]>([]);
  const [artifacts, setArtifacts] = useState<Artifact[]>([]);

  const [editorOpen, setEditorOpen] = useState(false);
  const [document, setDocument] = useState(STARTER_SPECIFICATION);
  const [validation, setValidation] = useState<string | null>(null);

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const selectedRun = runs.find((run) => run.id === selectedRunId) ?? null;

  const refresh = useCallback(async () => {
    if (!apiKey) return;
    try {
      const [definitionList, runList] = await Promise.all([
        client.listDefinitions(),
        client.listRuns(25),
      ]);
      setDefinitions(definitionList);
      setRuns(runList);
      setError(null);

      const versionEntries = await Promise.all(
        definitionList.map(async (definition) => {
          const list = await client.listVersions(definition.id);
          return [definition.id, list] as const;
        }),
      );
      setVersions(Object.fromEntries(versionEntries));
    } catch (err) {
      setError(describeError(err));
    }
  }, [apiKey, client]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const loadRunDetail = useCallback(
    async (runId: string) => {
      try {
        const [run, eventList, objectiveList, artifactList] = await Promise.all([
          client.getRun(runId),
          client.listEvents(runId),
          client.listObjectives(runId),
          client.listArtifacts(runId),
        ]);
        setRuns((previous) => previous.map((r) => (r.id === runId ? run : r)));
        setEvents(eventList);
        setObjectives(objectiveList);
        setArtifacts(artifactList);
      } catch (err) {
        setError(describeError(err));
      }
    },
    [client],
  );

  /**
   * Polls the selected run while it can still change, and stops when it cannot.
   *
   * Polling a terminal run forever would be a request every two seconds for a value that will never
   * differ — noise in the platform's own logs and a battery cost for no information.
   */
  const pollRef = useRef<number | null>(null);
  useEffect(() => {
    if (pollRef.current) window.clearInterval(pollRef.current);
    if (!selectedRunId) return;

    loadRunDetail(selectedRunId);

    const current = runs.find((run) => run.id === selectedRunId);
    if (current && isTerminal(current.status)) return;

    pollRef.current = window.setInterval(() => loadRunDetail(selectedRunId), 2000);
    return () => {
      if (pollRef.current) window.clearInterval(pollRef.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedRunId, selectedRun?.status, loadRunDetail]);

  const validateDocument = async () => {
    setBusy(true);
    setValidation(null);
    try {
      const result = await client.validate(document);
      setValidation(
        result.valid
          ? `Valid. Digest ${result.digest?.slice(0, 19)}…`
          : result.findings.map((f) => `${f.severity} ${f.path || '/'}: ${f.message}`).join('\n'),
      );
    } catch (err) {
      setValidation(describeError(err));
    } finally {
      setBusy(false);
    }
  };

  const createDefinition = async () => {
    setBusy(true);
    setError(null);
    try {
      const created = await client.createDefinition(document);
      setNotice(`Created "${created.name}" version ${created.version?.versionNumber ?? 1}.`);
      setEditorOpen(false);
      await refresh();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  };

  const triggerRun = async (version: VersionSummary) => {
    setBusy(true);
    setError(null);
    try {
      // A fresh key per click: two clicks are two intentional runs, but a retry of one click —
      // a dropped response, a double submit — returns the original rather than doubling the load.
      const key = `ui-${version.id}-${Date.now()}`;
      const run = await client.triggerRun(version.id, key);
      setNotice(`Run requested. It will start when your team has a free slot.`);
      setRuns((previous) => [run, ...previous]);
      setSelectedRunId(run.id);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  };

  const cancelRun = async (run: TestRun) => {
    setBusy(true);
    setError(null);
    try {
      const result = await client.cancelRun(run.id, 'Cancelled from the console');
      setNotice(
        result.outcome === 'CANCELLATION_REQUESTED'
          ? 'Stop requested. The load generator is being shut down.'
          : 'Run cancelled.',
      );
      await loadRunDetail(run.id);
      await refresh();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  };

  if (!apiKey) {
    return (
      <div className="v2-empty">
        <AlertCircle size={18} />
        <p>Enter your team's API key above to use the performance console.</p>
      </div>
    );
  }

  return (
    <div className="v2">
      {error && (
        <div className="banner banner-bad" role="alert">
          <AlertCircle size={16} /> {error}
        </div>
      )}
      {notice && (
        <div className="banner banner-info" role="status">
          <CheckCircle2 size={16} /> {notice}
          <button className="link" onClick={() => setNotice(null)} aria-label="Dismiss">
            dismiss
          </button>
        </div>
      )}

      <section className="card">
        <header className="card-head">
          <h2>Your tests</h2>
          <div className="card-actions">
            <button className="btn btn-quiet" onClick={refresh} disabled={busy}>
              <RefreshCw size={14} /> Refresh
            </button>
            <button className="btn" onClick={() => setEditorOpen((open) => !open)}>
              <Plus size={14} /> New test
            </button>
          </div>
        </header>

        {editorOpen && (
          <div className="editor">
            <label htmlFor="spec">
              Describe the test. The same document works here, through the API and in Git.
            </label>
            <textarea
              id="spec"
              spellCheck={false}
              rows={22}
              value={document}
              onChange={(e) => setDocument(e.target.value)}
            />
            <div className="editor-actions">
              <button className="btn btn-quiet" onClick={validateDocument} disabled={busy}>
                Check it
              </button>
              <button className="btn" onClick={createDefinition} disabled={busy}>
                Save test
              </button>
            </div>
            {validation && <pre className="validation">{validation}</pre>}
          </div>
        )}

        {definitions.length === 0 ? (
          <p className="muted">
            No tests yet. Choose <strong>New test</strong> to describe one.
          </p>
        ) : (
          <ul className="definitions">
            {definitions.map((definition) => (
              <li key={definition.id}>
                <div className="definition-head">
                  <strong>{definition.name}</strong>
                  <span className="muted">
                    {definition.latestVersion} version
                    {definition.latestVersion === 1 ? '' : 's'}
                  </span>
                </div>
                <ul className="versions">
                  {(versions[definition.id] ?? []).map((version) => (
                    <li key={version.id}>
                      <span className="muted">v{version.versionNumber}</span>
                      <code className="digest">{version.digest.slice(7, 19)}</code>
                      {version.executable ? (
                        <button
                          className="btn btn-small"
                          onClick={() => triggerRun(version)}
                          disabled={busy}
                        >
                          <Play size={12} /> Run this
                        </button>
                      ) : (
                        <span className="tone-bad" title="This version failed validation.">
                          not runnable
                        </span>
                      )}
                    </li>
                  ))}
                </ul>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="card">
        <header className="card-head">
          <h2>Recent runs</h2>
        </header>

        {runs.length === 0 ? (
          <p className="muted">Nothing has run yet.</p>
        ) : (
          <table className="runs">
            <thead>
              <tr>
                <th>Requested</th>
                <th>Status</th>
                <th>Environment</th>
                <th>By</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {runs.map((run) => (
                <tr
                  key={run.id}
                  className={run.id === selectedRunId ? 'selected' : undefined}
                  onClick={() => setSelectedRunId(run.id)}
                >
                  <td>{new Date(run.requestedAt).toLocaleString()}</td>
                  <td>
                    <StatusPill status={run.status} />
                    {run.cancellationRequested && !isTerminal(run.status) && (
                      <span className="muted"> stopping…</span>
                    )}
                  </td>
                  <td>{run.environment}</td>
                  <td className="muted">{run.requestedBy}</td>
                  <td>
                    <ChevronRight size={14} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {selectedRun && (
        <section className="card">
          <header className="card-head">
            <h2>
              Run detail <StatusPill status={selectedRun.status} />
            </h2>
            {!isTerminal(selectedRun.status) && selectedRun.status !== 'ANALYZING' && (
              <button
                className="btn btn-quiet"
                onClick={() => cancelRun(selectedRun)}
                disabled={busy}
              >
                <Square size={13} /> Stop this run
              </button>
            )}
          </header>

          <p className="muted">{STATUS_COPY[selectedRun.status].hint}</p>

          {selectedRun.failureReason && (
            <div className="banner banner-bad">
              <AlertCircle size={16} />
              <span>
                <strong>{selectedRun.failureCode}</strong> — {selectedRun.failureReason}
              </span>
            </div>
          )}

          <h3>Objectives</h3>
          {objectives.length === 0 ? (
            <p className="muted">
              {isTerminal(selectedRun.status)
                ? 'No objectives were evaluated for this run.'
                : 'Objectives are evaluated once the run finishes.'}
            </p>
          ) : (
            <ul className="objectives">
              {objectives.map((objective) => (
                <li key={objective.objectiveId}>
                  <VerdictIcon verdict={objective.verdict} />
                  <span className="objective-scope">{objective.scope}</span>
                  <span className="muted">{objective.detail}</span>
                </li>
              ))}
            </ul>
          )}

          <h3>What happened</h3>
          <ol className="timeline">
            {events.map((event) => (
              <li key={event.sequence}>
                <Clock size={12} className="muted" />
                <time>{new Date(event.occurredAt).toLocaleTimeString()}</time>
                <span>
                  {event.newState
                    ? `${STATUS_COPY[event.newState as RunStatus]?.label ?? event.newState}`
                    : event.type.replace(/_/g, ' ').toLowerCase()}
                </span>
                {event.detail.reason && <span className="muted">{event.detail.reason}</span>}
              </li>
            ))}
          </ol>

          {artifacts.length > 0 && (
            <>
              <h3>Evidence</h3>
              <ul className="artifacts">
                {artifacts.map((artifact) => (
                  <li key={artifact.id}>
                    <FileText size={13} className="muted" />
                    <span>{artifact.type.replace(/_/g, ' ').toLowerCase()}</span>
                    <span className="muted">{(artifact.sizeBytes / 1024).toFixed(1)} KB</span>
                  </li>
                ))}
              </ul>
            </>
          )}
        </section>
      )}
    </div>
  );
}

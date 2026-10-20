import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  AlertCircle,
  AlertTriangle,
  CheckCircle2,
  ChevronRight,
  Clock,
  Download,
  HelpCircle,
  Loader2,
  Play,
  Plus,
  RefreshCw,
  ServerCog,
  Square,
  Video,
  XCircle,
} from 'lucide-react';
import type {
  ExecutionCapability,
  ObjectiveOutcome,
  RunEvent,
  RunProgress,
  RunReport as Report,
  RunStatus,
  RecordingSession,
  Target,
  TestDefinition,
  TestRun,
  VersionSummary,
} from './api';
import { V2Client, describeError, isActive, isTerminal } from './api';
import RunReport from './RunReport';
import ImportPanel from './ImportPanel';
import Studio from './studio/Studio';
import LiveMonitor from './LiveMonitor';
import RunProgressBar from './RunProgressBar';
import { objectiveLabel } from './labels';
import './console.css';
import { RecorderPanel } from './RecorderPanel';
import type { Journey } from './studio/draft';
import { journeysFromImport } from './studio/draft';

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

/**
 * A test against the bundled demo service, ready to run.
 *
 * Mirrors `specification/examples/valid/demo-service.yaml`, which the build validates. This is the
 * one starting point that generates real, interesting load on a laptop: the demo service answers
 * `/api/users` in 5-25ms and `POST /api/payments` in 150-600ms while failing about 5% of them, so
 * the report shows a breach and a pass side by side rather than a flat line.
 *
 * Its scenarios state their own method and path. That is the manual form — no importer needed — and
 * only the path comes from here; the host and port are resolved by the platform from the team's
 * registered target.
 */
const DEMO_SERVICE_SPECIFICATION = `apiVersion: performance.ameriprise.com/v1alpha1
kind: PerformanceTest

metadata:
  name: demo-service-baseline
  team: local-dev
  application: demo-service

spec:
  environment:
    name: performance
    runnerCell: local
    targetClass: non-production

  workloads:
    - name: demo-load
      type: protocol
      engine: jmeter
      model:
        type: stages
        stages:
          # Short on purpose: a laptop generating load against a service on the same
          # laptop measures the laptop as much as the service.
          - duration: 30s
            targetUsers: 5
          - duration: 1m
            targetUsers: 10
          - duration: 15s
            targetUsers: 0

      scenarios:
        # The fast read path: 5-25ms, almost never fails.
        - name: browse-users
          weight: 80
          method: GET
          path: /api/users

        # The slow write path: 150-600ms, and fails about 5% of the time by design.
        - name: submit-payment
          weight: 20
          method: POST
          path: /api/payments

  objectives:
    # Expected to breach — the payment path alone runs to ~600ms. A demo that always
    # passes never shows anyone what a breach looks like.
    - metric: http.response.time
      scope: demo-load
      percentile: 95
      threshold: 400ms

    # Expected to breach too: the demo service fails ~5% of payments deliberately.
    - metric: http.error.rate
      scope: demo-load
      threshold: 1%

    # Scoped to the fast journey alone, and expected to pass.
    - metric: http.response.time
      scope: demo-load/browse-users
      percentile: 95
      threshold: 250ms

  observability:
    provider: dynatrace
    correlationHeader: X-Performance-Test-Run-Id

  governance:
    maxDuration: 10m
    approvalPolicy: non-production-standard
    retention: 7d
`;

const STARTER_SPECIFICATION = `apiVersion: performance.ameriprise.com/v1alpha1
kind: PerformanceTest

metadata:
  # These names must already be registered. The team is the one your API key belongs to,
  # and the application must match one of that team's configured targets — that
  # correspondence is how the platform decides where a run's traffic may go, and it is
  # deliberately not something a specification can choose for itself.
  #
  # These defaults match the local-dev team that ships in config/teams, so this document
  # runs as-is on a laptop. Change them to your own team and target.
  name: checkout-baseline
  team: local-dev
  application: local-service

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

/**
 * The starting points offered in the editor.
 *
 * Two, because they answer different questions. The demo one is the only one that generates real,
 * interesting load without any setup beyond starting the bundled service — which is what someone
 * trying the platform for the first time actually wants. The blank-ish one is the shape to copy when
 * describing a real system.
 */
const TEMPLATES: { id: string; label: string; hint: string; document: string }[] = [
  {
    id: 'demo',
    label: 'Demo service',
    hint:
      'Runs against the bundled demo service on port 8081. Start it first, and use the ' +
      'local-jmeter provider to generate real traffic.',
    document: DEMO_SERVICE_SPECIFICATION,
  },
  {
    id: 'starter',
    label: 'Blank starter',
    hint: 'The shape of a test. Edit the team, application and journeys to describe your own system.',
    document: STARTER_SPECIFICATION,
  },
];

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

function targetKey(target: Target) {
  return `${target.teamId}:${target.application}:${target.environment}`;
}

export default function PerformanceConsole({ apiKey, teamNames = [], recorderAvailable = false }: { apiKey: string; teamNames?: string[]; recorderAvailable?: boolean }) {
  const client = useMemo(() => new V2Client(apiKey), [apiKey]);

  const [definitions, setDefinitions] = useState<TestDefinition[]>([]);
  const [versions, setVersions] = useState<Record<string, VersionSummary[]>>({});
  const [runs, setRuns] = useState<TestRun[]>([]);
  const [targets, setTargets] = useState<Target[]>([]);
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [events, setEvents] = useState<RunEvent[]>([]);
  const [objectives, setObjectives] = useState<ObjectiveOutcome[]>([]);
  const [report, setReport] = useState<Report | null>(null);
  const [liveProgress, setLiveProgress] = useState<RunProgress | null>(null);

  /** What this deployment does when a run is triggered. Read once; it cannot change while running. */
  const [capability, setCapability] = useState<ExecutionCapability | null>(null);

  const [editorOpen, setEditorOpen] = useState(false);
  /**
   * The studio, which is now the way a test is described.
   *
   * The YAML editor stays available behind "Write it as YAML" — it is how an expert works, how a
   * definition already in Git is edited, and the escape hatch for anything the studio cannot yet
   * express. Removing it would make the studio's gaps into walls.
   */
  const [studioOpen, setStudioOpen] = useState(false);
  // Defaults to the demo template: it is the one that runs without editing, and someone opening the
  // editor for the first time is far more likely to want a working test than an empty shape.
  const [templateId, setTemplateId] = useState(TEMPLATES[0].id);
  const [document, setDocument] = useState(TEMPLATES[0].document);
  const [validation, setValidation] = useState<string | null>(null);
  const [importOpen, setImportOpen] = useState(false);
  const [sessionCredential, setSessionCredential] = useState('');
  const [recordingTargetKey, setRecordingTargetKey] = useState('');
  const [recordingSession, setRecordingSession] = useState<RecordingSession | null>(null);
  /**
   * Journeys carried from a recording into the studio.
   *
   * Held here rather than inside the recorder panel because the studio is the console's, not the
   * panel's — and because clearing it on save is what stops a second visit to the studio reopening
   * somebody's old recording.
   */
  const [recordingSeed, setRecordingSeed] = useState<{
    journeys: Journey[];
    application: string;
    environment: string;
    name: string;
    recordingSessionId: string;
  } | null>(null);

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const selectedRun = runs.find((run) => run.id === selectedRunId) ?? null;

  const refresh = useCallback(async () => {
    if (!apiKey) return;
    try {
      const [definitionList, runList, targetList] = await Promise.all([
        client.listDefinitions(),
        client.listRuns(25),
        client.listTargets(),
      ]);
      setDefinitions(definitionList);
      setRuns(runList);
      setTargets(targetList);
      setRecordingTargetKey((current) => {
        if (current && targetList.some((target) => targetKey(target) === current)) return current;
        return targetList[0] ? targetKey(targetList[0]) : '';
      });
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

  /**
   * Reads the active execution provider once.
   *
   * Failing to read it is not treated as a warning-free state: the header says the provider is
   * unknown rather than silently showing nothing, because "no banner" and "no load generated" would
   * otherwise look identical.
   */
  useEffect(() => {
    if (!apiKey) return;
    client
      .getExecutionCapability()
      .then(setCapability)
      .catch(() => setCapability(null));
  }, [apiKey, client]);

  const loadRunDetail = useCallback(
    async (runId: string) => {
      try {
        const [run, eventList, objectiveList, runReport, progress] = await Promise.all([
          client.getRun(runId),
          client.listEvents(runId),
          client.listObjectives(runId),
          client.getReport(runId),
          // Kept out of the failure path of the rest: a live view that could not be read is a gap
          // in a chart, and turning it into the panel's error message would report a problem with
          // the run when the problem is with one poll.
          client.getRunProgress(runId).catch(() => null),
        ]);
        setRuns((previous) => previous.map((r) => (r.id === runId ? run : r)));
        setEvents(eventList);
        setObjectives(objectiveList);
        setReport(runReport);
        setLiveProgress(progress);
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
      const run = await client.triggerRun(
        version.id,
        key,
        sessionCredential.trim() ? { session: sessionCredential } : {},
      );
      setSessionCredential('');
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

  const startRecording = async () => {
    const target = targets.find((candidate) => targetKey(candidate) === recordingTargetKey);
    if (!target) {
      setError('Choose a registered target before starting a recording.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const session = await client.startRecording(
        target.teamId,
        target.application,
        target.environment,
      );
      setRecordingSession(session);
      setNotice(
        session.status === 'LIVE'
          ? `Recording ${session.application}. Click the surface below, then interact with the page.`
          : `Recorder session ${session.id.slice(0, 8)} is ${session.status.replace(/_/g, ' ').toLowerCase()}.`,
      );
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
        <p>Enter your team access token above. For the local demo, use local-dev-key-not-a-secret.</p>
      </div>
    );
  }

  return (
    <div className="v2">
      {/*
        Always visible, above everything. A deployment running a provider that generates no load
        produces runs that look real in every respect except that the numbers are invented, and a
        consumer has no way to know that from the outside.
      */}
      <div
        className={
          capability && !capability.generatesRealLoad
            ? 'provider-line provider-line-synthetic'
            : 'provider-line'
        }
        role={capability && !capability.generatesRealLoad ? 'alert' : undefined}
      >
        {capability && !capability.generatesRealLoad ? (
          <AlertTriangle size={15} aria-hidden />
        ) : (
          <ServerCog size={15} aria-hidden />
        )}
        {capability ? (
          <span>
            Execution provider <code>{capability.providerId}</code> — {capability.summary}
          </span>
        ) : (
          // Not silence. "No banner" and "no load generated" must never look the same.
          <span>Execution provider could not be read. Treat any results here as unverified.</span>
        )}
      </div>

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
            <button
              className="btn btn-quiet"
              onClick={() => {
                setStudioOpen(false);
                setEditorOpen((open) => !open);
              }}
            >
              Write it as YAML
            </button>
            <button
              className="btn"
              onClick={() => {
                setEditorOpen(false);
                setStudioOpen(true);
              }}
            >
              <Plus size={14} /> New test
            </button>
          </div>
        </header>

        {studioOpen && (
          <Studio
            client={client}
            team={teamNames.length === 1 ? teamNames[0] : ''}
            seed={recordingSeed ? { testType: 'api-load', ...recordingSeed } : null}
            onSaved={(name) => {
              setStudioOpen(false);
              setRecordingSeed(null);
              setNotice(`Created "${name}". Choose it below to run it.`);
              void refresh();
            }}
            onCancel={() => {
              setStudioOpen(false);
              setRecordingSeed(null);
            }}
          />
        )}

        {recorderAvailable && <div className="recorder-entry">
          <div>
            <h3>Recorder</h3>
            <p className="muted">
              Starts only against a registered target, on a browser the platform controls. Every
              destination outside that target's allowlist is refused and shown to you.
            </p>
          </div>
          <div className="recorder-controls">
            <select
              value={recordingTargetKey}
              onChange={(event) => setRecordingTargetKey(event.target.value)}
              disabled={targets.length === 0 || busy}
              aria-label="Recording target"
            >
              {targets.length === 0 ? (
                <option value="">No registered targets</option>
              ) : (
                targets.map((target) => (
                  <option key={targetKey(target)} value={targetKey(target)}>
                    {target.application} / {target.environment}
                  </option>
                ))
              )}
            </select>
            <button className="btn btn-quiet" onClick={startRecording} disabled={busy || !recordingTargetKey}>
              <Video size={14} /> Start guarded recording
            </button>
          </div>
        </div>}

        {recordingSession && (
          <RecorderPanel
            client={client}
            session={recordingSession}
            onSessionChange={setRecordingSession}
            onClose={() => setRecordingSession(null)}
            onError={setError}
            onDraft={(draft) => {
              const journeys = journeysFromImport(draft.scenarios, draft.correlationSuggestions);
              if (journeys.length === 0) {
                setError(
                  'This recording produced no journeys. Static assets and failed requests are ' +
                    'deliberately left out — interact with the application, then try again.',
                );
                return;
              }
              setRecordingSeed({
                journeys,
                // The recording already knows these — the person chose them when they picked a
                // target and pressed record. About opens answered rather than blank, and the
                // Validator has what it needs by the time it runs.
                application: recordingSession.application,
                environment: recordingSession.environment,
                name: `${recordingSession.application}-recorded`,
                // Kept so the Validator can be run against the recording rather than against the
                // draft alone. It is the only thing that still knows the literal each accepted
                // correlation replaced, and therefore the only way to tell a rule that carried a
                // fresh value from one that quietly did nothing.
                recordingSessionId: recordingSession.id,
              });
              setEditorOpen(false);
              setStudioOpen(true);
              setNotice(
                `Turned ${journeys.length} recorded journey${journeys.length === 1 ? '' : 's'} ` +
                  'into a draft. Nothing has been created yet — review it below, then ' +
                  '“Save test” to create it. It appears under Your tests, with a Run button.',
              );
              // The studio opens above the recorder, so from down here it looked like the button
              // did nothing. Bring it into view rather than leaving someone to find it.
              // window.document, because `document` is a state variable in this component — the
              // YAML editor's contents.
              requestAnimationFrame(() =>
                window.document.querySelector('.studio')?.scrollIntoView({
                  behavior: 'smooth',
                  block: 'start',
                }),
              );
            }}
          />
        )}

        {editorOpen && (
          <div className="editor">
            <fieldset className="templates">
              <legend>Start from</legend>
              {TEMPLATES.map((template) => (
                <label key={template.id} className="template">
                  <input
                    type="radio"
                    name="template"
                    value={template.id}
                    checked={templateId === template.id}
                    onChange={() => {
                      setTemplateId(template.id);
                      setDocument(template.document);
                      setValidation(null);
                    }}
                  />
                  <span>
                    <strong>{template.label}</strong>
                    <span className="muted">{template.hint}</span>
                  </span>
                </label>
              ))}
            </fieldset>

            <div className="editor-import">
              <button
                className="btn btn-quiet"
                onClick={() => setImportOpen((open) => !open)}
                aria-expanded={importOpen}
              >
                <Download size={14} />
                {importOpen ? 'Hide import' : 'Import journeys from a file or command'}
              </button>
              <span className="muted">
                A cURL command, an OpenAPI document, a browser recording or a Postman collection.
              </span>
            </div>

            {importOpen && (
              <ImportPanel
                client={client}
                document={document}
                onApply={(next) => {
                  setDocument(next);
                  setValidation(null);
                  setNotice(
                    'The imported journeys are in the test below. Read the findings, fill in ' +
                      'anything the import could not, then save it.',
                  );
                }}
                onClose={() => setImportOpen(false)}
              />
            )}

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
          <>
            <details className="advanced"><summary>Optional session authentication</summary>
            <p className="muted">Only needed when this test explicitly uses a runtime session credential. Leave blank for a public or unauthenticated journey.</p>
            <div className="runtime-credential">
              <label htmlFor="session-credential">Session credential</label>
              <input
                id="session-credential"
                type="password"
                value={sessionCredential}
                onChange={(event) => setSessionCredential(event.target.value)}
                placeholder="Used for run://session"
                autoComplete="off"
              />
            </div>
            </details>
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
          </>
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
                    {/* In the list too, not only on the report. Someone scanning this table for a
                        result must not have to open a run to learn its numbers are invented. */}
                    {run.synthetic && (
                      <span
                        className="pill pill-wait"
                        title="No load was generated. This run's measurements are synthetic."
                      >
                        synthetic
                      </span>
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

          {/* Where a run is in its plan. Derived from the stage arithmetic and the recorded start,
              so it costs no new measurement and no runner change — live throughput and latency are
              S7, which does need one. */}
          <RunProgressBar run={selectedRun} report={report} />

          {/* Live measurements, while there are any. Kept above the objectives and the timeline
              because it is the reason somebody has this panel open during a run — and it disappears
              on its own once the run ends and the report becomes the thing worth reading. */}
          {isActive(selectedRun.status) && <LiveMonitor progress={liveProgress} />}

          {/* On the run as well as on its report. The report is what gets screenshotted, but this
              panel is where someone first looks at a result. */}
          {selectedRun.synthetic && (
            <div className="synthetic-banner" role="alert">
              <AlertTriangle size={18} aria-hidden />
              <div>
                <strong>
                  No load was generated. These measurements are synthetic and describe nothing.
                </strong>
                <p>
                  This deployment runs an execution provider that sends no traffic. Nothing was
                  measured, so every objective below is reported as not determined.
                </p>
              </div>
            </div>
          )}

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
                  <span className="objective-scope">
                    {objectiveLabel(objective.metric, objective.scope)}
                  </span>
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

        </section>
      )}

      {/*
        The report is its own panel rather than a section of the detail card: it is the thing people
        link to and screenshot, and it carries its own copy of the synthetic warning so that a
        screenshot of it alone is still honest.
      */}
      {selectedRun && report && report.run.id === selectedRun.id && (
        <RunReport client={client} report={report} onError={setError} />
      )}
    </div>
  );
}

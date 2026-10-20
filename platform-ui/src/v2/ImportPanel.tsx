import { useRef, useState } from 'react';
import { AlertTriangle, Info, Upload, XCircle } from 'lucide-react';
import {
  describeError,
  type ImportResponse,
  type SourceType,
  type V2Client,
  type ValidationFinding,
} from './api';
import { spliceScenarios, scenariosToYaml } from './scenarioYaml';

/**
 * Reads something a consumer already has into the journeys of a test.
 *
 * The blank page is the real barrier to onboarding, not the YAML — and until now the four importers
 * this platform has were reachable only over HTTP, which is no use to the people they were built
 * for.
 *
 * ## Importing creates nothing
 *
 * The endpoint reads a document and returns a draft. No test, no version, no run. That is what makes
 * it safe to sit behind a button somebody presses while experimenting, and what keeps an import from
 * becoming an executable test without a person having looked at it — saving is still a separate,
 * deliberate act.
 *
 * ## The findings are the point, not a footnote
 *
 * Every importer removes far more than it keeps: credentials, hosts, session cookies, scripts,
 * third-party requests, and any value it would have had to invent. What it *could not* import is
 * what stands between this draft and a test that measures something — a wall of 401s at run time is
 * the alternative to reading them. So they are rendered first, in full, with their remediation, and
 * they stay on screen after the journeys are used.
 */

const SOURCES: { id: SourceType; label: string; hint: string; accept: string }[] = [
  {
    id: 'curl',
    label: 'cURL command',
    hint: 'Copy as cURL from your browser’s network tab. The fastest way to a first journey.',
    accept: '.txt,.sh',
  },
  {
    id: 'openapi',
    label: 'OpenAPI / Swagger',
    hint: 'Every operation your API has. Values come from the examples the document states.',
    accept: '.yaml,.yml,.json',
  },
  {
    id: 'har',
    label: 'Browser recording (HAR)',
    hint: 'What a real user actually did, and how often. Review it: it records a live session.',
    accept: '.har,.json',
  },
  {
    id: 'postman',
    label: 'Postman collection',
    hint: 'Requests somebody already named and grouped. Scripts are reported, never run.',
    accept: '.json',
  },
];

const SEVERITY_ORDER: Record<ValidationFinding['severity'], number> = {
  ERROR: 0,
  WARNING: 1,
  INFO: 2,
};

/**
 * Why the imported journeys could not be put into this test.
 *
 * One cause is predictable enough to name properly, and it is the one people will hit most: a test
 * whose objectives are scoped to individual journeys. Replacing the journeys leaves those objectives
 * pointing at names that no longer exist, and the platform refuses the test — correctly, because an
 * objective about a journey that is not in the test is a question nobody can answer.
 *
 * Telling someone to paste the journeys manually would be wrong here: it hits exactly the same wall.
 * The objectives are what has to change, and only a person can decide what they should say about the
 * journeys that arrived.
 */
function explainRefusal(blocking: ValidationFinding[]): string {
  const orphaned = blocking.filter((finding) => finding.code === 'spec.objective.scope.unresolved');

  if (orphaned.length === blocking.length && orphaned.length > 0) {
    return (
      `This test has ${orphaned.length} objective${orphaned.length === 1 ? '' : 's'} about ` +
      'journeys the import replaces, so nothing was changed. Point them at the journeys below or ' +
      'at the whole workload first, then import again. Starting from “Blank starter”, whose ' +
      'objectives cover the whole workload, avoids this.'
    );
  }

  return (
    'Putting these journeys into this test would leave it invalid, so nothing was changed: ' +
    blocking.map((finding) => `${finding.path || '/'} ${finding.message}`).join('; ')
  );
}

function FindingIcon({ severity }: { severity: ValidationFinding['severity'] }) {
  if (severity === 'ERROR') return <XCircle size={14} className="tone-bad" aria-hidden />;
  if (severity === 'WARNING') return <AlertTriangle size={14} className="tone-warn" aria-hidden />;
  return <Info size={14} className="tone-neutral" aria-hidden />;
}

export default function ImportPanel({
  client,
  document: specification,
  onApply,
  onClose,
}: {
  client: V2Client;
  /** The test being edited. Its journeys are what an import replaces. */
  document: string;
  onApply: (next: string) => void;
  onClose: () => void;
}) {
  const [sourceType, setSourceType] = useState<SourceType>('curl');
  const [source, setSource] = useState('');
  const [fileName, setFileName] = useState<string | null>(null);
  const [result, setResult] = useState<ImportResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [placement, setPlacement] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  const fileInput = useRef<HTMLInputElement>(null);
  const active = SOURCES.find((entry) => entry.id === sourceType) ?? SOURCES[0];

  const readFile = (file: File) => {
    const reader = new FileReader();
    reader.onload = () => {
      setSource(String(reader.result ?? ''));
      setFileName(file.name);
      setResult(null);
      setPlacement(null);
    };
    reader.onerror = () => setError(`Could not read ${file.name}.`);
    reader.readAsText(file);
  };

  const runImport = async () => {
    setBusy(true);
    setError(null);
    setResult(null);
    setPlacement(null);
    try {
      setResult(await client.importSource(sourceType, source));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  };

  /**
   * Puts the imported journeys into the test being edited.
   *
   * The spliced document is validated against the server before it is accepted. Replacing part of a
   * YAML document by manipulating text is only ever safe when it is checked, and the platform
   * already owns the validator that can check it — so an edit that does not round-trip is discarded
   * rather than handed back looking fine.
   */
  const useJourneys = async () => {
    if (!result) return;
    setBusy(true);
    setError(null);

    const spliced = spliceScenarios(specification, result.scenarios);
    if (!spliced.document) {
      setPlacement(spliced.reason);
      setBusy(false);
      return;
    }

    try {
      const check = await client.validate(spliced.document);
      const blocking = check.findings.filter((finding) => finding.severity === 'ERROR');
      if (blocking.length > 0) {
        setPlacement(explainRefusal(blocking));
        return;
      }
      onApply(spliced.document);
      setPlacement(null);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  };

  const snippet = result ? scenariosToYaml(result.scenarios, 6) : '';

  const copySnippet = async () => {
    try {
      await navigator.clipboard.writeText(`scenarios:\n${snippet}`);
      setCopied(true);
    } catch {
      setCopied(false);
      setError('Could not copy. Select the journeys below and copy them manually.');
    }
  };

  const findings = result
    ? [...result.findings].sort(
        (a, b) => SEVERITY_ORDER[a.severity] - SEVERITY_ORDER[b.severity],
      )
    : [];
  const errorCount = findings.filter((finding) => finding.severity === 'ERROR').length;

  return (
    <div className="import-panel">
      <header className="import-head">
        <h3>Import journeys from something you already have</h3>
        <button className="btn btn-quiet btn-small" onClick={onClose}>
          Close
        </button>
      </header>

      <p className="muted">
        Reading a source <strong>creates nothing</strong> — no test, no run — so it is safe to try as
        often as you like. Every importer removes credentials and hosts, and never invents data it
        was not given. What it removed is listed below the journeys, and that list is the part worth
        reading.
      </p>

      <fieldset className="templates">
        <legend>What have you got?</legend>
        {SOURCES.map((entry) => (
          <label key={entry.id} className="template">
            <input
              type="radio"
              name="source-type"
              value={entry.id}
              checked={sourceType === entry.id}
              onChange={() => {
                setSourceType(entry.id);
                setResult(null);
                setPlacement(null);
              }}
            />
            <span>
              <strong>{entry.label}</strong>
              <span className="muted">{entry.hint}</span>
            </span>
          </label>
        ))}
      </fieldset>

      <label htmlFor="import-source">Paste it here, or choose a file.</label>
      <textarea
        id="import-source"
        spellCheck={false}
        rows={8}
        value={source}
        placeholder={
          sourceType === 'curl'
            ? "curl 'https://your-api/orders?status=open' -H 'Accept: application/json'"
            : 'Paste the file’s contents, or use “Choose a file”.'
        }
        onChange={(event) => {
          setSource(event.target.value);
          setFileName(null);
          setResult(null);
          setPlacement(null);
        }}
      />

      <div className="editor-actions">
        <input
          ref={fileInput}
          type="file"
          accept={active.accept}
          className="visually-hidden"
          onChange={(event) => {
            const file = event.target.files?.[0];
            if (file) readFile(file);
            event.target.value = '';
          }}
        />
        <button
          className="btn btn-quiet"
          onClick={() => fileInput.current?.click()}
          disabled={busy}
        >
          <Upload size={14} /> Choose a file
        </button>
        <button className="btn" onClick={runImport} disabled={busy || source.trim() === ''}>
          Read it
        </button>
        {fileName && <span className="muted">{fileName}</span>}
      </div>

      {error && <p className="banner banner-bad">{error}</p>}

      <div aria-live="polite">
        {result && result.scenarios.length === 0 && (
          <p className="banner banner-bad">
            Nothing could be imported from this. The findings below say why.
          </p>
        )}

        {result && result.scenarios.length > 0 && (
          <section className="import-result">
            <h4>
              {result.scenarios.length} journey{result.scenarios.length === 1 ? '' : 's'}
            </h4>
            <table className="runs import-scenarios">
              <thead>
                <tr>
                  <th scope="col">Journey</th>
                  <th scope="col">Share</th>
                  <th scope="col">Request</th>
                  <th scope="col">Sends data</th>
                </tr>
              </thead>
              <tbody>
                {result.scenarios.map((scenario) => (
                  <tr key={scenario.name}>
                    <td>{scenario.name}</td>
                    <td>{scenario.weight}%</td>
                    <td>
                      <code>
                        {scenario.method} {scenario.path}
                        {Object.keys(scenario.query).length > 0 &&
                          `?${Object.entries(scenario.query)
                            .map(([key, value]) => `${key}=${value}`)
                            .join('&')}`}
                      </code>
                    </td>
                    <td className="muted">{scenario.body ? 'Yes' : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>

            <div className="editor-actions">
              <button className="btn" onClick={useJourneys} disabled={busy}>
                Use these journeys
              </button>
              <button className="btn btn-quiet" onClick={copySnippet} disabled={busy}>
                {copied ? 'Copied' : 'Copy as YAML'}
              </button>
            </div>

            {placement && <p className="banner banner-bad">{placement}</p>}
          </section>
        )}

        {result && findings.length > 0 && (
          <section className="import-findings">
            <h4>
              What the import changed or could not do
              {errorCount > 0 && (
                <span className="pill pill-bad">
                  {errorCount} must be fixed before running
                </span>
              )}
            </h4>
            <ul className="findings">
              {findings.map((finding) => (
                <li key={`${finding.code}${finding.path}`} className="finding">
                  <FindingIcon severity={finding.severity} />
                  <div>
                    <p>{finding.message}</p>
                    {finding.remediation && <p className="muted">{finding.remediation}</p>}
                    <code className="muted">{finding.path || '/'}</code>
                  </div>
                </li>
              ))}
            </ul>
          </section>
        )}
      </div>
    </div>
  );
}

import { useRef, useState } from 'react';
import { AlertCircle, AlertTriangle, FileUp, Info, Loader2, Plus } from 'lucide-react';
import type { SourceType, ValidationFinding, V2Client } from '../api';
import { describeError } from '../api';
import type { Journey, StudioDraft } from './draft';
import { journeysFromImport } from './draft';

/**
 * Where the requests come from.
 *
 * <p>Four document sources plus typing one by hand. Every one produces a draft plus <em>findings</em>,
 * and the findings are as much the answer as the journeys: an importer strips credentials, drops
 * hosts and refuses to invent business data, and what it could not import is exactly what stands
 * between a draft and a test that measures something.
 *
 * <p>The HAR option carries a wizard rather than a file picker. A capture of the real application is
 * the best source this platform accepts — it is the only one that knows how often each request was
 * actually made — and most people have never exported one. PLAN.md §5 chose this over building a
 * recorder for release 1: it needs no extension, no certificate and no install.
 */

interface SourceChoice {
  type: SourceType;
  label: string;
  /** What a consumer would call the thing they have. */
  blurb: string;
}

const SOURCES: SourceChoice[] = [
  {
    type: 'har',
    label: 'A recording of real traffic',
    blurb:
      'A HAR file captured from your browser while you used the application. The best source there ' +
      'is: it knows which requests actually happen and how often.',
  },
  {
    type: 'openapi',
    label: 'An API description',
    blurb: 'An OpenAPI or Swagger document, in YAML or JSON.',
  },
  {
    type: 'postman',
    label: 'A Postman collection',
    blurb: 'An exported collection, v2.0 or v2.1.',
  },
  {
    type: 'curl',
    label: 'A cURL command',
    blurb: 'One request, pasted straight from your terminal or your browser’s network tab.',
  },
];

/** The six steps that get a HAR out of a browser. Written for someone who has never done it. */
const HAR_STEPS = [
  'Open your application in Chrome or Edge, on the page where the journey starts.',
  'Press F12 to open developer tools, then choose the Network tab.',
  'Tick “Preserve log”, so navigating does not clear what you have captured.',
  'Do the journey you want to test, at a normal pace.',
  'Right-click anywhere in the request list and choose “Save all as HAR with content”.',
  'Drop that file below.',
];

export default function SourcesStep({
  client,
  draft,
  onChange,
}: {
  client: V2Client;
  draft: StudioDraft;
  onChange: (draft: StudioDraft) => void;
}) {
  const [sourceType, setSourceType] = useState<SourceType>('har');
  const [text, setText] = useState('');
  const [findings, setFindings] = useState<ValidationFinding[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  const choice = SOURCES.find((s) => s.type === sourceType) ?? SOURCES[0];

  async function read(document: string) {
    if (!document.trim()) return;
    setBusy(true);
    setError(null);
    try {
      const result = await client.importSource(sourceType, document);
      setFindings(result.findings);
      // Appended, not replaced: a test can legitimately draw on more than one source, and silently
      // discarding what was already there would lose work with no way to get it back.
      onChange({
        ...draft,
        journeys: [
          ...draft.journeys,
          ...journeysFromImport(result.scenarios, result.correlationSuggestions),
        ],
      });
    } catch (err) {
      setError(describeError(err));
      setFindings(null);
    } finally {
      setBusy(false);
    }
  }

  function addByHand() {
    const journey: Journey = {
      name: `request-${draft.journeys.length + 1}`,
      share: 100,
      method: 'GET',
      path: '/',
      headers: {},
      query: {},
      body: null,
      steps: [],
      correlationSuggestions: [],
      operationId: null,
      include: true,
    };
    onChange({ ...draft, journeys: [...draft.journeys, journey] });
  }

  const errors = findings?.filter((f) => f.severity === 'ERROR') ?? [];
  const rest = findings?.filter((f) => f.severity !== 'ERROR') ?? [];

  return (
    <>
      <div className="studio-field">
        <label htmlFor="source-type">What do you have?</label>
        <select
          id="source-type"
          value={sourceType}
          onChange={(e) => {
            setSourceType(e.target.value as SourceType);
            setFindings(null);
            setError(null);
          }}
        >
          {SOURCES.map((source) => (
            <option key={source.type} value={source.type}>
              {source.label}
            </option>
          ))}
        </select>
        <p className="studio-hint">{choice.blurb}</p>
      </div>

      {sourceType === 'har' && (
        <details className="advanced" open={draft.journeys.length === 0}>
          <summary>How do I get a recording?</summary>
          <ol className="studio-hint" style={{ lineHeight: 1.9, paddingLeft: '1.2rem' }}>
            {HAR_STEPS.map((instruction) => (
              <li key={instruction}>{instruction}</li>
            ))}
          </ol>
          <p className="studio-hint">
            <Info size={13} aria-hidden /> The platform removes cookies, passwords, tokens and
            third-party traffic before anything is stored, and tells you what it removed.
          </p>
        </details>
      )}

      <div className="studio-field">
        <label htmlFor="source-text">
          {sourceType === 'curl' ? 'Paste the command' : 'Paste the file, or choose it below'}
        </label>
        <textarea
          id="source-text"
          rows={sourceType === 'curl' ? 4 : 8}
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder={
            sourceType === 'curl'
              ? "curl 'https://example.com/api/orders' -H 'Accept: application/json'"
              : ''
          }
        />
      </div>

      <div className="card-actions">
        <button type="button" className="btn" disabled={busy || !text.trim()} onClick={() => read(text)}>
          {busy ? <Loader2 size={14} className="spin" /> : <FileUp size={14} />} Read this
        </button>

        <button type="button" className="btn btn-quiet" onClick={() => fileInput.current?.click()}>
          Choose a file
        </button>
        {/*
          Kept in the accessibility tree rather than `display: none`, so it stays reachable by
          keyboard and screen reader while the styled button is what everyone else sees.
        */}
        <input
          ref={fileInput}
          className="visually-hidden"
          type="file"
          accept=".har,.json,.yaml,.yml,.txt"
          aria-label="Choose a source file"
          onChange={async (e) => {
            const file = e.target.files?.[0];
            if (!file) return;
            const content = await file.text();
            setText(content);
            await read(content);
            e.target.value = '';
          }}
        />

        <button type="button" className="btn btn-quiet" onClick={addByHand}>
          <Plus size={14} /> Add a request by hand
        </button>
      </div>

      {error && (
        <div className="banner banner-bad" role="alert">
          <AlertCircle size={16} /> {error}
        </div>
      )}

      {findings !== null && (
        <>
          <p className="studio-summary">
            Added <strong>{draft.journeys.length}</strong> journey
            {draft.journeys.length === 1 ? '' : 's'}.{' '}
            {findings.length > 0 && (
              <>
                The platform changed or removed {findings.length} thing
                {findings.length === 1 ? '' : 's'} — worth reading before you run this.
              </>
            )}
          </p>

          {/*
            Errors first, everything else after. What an importer could not do is the difference
            between a draft and a test that measures something, so it gets room rather than a badge.
          */}
          <ul className="findings">
            {[...errors, ...rest].map((finding, i) => (
              <li className="finding" key={i}>
                {finding.severity === 'ERROR' ? (
                  <AlertCircle size={15} className="tone-bad" aria-hidden />
                ) : finding.severity === 'WARNING' ? (
                  <AlertTriangle size={15} className="tone-warn" aria-hidden />
                ) : (
                  <Info size={15} className="tone-neutral" aria-hidden />
                )}
                <div>
                  <p>{finding.message}</p>
                  {finding.remediation && <p className="muted">{finding.remediation}</p>}
                </div>
              </li>
            ))}
          </ul>
        </>
      )}
    </>
  );
}

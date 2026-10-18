import { useState } from 'react';
import {
  AlertTriangle,
  CheckCircle2,
  Download,
  HelpCircle,
  Loader2,
  XCircle,
} from 'lucide-react';
import type {
  Artifact,
  ObjectiveOutcome,
  OperationMetrics,
  RunReport as Report,
  WorkloadProfile,
} from './api';
import { V2Client, describeError } from './api';
import { objectiveLabel } from './labels';
import { summarize, METRIC_EXPLANATIONS } from './narrative';
import WebMetricsTable from './WebMetricsTable';

/**
 * One run's report.
 *
 * <p>Consumer language throughout (CLAUDE.md §24): expected users, ramp-up, journey mix,
 * response-time objective. Thread groups, samplers, JMX and Kubernetes appear nowhere except in the
 * collapsed "How the platform ran this" section, which §24 permits as an advanced read-only view.
 *
 * <h2>Nothing is rendered as a zero when it is missing</h2>
 * A run that has not been analyzed shows "nothing has been measured yet", not a table of zeros. A
 * zero is a measurement, and displaying one for a run that never ran describes a finished test that
 * found nothing wrong — which is the most flattering possible lie about an unmeasured system.
 */

/** Whole seconds as the durations people actually write: 2m, 90s, 1h 30m. */
function humanDuration(seconds: number): string {
  if (seconds <= 0) return '0s';
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const rest = seconds % 60;

  const parts: string[] = [];
  if (hours) parts.push(`${hours}h`);
  if (minutes) parts.push(`${minutes}m`);
  if (rest || parts.length === 0) parts.push(`${rest}s`);
  return parts.join(' ');
}

/** Thousands separated, so a six-digit request count is readable at a glance. */
const count = (value: number) => value.toLocaleString();

function VerdictIcon({ verdict }: { verdict: ObjectiveOutcome['verdict'] }) {
  if (verdict === 'PASS') return <CheckCircle2 size={15} className="tone-good" aria-hidden />;
  if (verdict === 'BREACH') return <XCircle size={15} className="tone-bad" aria-hidden />;
  return <HelpCircle size={15} className="tone-neutral" aria-hidden />;
}

/**
 * The banner that has to survive a screenshot.
 *
 * <p>Not a tooltip, not a footnote, not a muted aside. It is the first thing in the report, it is
 * high contrast, and it says plainly that the numbers below it describe nothing. The failure mode
 * being prevented is specific: a latency figure pasted into a ticket while the caveat stays behind.
 */
function SyntheticBanner({ notice }: { notice: string }) {
  return (
    <div className="synthetic-banner" role="alert">
      <AlertTriangle size={18} aria-hidden />
      <div>
        <strong>{notice}</strong>
        <p>
          This deployment is configured with an execution provider that generates no traffic. The
          figures below were invented so the platform could be exercised end to end. Every objective
          is reported as <em>not determined</em>, because nothing was measured.
        </p>
      </div>
    </div>
  );
}

/**
 * Objective verdicts, with the headline first.
 *
 * <p>The headline is deliberately not "passed unless something failed". It reads as demonstrated only
 * when the run generated real load, asked at least one question, and every answer was a pass — the
 * server computes that, and this only renders it.
 */
function Verdicts({ report }: { report: Report }) {
  const { summary, objectives } = report;

  return (
    <>
      <h3>Verdict</h3>
      {objectives.length === 0 ? (
        <p className="muted">
          {report.measurements
            ? 'This test declares no objectives, so there was no pass or fail to reach.'
            : 'Objectives are judged once the run finishes.'}
        </p>
      ) : (
        <>
          <p className={summary.demonstrated ? 'verdict verdict-good' : 'verdict verdict-neutral'}>
            {summary.demonstrated
              ? `Met every objective (${summary.passed} of ${objectives.length}).`
              : summary.breached > 0
                ? `${summary.breached} of ${objectives.length} objectives were not met.`
                : `No objective could be judged (${summary.indeterminate} of ${objectives.length}).`}
          </p>
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
        </>
      )}
    </>
  );
}

/** A numeric column header that carries what its number means. */
function Column({ label, explains }: { label: string; explains: keyof typeof METRIC_EXPLANATIONS }) {
  const explanation = METRIC_EXPLANATIONS[explains];
  return (
    <th scope="col" className="numeric">
      <abbr className="column-label" title={explanation}>
        {label}
      </abbr>
      <span className="sr-only"> — {explanation}</span>
    </th>
  );
}

/**
 * Response times per operation.
 *
 * <p>A table rather than a chart: these are values people look up and quote, and precise lookup is
 * what a table is for. The whole-run row is first and emphasized, because a whole-test objective is
 * judged against it.
 */
function LatencyTable({ metrics }: { metrics: OperationMetrics[] }) {
  return (
    <div className="table-scroll">
      <table className="metrics">
        <caption className="sr-only">
          Response times and error rates for the whole test and each journey
        </caption>
        <thead>
          <tr>
            <th scope="col">Journey</th>
            {/*
              Each column carries its own explanation. Where the number is, not in a glossary: the
              moment somebody needs to know what a 95th percentile is, is the moment they are
              looking at one. `title` for the pointer, and the same text is available to a screen
              reader through the visually hidden note.
            */}
            <Column label="Samples" explains="requests" />
            <Column label="Failed" explains="failed" />
            <Column label="Median" explains="p50" />
            <Column label="90th" explains="p90" />
            <Column label="95th" explains="p95" />
            <Column label="99th" explains="p99" />
            <Column label="Slowest" explains="max" />
            <Column label="Per second" explains="throughput" />
          </tr>
        </thead>
        <tbody>
          {metrics.map((operation) => (
            <tr key={operation.name} className={operation.name === '*' ? 'aggregate' : undefined}>
              <th scope="row">
                {operation.name === '*' ? 'Whole test' : operation.name}
              </th>
              <td className="numeric">{count(operation.sampleCount)}</td>
              <td className="numeric">
                {count(operation.errorCount)}
                <span className="muted"> ({operation.errorRatePercent.toFixed(2)}%)</span>
                {/* Surfaced rather than folded into the error count: these are requests whose
                    result could not be read at all, and V1 excluding them from the denominator is
                    exactly how its error rate improved the worse a run went. */}
                {operation.unparsedCount > 0 && (
                  <span className="tone-bad" title="Results that could not be read at all.">
                    {' '}
                    +{count(operation.unparsedCount)} unreadable
                  </span>
                )}
              </td>
              <td className="numeric">{count(operation.p50Millis)} ms</td>
              <td className="numeric">{count(operation.p90Millis)} ms</td>
              <td className="numeric emphasis">{count(operation.p95Millis)} ms</td>
              <td className="numeric">{count(operation.p99Millis)} ms</td>
              <td className="numeric">{count(operation.maxMillis)} ms</td>
              <td className="numeric">{operation.throughputPerSecond.toFixed(1)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * The load profile, as a shape.
 *
 * <p>One series — expected users over time — so magnitude, so bars, and no legend: the heading names
 * it. The bar is the platform's brand hue at a step validated against this surface for lightness,
 * chroma and contrast. Each bar carries its own number, so the encoding is never colour or length
 * alone, and the row is a real table row, so the values are readable without the bars at all.
 */
function StageProfile({ workload }: { workload: WorkloadProfile }) {
  const rate = workload.modelType === 'ARRIVAL_RATE';
  const target = (stage: WorkloadProfile['stages'][number]) => rate ? stage.targetRate ?? 0 : stage.targetUsers;
  const peak = Math.max(1, ...workload.stages.map(target));

  return (
    <div className="workload">
      <div className="workload-head">
        <strong>{workload.name}</strong>
        <span className="muted">
          {workload.type === 'BROWSER'
            ? `${count(workload.peakUsers)} browsers at once${workload.iterationsPerUser ? ` · ${workload.iterationsPerUser} journeys per browser` : ''}`
            : rate
              ? `${count(workload.peakArrivalRate ?? 0)} requests per second at peak · at most ${count(workload.maxConcurrency ?? workload.peakUsers)} requests in flight · ${humanDuration(workload.totalDurationSeconds)}`
              : `${count(workload.peakUsers)} expected users at peak · ${humanDuration(workload.totalDurationSeconds)}`}
        </span>
      </div>

      {workload.stages.length > 0 && (
        <div className="table-scroll">
          <table className="stages">
            <caption className="sr-only">{rate ? 'Requested rate' : 'Expected users'} at each stage of {workload.name}</caption>
            <thead>
              <tr>
                <th scope="col">For</th>
                <th scope="col">{rate ? 'Requests per second' : 'Expected users'}</th>
              </tr>
            </thead>
            <tbody>
              {workload.stages.map((stage, index) => (
                <tr key={index}>
                  <th scope="row">{humanDuration(stage.durationSeconds)}</th>
                  <td>
                    <span className="stage-row">
                      <span className="stage-track">
                        <span
                          className="stage-bar"
                          style={{ width: `${(target(stage) / peak) * 100}%` }}
                          aria-hidden
                        />
                      </span>
                      <span className="stage-value">{count(target(stage))}</span>
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {workload.scenarios.length > 0 && (
        <ul className="mix">
          {workload.scenarios.map((scenario) => (
            <li key={scenario.name}>
              <span className="objective-scope">{scenario.name}</span>
              <span className="muted">{scenario.weightPercent}% of journeys</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** Evidence, each downloaded through an authorized request rather than a stored link. */
function Evidence({
  client,
  runId,
  artifacts,
  onError,
}: {
  client: V2Client;
  runId: string;
  artifacts: Artifact[];
  onError: (message: string) => void;
}) {
  const [downloading, setDownloading] = useState<string | null>(null);

  const fileNameFor = (artifact: Artifact) =>
    artifact.objectKey.slice(artifact.objectKey.lastIndexOf('/') + 1);

  const download = async (artifact: Artifact) => {
    setDownloading(artifact.id);
    try {
      await client.downloadArtifact(runId, artifact, fileNameFor(artifact));
    } catch (err) {
      onError(describeError(err));
    } finally {
      setDownloading(null);
    }
  };

  return (
    <>
      <h3>Evidence</h3>
      <ul className="artifacts">
        {artifacts.map((artifact) => (
          <li key={artifact.id}>
            <button
              className="link link-download"
              onClick={() => download(artifact)}
              disabled={downloading === artifact.id}
            >
              {downloading === artifact.id ? (
                <Loader2 size={13} className="spin" aria-hidden />
              ) : (
                <Download size={13} aria-hidden />
              )}
              {fileNameFor(artifact)}
            </button>
            <span className="muted">{artifact.type.replace(/_/g, ' ').toLowerCase()}</span>
            <span className="muted">{(artifact.sizeBytes / 1024).toFixed(1)} KB</span>
          </li>
        ))}
      </ul>
      <p className="muted footnote">
        Each download is authorized when you ask for it and checked against the fingerprint recorded
        when the run produced it. No shareable link is created.
      </p>
    </>
  );
}

/**
 * The answer, before anything else.
 *
 * <p>The report below this is a good expert report and is unchanged. This exists for the reader who
 * has not met a 95th percentile before, and who would otherwise look at a table of numbers with no
 * way to tell whether to be pleased.
 *
 * <p>It is generated by {@link summarize}, whose tests pin the thing that matters: it can never
 * read as more reassuring than the data. A synthetic run says so and quotes no figure; an
 * indeterminate verdict never becomes a pass; a run missing a shard says that first.
 */
function Answer({ report }: { report: Report }) {
  const narrative = summarize(report);
  const Icon =
    narrative.tone === 'good' ? CheckCircle2 : narrative.tone === 'bad' ? XCircle : HelpCircle;

  return (
    <div className={`answer answer-${narrative.tone}`}>
      <p className="answer-headline">
        <Icon size={18} className={`tone-${narrative.tone === 'unknown' ? 'neutral' : narrative.tone}`} aria-hidden />
        {narrative.headline}
      </p>
      {narrative.detail.map((sentence) => (
        <p className="answer-detail" key={sentence}>
          {sentence}
        </p>
      ))}
    </div>
  );
}

export default function RunReport({
  client,
  report,
  onError,
}: {
  client: V2Client;
  report: Report;
  onError: (message: string) => void;
}) {
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const { measurements, test, execution } = report;

  return (
    <section className="card report">
      <header className="card-head">
        <h2>Report</h2>
        {test && (
          <span className="muted">
            {test.name} · version {test.versionNumber} · {test.application}
          </span>
        )}
      </header>

      {/* First, before any number. */}
      {report.synthetic && report.syntheticNotice && (
        <SyntheticBanner notice={report.syntheticNotice} />
      )}

      <Answer report={report} />

      <Verdicts report={report} />

      <h3>What was measured</h3>
      {!measurements ? (
        <p className="muted">
          Nothing has been measured yet. Results appear once the run has finished and been analyzed.
        </p>
      ) : (
        <>
          {/* A partial result set describes a smaller test than the one that was planned. Said
              plainly rather than left for someone to notice in a shard count. */}
          {!measurements.complete && (
            <div className="banner banner-bad" role="alert">
              <AlertTriangle size={16} aria-hidden />
              <span>
                Only {measurements.shardsReported} of {measurements.shardsExpected} load generators
                reported. These figures describe less load than the test asked for, so no objective
                could be judged.
              </span>
            </div>
          )}
          <LatencyTable metrics={[measurements.overall, ...measurements.operations.filter(m => !m.name.startsWith('web.'))]} />
          <WebMetricsTable metrics={measurements.operations.filter(m => m.name.startsWith('web.'))} />
        </>
      )}

      {test && test.workloads.length > 0 && (
        <>
          <h3>The load this test applies</h3>
          {test.workloads.map((workload) => (
            <StageProfile key={workload.name} workload={workload} />
          ))}
        </>
      )}

      {report.artifacts.length > 0 && (
        <Evidence
          client={client}
          runId={report.run.id}
          artifacts={report.artifacts}
          onError={onError}
        />
      )}

      {/*
        The platform's own view, collapsed. CLAUDE.md §24 permits an advanced read-only section for
        experts and forbids putting engine and runner detail on the ordinary path — so it is here,
        closed, and nothing above depends on opening it.
      */}
      {execution && (
        <details
          className="advanced"
          open={advancedOpen}
          onToggle={(event) => setAdvancedOpen(event.currentTarget.open)}
        >
          <summary>How the platform ran this</summary>
          <dl>
            <dt>Engine</dt>
            <dd>{execution.engine}</dd>
            <dt>Load generators</dt>
            <dd>{execution.shardCount}</dd>
            <dt>Peak concurrency requested</dt>
            <dd>{count(execution.peakVirtualUsers)}</dd>
            <dt>Planned duration</dt>
            <dd>{humanDuration(execution.estimatedDurationSeconds)}</dd>
            {/* The answer to "are these results from the test that was approved". Results whose
                digest does not match the run's plan are rejected rather than merged. */}
            <dt>Plan fingerprint</dt>
            <dd>
              <code className="digest">{execution.planDigest}</code>
            </dd>
            {test && (
              <>
                <dt>Test fingerprint</dt>
                <dd>
                  <code className="digest">{test.digest}</code>
                </dd>
              </>
            )}
          </dl>
        </details>
      )}
    </section>
  );
}

import {
  Activity,
  AlertOctagon,
  AlertTriangle,
  CheckCircle2,
  Info,
  Radio,
  Users,
} from 'lucide-react';
import type { Observation, RunProgress } from './api';
import {
  formatAge,
  formatMillis,
  formatPercent,
  formatRate,
  freshness,
  quietShards,
  rankedOperations,
  sparkline,
} from './liveProgress';

/**
 * What a run is doing, while it is doing it.
 *
 * Until this existed a run in flight showed a state and nothing else. `Running` was true for twenty
 * minutes and said nothing about whether the error rate was climbing, whether the load generators
 * were still alive, or whether the test was measuring anything at all. For an experienced engineer
 * that is tolerable. For someone on their first load test it is a spinner followed by a verdict they
 * have no context for.
 *
 * ## Three rules this screen holds to
 *
 * **It never claims to be current when it is not.** The freshness badge is the first thing rendered
 * and it stops saying "Live" the moment the numbers stop arriving. Sixty-second-old figures
 * presented as now are worse than no figures: somebody makes a decision from them.
 *
 * **Every message carries its numbers.** The observations come from server-side rules, each one
 * pinned to an observation, and the evidence is shown beside the message rather than hidden behind
 * it. A live view read by a novice must not be able to say something confident and unsupported.
 *
 * **Nothing here is the verdict.** Percentiles are bucket upper bounds and counts come from partial
 * output, so the objectives are labelled provisional in the heading, in the note under it, and in
 * each row's own detail text — because a screenshot of one row is what actually travels.
 */
export default function LiveMonitor({ progress }: { progress: RunProgress | null }) {
  if (!progress) return null;

  const current = freshness(progress.ageSeconds);
  const quiet = new Set(quietShards(progress));
  const operations = rankedOperations(progress);
  const series = progress.series;

  return (
    <section className="live-monitor" aria-label="Live measurements">
      <header className="live-head">
        <h3>
          <Activity size={16} aria-hidden /> Right now
        </h3>
        <span className={`live-badge live-${current.state}`} role="status">
          <Radio size={12} aria-hidden /> {current.label}
        </span>
      </header>

      {/*
        Observations first, above the numbers. Somebody scanning this screen while a test runs is
        looking for a reason to act; making them derive it from four tiles is how a climbing error
        rate goes unnoticed for another ten minutes.
      */}
      {progress.observations.length > 0 && (
        <ul className="observations">
          {progress.observations.map((observation) => (
            <li key={observation.code} className={`observation obs-${observation.severity.toLowerCase()}`}>
              <ObservationIcon severity={observation.severity} />
              <div>
                <strong>{observation.message}</strong>
                <span className="muted">{observation.evidence}</span>
              </div>
            </li>
          ))}
        </ul>
      )}

      <div className="live-tiles">
        <Tile
          label="Requests per second"
          value={formatRate(progress.overall.throughputPerSecondMilli)}
          hint="Measured over the last few seconds, not averaged over the whole run."
          series={sparkline(series, (point) => point.throughputPerSecondMilli, 120, 28)}
        />
        <Tile
          label="Response time (p95)"
          value={formatMillis(progress.overall.p95Millis)}
          hint="95 in every 100 requests were at least this fast. An upper bound while the run is going: the exact figure comes from the full results at the end."
          series={sparkline(series, (point) => point.p95Millis, 120, 28)}
        />
        <Tile
          label="Failing"
          value={`${formatPercent(progress.overall.errorRateMilliPercent)}%`}
          hint="Requests the target did not answer successfully. Failures are usually fast, so a high figure here makes the response times above look better than the truth."
          series={sparkline(series, (point) => point.errorRateMilliPercent, 120, 28)}
          tone={progress.overall.errorRateMilliPercent >= 5000 ? 'bad' : undefined}
        />
        <Tile
          label="Users applying load"
          value={progress.activeUsers.toLocaleString()}
          hint="What the generators report they are actually running — not what the plan's stage asked for."
          icon={<Users size={13} aria-hidden />}
        />
      </div>

      <p className="muted live-total">
        {progress.overall.sampleCount.toLocaleString()} requests measured so far
        {progress.overall.unparsedCount > 0 && (
          <>
            {' '}
            · {progress.overall.unparsedCount.toLocaleString()}{' '}
            {progress.overall.unparsedCount === 1 ? 'row' : 'rows'} the generator could not read
          </>
        )}
        {' · '}
        {progress.shardsReporting} of {progress.shardsExpected} generator
        {progress.shardsExpected === 1 ? '' : 's'} reporting
      </p>

      {operations.length > 0 && (
        <>
          <h4>By journey</h4>
          <div className="table-scroll">
            <table className="live-table">
              <thead>
                <tr>
                  <th scope="col">Journey</th>
                  <th scope="col">Requests</th>
                  <th scope="col">Failing</th>
                  <th scope="col">p95</th>
                  <th scope="col">Per second</th>
                </tr>
              </thead>
              <tbody>
                {operations.map((operation) => (
                  <tr key={operation.name}>
                    <td>{operation.name}</td>
                    <td>{operation.sampleCount.toLocaleString()}</td>
                    <td className={operation.errorRateMilliPercent >= 5000 ? 'bad' : undefined}>
                      {formatPercent(operation.errorRateMilliPercent)}%
                    </td>
                    <td>{formatMillis(operation.p95Millis)}</td>
                    <td>{formatRate(operation.throughputPerSecondMilli)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      {/*
        The generators, shown separately from the totals and always — not only when one goes quiet.
        Added totals cannot show a generator that stopped: the survivors keep adding to them, so a
        run that lost half its load reads as a run that is merely slower.
      */}
      {progress.shards.length > 0 && (
        <>
          <h4>Load generators</h4>
          <div className="table-scroll">
            <table className="live-table">
              <thead>
                <tr>
                  <th scope="col">Generator</th>
                  <th scope="col">Last reported</th>
                  <th scope="col">Requests</th>
                  <th scope="col">Failed</th>
                  <th scope="col">Users</th>
                </tr>
              </thead>
              <tbody>
                {progress.shards.map((shard) => (
                  <tr key={shard.shardIndex} className={quiet.has(shard.shardIndex) ? 'row-bad' : undefined}>
                    <td>#{shard.shardIndex}</td>
                    <td className={quiet.has(shard.shardIndex) ? 'bad' : undefined}>
                      {formatAge(shard.ageSeconds)}
                    </td>
                    <td>{shard.sampleCount.toLocaleString()}</td>
                    <td>{shard.errorCount.toLocaleString()}</td>
                    <td>{shard.activeUsers.toLocaleString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      {progress.provisionalObjectives.length > 0 && (
        <>
          <h4>Objectives, so far</h4>
          <p className="muted">
            Judged against the numbers above, which are partial. This is not the run's result — that
            is worked out from the complete measurements once every generator has finished.
          </p>
          <ul className="objectives">
            {progress.provisionalObjectives.map((objective) => (
              <li key={objective.objectiveId}>
                <ObjectiveIcon verdict={objective.verdict} />
                <span className="objective-scope">{objective.metric}</span>
                <span className="muted">{objective.detail}</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}

/**
 * One headline number, with a sparkline where a trend exists.
 *
 * The hint is on the tile rather than in a legend somewhere: the audience is somebody who has not
 * run a load test before, and "p95" is meaningless to them until something says what it counts.
 */
function Tile({
  label,
  value,
  hint,
  series,
  tone,
  icon,
}: {
  label: string;
  value: string;
  hint: string;
  series?: string;
  tone?: 'bad';
  icon?: React.ReactNode;
}) {
  return (
    <div className={tone === 'bad' ? 'live-tile live-tile-bad' : 'live-tile'}>
      <span className="live-tile-label" title={hint}>
        {icon} {label}
      </span>
      <strong className="live-tile-value">{value}</strong>
      {series ? (
        <svg
          className="sparkline"
          viewBox="0 0 120 28"
          preserveAspectRatio="none"
          role="img"
          aria-label={`${label} over the run so far`}
        >
          <path d={series} fill="none" strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
        </svg>
      ) : (
        <span className="sparkline-placeholder" aria-hidden />
      )}
      <span className="live-tile-hint muted">{hint}</span>
    </div>
  );
}

function ObservationIcon({ severity }: { severity: Observation['severity'] }) {
  if (severity === 'CRITICAL') return <AlertOctagon size={15} aria-label="Serious" />;
  if (severity === 'WARNING') return <AlertTriangle size={15} aria-label="Worth checking" />;
  return <Info size={15} aria-label="Information" />;
}

function ObjectiveIcon({ verdict }: { verdict: string }) {
  if (verdict === 'BREACH') return <AlertTriangle size={14} className="bad" aria-label="Not met" />;
  if (verdict === 'PASS') return <CheckCircle2 size={14} className="good" aria-label="Met" />;
  return <Info size={14} className="muted" aria-label="Not determined" />;
}

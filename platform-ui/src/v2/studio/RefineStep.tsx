import { Trash2 } from 'lucide-react';
import type { StudioDraft } from './draft';
import { includedJourneys, normalizeShares } from './draft';

/**
 * Keep what matters, drop the noise, and decide the traffic mix.
 *
 * <p>Every imported journey arrives here, including ones an importer clearly did not intend to be
 * run. Nothing is dropped silently: a journey missing from this table is a decision nobody can see,
 * and the whole point of the step is that a person looks at what will be sent before it is sent.
 *
 * <p>Shares are shown as they will actually be apportioned, not as they were imported. A table
 * reading 40/40/40 while the platform runs 33/33/34 would be describing a different test from the
 * one about to execute.
 */
export default function RefineStep({
  draft,
  onChange,
}: {
  draft: StudioDraft;
  onChange: (draft: StudioDraft) => void;
}) {
  const kept = includedJourneys(draft);
  // What the platform will actually run, so the column and the test agree.
  const apportioned = new Map(
    normalizeShares(kept).map((journey) => [journey.name, journey.share]),
  );

  const update = (position: number, patch: Partial<StudioDraft['journeys'][number]>) =>
    onChange({
      ...draft,
      journeys: draft.journeys.map((journey, i) =>
        i === position ? { ...journey, ...patch } : journey,
      ),
    });

  return (
    <>
      <p className="studio-hint">
        {kept.length} of {draft.journeys.length} journeys will run. Traffic is split between them in
        the proportions below — the platform adjusts them to add up to 100%.
      </p>

      <div className="table-scroll">
        <table className="metrics">
          <caption className="sr-only">
            Imported journeys, whether each will run, and its share of traffic
          </caption>
          <thead>
            <tr>
              <th scope="col">Run</th>
              <th scope="col">Journey</th>
              <th scope="col">Request</th>
              <th scope="col" className="numeric">Share of traffic</th>
              <th scope="col"><span className="sr-only">Remove</span></th>
            </tr>
          </thead>
          <tbody>
            {draft.journeys.map((journey, position) => (
              <tr key={position} className={journey.include ? undefined : 'muted'}>
                <td>
                  <input
                    type="checkbox"
                    checked={journey.include}
                    onChange={(e) => update(position, { include: e.target.checked })}
                    aria-label={`Include ${journey.name}`}
                  />
                </td>
                <td>
                  <input
                    type="text"
                    value={journey.name}
                    onChange={(e) => update(position, { name: e.target.value })}
                    aria-label={`Name of journey ${position + 1}`}
                  />
                </td>
                <td>
                  {journey.steps.length > 0 ? (
                    <>
                      <code>{journey.steps.length} ordered steps</code>
                      <span className="muted">
                        {' '}
                        from {journey.steps[0].method} {journey.steps[0].path}
                      </span>
                    </>
                  ) : (
                    <code>
                      {journey.method} {journey.path}
                    </code>
                  )}
                  {Object.keys(journey.headers).length > 0 && (
                    <span className="muted"> +{Object.keys(journey.headers).length} header
                      {Object.keys(journey.headers).length === 1 ? '' : 's'}</span>
                  )}
                  {journey.body && <span className="muted"> + body</span>}
                </td>
                <td className="numeric">
                  {journey.include ? (
                    <>
                      <input
                        type="number"
                        min={1}
                        value={journey.share}
                        onChange={(e) => update(position, { share: Number(e.target.value) })}
                        aria-label={`Share of traffic for ${journey.name}`}
                        style={{ width: '4.5rem' }}
                      />{' '}
                      {/*
                        The apportioned figure beside the typed one, when they differ. Someone who
                        typed 40 and will get 33 should be able to see that here rather than discover
                        it in a report.
                      */}
                      {apportioned.get(journey.name) !== journey.share && (
                        <span className="muted">→ {apportioned.get(journey.name)}%</span>
                      )}
                    </>
                  ) : (
                    <span className="muted">—</span>
                  )}
                </td>
                <td>
                  <button
                    type="button"
                    className="link"
                    onClick={() =>
                      onChange({
                        ...draft,
                        journeys: draft.journeys.filter((_, i) => i !== position),
                      })
                    }
                    aria-label={`Remove ${journey.name}`}
                  >
                    <Trash2 size={14} aria-hidden />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}

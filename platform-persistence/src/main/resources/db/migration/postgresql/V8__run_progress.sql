-- V8: what a run looked like while it was still running.
--
-- Until now a run in flight showed a state and nothing else. `RUNNING` was true for twenty minutes
-- and said nothing about whether the error rate was climbing or the load generators were still
-- alive, so the only way to learn a test was measuring nothing was to wait for it to finish and read
-- a verdict with no context.
--
-- Each row is one merged snapshot: every shard's rolling `progress.json`, added together by the
-- reconciler on one pass. A series rather than a single upserted row, because rate of change is the
-- signal — an error rate that doubled in the last minute, a p95 past a knee, throughput that stopped
-- climbing when users did not. A latest-only table can show a number and never a trend, and the
-- trend is what tells somebody whether to stop the test.
--
-- ── This is not evidence, and the distinction is load-bearing ────────────────
--
-- Everything here is reduced from partial runner output, with percentiles read off bucket edges
-- rather than from the samples themselves. A run's actual result is what the analyzer computes from
-- the complete raw results after every shard has finished, and nothing in this table ever becomes
-- that. Losing these rows costs a chart, never a verdict, which is what makes it safe to prune them
-- on a retention schedule while artifacts are kept.
--
-- They are kept after a run reaches a terminal state anyway: a run that died leaves its last
-- snapshot as the only account of what was happening at the time.
CREATE TABLE run_progress (
    id                          UUID          NOT NULL,
    run_id                      UUID          NOT NULL,

    -- Which engine's shards reported. Self-describing for the same reason run_metrics.engine is:
    -- a throughput figure whose engine can only be found by joining another table stops meaning
    -- anything if that row is ever pruned.
    engine                      VARCHAR(64)   NOT NULL,

    -- The freshest shard capture in this merge, by the runner's clock, and when the control plane
    -- merged it, by its own. Both, because the gap between them is the only way to tell a stale
    -- number from a current one when the two clocks disagree.
    captured_at                 TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    recorded_at                 TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    shards_expected             INTEGER       NOT NULL,
    shards_reporting            INTEGER       NOT NULL,

    elapsed_seconds             BIGINT        NOT NULL,
    active_users                BIGINT        NOT NULL,

    -- The aggregate, lifted into columns so the common reads — the latest point, a sparkline over
    -- the series — never parse a document. Everything else lives in detail_json.
    sample_count                BIGINT        NOT NULL,
    error_count                 BIGINT        NOT NULL,
    unparsed_count              BIGINT        NOT NULL,
    throughput_per_second_milli BIGINT        NOT NULL,
    p95_millis                  BIGINT        NOT NULL,

    -- The parts that are genuinely document-shaped: the merged latency buckets, the per-operation
    -- breakdown, and each shard's own last capture time. Modelled as a document rather than as three
    -- child tables because a row is written every few seconds per run and is read whole or not at
    -- all — three joins per point would buy nothing and cost the write path.
    --
    -- TEXT rather than JSONB, matching execution_plans.plan_json: nothing queries inside it, and a
    -- text column round-trips byte-for-byte, which a normalising JSON type does not promise.
    detail_json                 TEXT          NOT NULL,

    CONSTRAINT pk_run_progress PRIMARY KEY (id),
    CONSTRAINT fk_run_progress_run FOREIGN KEY (run_id) REFERENCES test_runs (id),

    -- One point per capture instant. Reconciliation repeats and may re-read the same runner
    -- snapshot; a second identical point would make a flat stretch look like sustained activity.
    CONSTRAINT uq_run_progress_run_captured UNIQUE (run_id, captured_at),

    CONSTRAINT ck_run_progress_counts CHECK (
        sample_count >= 0 AND error_count >= 0 AND unparsed_count >= 0
        AND error_count <= sample_count),
    CONSTRAINT ck_run_progress_shards CHECK (
        shards_expected >= 1 AND shards_reporting >= 0),
    CONSTRAINT ck_run_progress_rates CHECK (
        throughput_per_second_milli >= 0 AND p95_millis >= 0
        AND elapsed_seconds >= 0 AND active_users >= 0)
);

-- Ordered reads of one run's series, which is every read this table has.
CREATE INDEX ix_run_progress_run ON run_progress (run_id, captured_at);

COMMENT ON TABLE run_progress IS
    'Merged rolling snapshots from a run''s runners while it executed. Derived and provisional: '
    'percentiles are bucket upper bounds and counts come from partial output. Never the source of a '
    'run''s verdict, which is computed from the complete results in run_metrics.';

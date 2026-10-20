-- V5: the measurements a run produced, and whether any load was generated to produce them.
--
-- Two changes that belong together, because they are the two halves of an honest report: what was
-- measured, and whether the measurement means anything.


-- ── Synthetic marking ────────────────────────────────────────────────────────
--
-- A development execution provider generates no load and invents its results. Until now the only
-- record of that was a field inside each run's runner-manifest.json, which someone had to know
-- existed, know to open, and know to read. The number in a report travels into tickets and status
-- updates; a caveat that lives in a separate file does not travel with it.
--
-- Carried on the run so every read path — status, objectives, report — sees it without a join, and
-- so it survives artifact retention expiring.
--
-- Set from the provider's own declaration (ExecutionProvider.generatesRealLoad), never from a string
-- comparison on a provider name: a name check silently stops working the moment a provider is added.
--
-- DEFAULT false is correct for the rows already here. Every existing run in a deployment of this
-- version predates the flag, and defaulting them to "synthetic" would be a false accusation against
-- runs that may well have generated real load. The flag means "known to be synthetic", not "unknown".
ALTER TABLE test_runs
    ADD COLUMN synthetic BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN test_runs.synthetic IS
    'True when the execution provider generates no load, making every measurement invented. '
    'Monotonic: never cleared. Such a run reports INDETERMINATE objectives and can never pass.';


-- ── Run metrics ──────────────────────────────────────────────────────────────
--
-- One row per operation, plus one for the whole-run aggregate under the reserved name '*'.
--
-- Until now these numbers were computed during analysis and thrown away; only the objective verdicts
-- were kept. That made a verdict readable months later but left nobody able to see the latency
-- distribution behind it. Persisting them rather than recomputing on read is deliberate: a report
-- that stops working once artifact retention expires is not a report, and re-reducing a large JTL on
-- every read puts that cost on the path a person is waiting on.
--
-- Recomputation from the stored results file stays available as a verification path — a disputed
-- number can still be settled by re-deriving it — and one test asserts the stored values match a
-- recomputation exactly.
--
-- Every value is an integer for the reason the rest of the platform's measurements are: these decide
-- pass or fail, and a comparison that can differ in the last bit between two reads is not a verdict.
CREATE TABLE run_metrics (
    id                         UUID          NOT NULL,
    run_id                     UUID          NOT NULL,

    -- The operation these measurements describe, as 'workload/scenario'. '*' is the aggregate
    -- across every operation in the run, which is what a whole-test objective is judged against.
    operation                  VARCHAR(255)  NOT NULL,

    -- Which engine produced the samples. Duplicated per row rather than joined from execution_plans
    -- so a metrics row is self-describing: a p95 whose engine can only be discovered by joining
    -- another table is a number that stops meaning anything if that row is ever pruned.
    engine                     VARCHAR(64)   NOT NULL,

    sample_count               BIGINT        NOT NULL,
    error_count                BIGINT        NOT NULL,

    -- Rows the engine could not read at all. Counted separately and kept in the error-rate
    -- denominator: V1 treated a malformed row as neither a sample nor an error, which quietly
    -- improved the reported error rate the more badly a run went (defect D5).
    unparsed_count             BIGINT        NOT NULL,

    min_millis                 BIGINT        NOT NULL,
    max_millis                 BIGINT        NOT NULL,
    mean_millis                BIGINT        NOT NULL,
    p50_millis                 BIGINT        NOT NULL,
    p90_millis                 BIGINT        NOT NULL,
    p95_millis                 BIGINT        NOT NULL,
    p99_millis                 BIGINT        NOT NULL,

    -- Requests per second scaled by 1,000, so it stays exact. Never a double.
    throughput_per_second_milli BIGINT       NOT NULL,

    -- Completeness travels with the measurements, because a number reduced from a partial result set
    -- describes a smaller test than the one that was planned. Stored per row rather than on the run
    -- so that reading a metric never requires trusting a second lookup to know it is whole.
    shards_expected            INTEGER       NOT NULL,
    shards_reported            INTEGER       NOT NULL,

    -- The sample window: first sample to last. Null when nothing was measured.
    measured_from              TIMESTAMP(6) WITH TIME ZONE,
    measured_to                TIMESTAMP(6) WITH TIME ZONE,

    recorded_at                TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_run_metrics PRIMARY KEY (id),
    CONSTRAINT fk_run_metrics_run FOREIGN KEY (run_id) REFERENCES test_runs (id),

    -- One row per operation per run. Re-analysis replaces the set rather than appending to it; a
    -- report showing one operation twice with different numbers is worse than no report.
    CONSTRAINT uq_run_metrics_run_operation UNIQUE (run_id, operation),

    CONSTRAINT ck_run_metrics_counts CHECK (
        sample_count >= 0 AND error_count >= 0 AND unparsed_count >= 0
        AND error_count <= sample_count),
    CONSTRAINT ck_run_metrics_latencies CHECK (
        min_millis >= 0 AND max_millis >= 0 AND mean_millis >= 0
        AND p50_millis >= 0 AND p90_millis >= 0 AND p95_millis >= 0 AND p99_millis >= 0),
    CONSTRAINT ck_run_metrics_throughput CHECK (throughput_per_second_milli >= 0),
    CONSTRAINT ck_run_metrics_shards CHECK (
        shards_expected >= 1 AND shards_reported >= 0 AND shards_reported <= shards_expected)
);

CREATE INDEX ix_run_metrics_run ON run_metrics (run_id, operation);

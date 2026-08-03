-- H2 variant. See the PostgreSQL migration for the rationale behind each column.
--
-- One deliberate difference: no COMMENT ON. The document column is TEXT in both dialects, as
-- execution_plans.plan_json already is — nothing queries inside it, and it is read whole.

CREATE TABLE run_progress (
    id                          UUID          NOT NULL,
    run_id                      UUID          NOT NULL,
    engine                      VARCHAR(64)   NOT NULL,
    captured_at                 TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    recorded_at                 TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    shards_expected             INTEGER       NOT NULL,
    shards_reporting            INTEGER       NOT NULL,
    elapsed_seconds             BIGINT        NOT NULL,
    active_users                BIGINT        NOT NULL,
    sample_count                BIGINT        NOT NULL,
    error_count                 BIGINT        NOT NULL,
    unparsed_count              BIGINT        NOT NULL,
    throughput_per_second_milli BIGINT        NOT NULL,
    p95_millis                  BIGINT        NOT NULL,
    detail_json                 TEXT          NOT NULL,

    CONSTRAINT pk_run_progress PRIMARY KEY (id),
    CONSTRAINT fk_run_progress_run FOREIGN KEY (run_id) REFERENCES test_runs (id),
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

CREATE INDEX ix_run_progress_run ON run_progress (run_id, captured_at);

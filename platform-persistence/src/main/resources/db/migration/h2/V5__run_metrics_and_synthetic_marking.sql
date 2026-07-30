-- H2 variant. See the PostgreSQL migration for the rationale behind each column.
--
-- One deliberate difference: no COMMENT ON COLUMN. H2 supports it, but the comment is documentation
-- for whoever inspects the production schema, and duplicating prose across two dialects is how the
-- two drift apart.

ALTER TABLE test_runs
    ADD COLUMN synthetic BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE run_metrics (
    id                          UUID          NOT NULL,
    run_id                      UUID          NOT NULL,
    operation                   VARCHAR(255)  NOT NULL,
    engine                      VARCHAR(64)   NOT NULL,
    sample_count                BIGINT        NOT NULL,
    error_count                 BIGINT        NOT NULL,
    unparsed_count              BIGINT        NOT NULL,
    min_millis                  BIGINT        NOT NULL,
    max_millis                  BIGINT        NOT NULL,
    mean_millis                 BIGINT        NOT NULL,
    p50_millis                  BIGINT        NOT NULL,
    p90_millis                  BIGINT        NOT NULL,
    p95_millis                  BIGINT        NOT NULL,
    p99_millis                  BIGINT        NOT NULL,
    throughput_per_second_milli BIGINT        NOT NULL,
    shards_expected             INTEGER       NOT NULL,
    shards_reported             INTEGER       NOT NULL,
    measured_from               TIMESTAMP(6) WITH TIME ZONE,
    measured_to                 TIMESTAMP(6) WITH TIME ZONE,
    recorded_at                 TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_run_metrics PRIMARY KEY (id),
    CONSTRAINT fk_run_metrics_run FOREIGN KEY (run_id) REFERENCES test_runs (id),
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

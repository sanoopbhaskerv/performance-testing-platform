-- V1 baseline: adopt the schema Hibernate's ddl-auto:update previously created.
--
-- Until now the shape of test_runs was a side effect of editing a JPA entity, with no review,
-- no history and no way to roll forward deliberately. Flyway takes ownership from here so every
-- schema change is a reviewed artifact. This migration reproduces the existing table exactly, so
-- adopting Flyway is not itself a schema change and V1 keeps working unchanged.
--
-- Existing deployments already carry this table from ddl-auto. Set
-- spring.flyway.baseline-on-migrate=true there so Flyway adopts the current state instead of
-- trying to recreate it. See docs/migration/v1-to-v2.md.
--
-- The V2 replacement (idempotency key, execution plan reference, optimistic lock, cancellation
-- metadata, run events) arrives in Increment 1C. This table is not extended here.

CREATE TABLE IF NOT EXISTS test_runs (
    id                        VARCHAR(255)  NOT NULL,
    team                      VARCHAR(255)  NOT NULL,
    test_type                 VARCHAR(255),
    status                    VARCHAR(255),
    sla_result                VARCHAR(255),
    started_at                TIMESTAMP(6) WITH TIME ZONE,
    completed_at              TIMESTAMP(6) WITH TIME ZONE,
    created_at                TIMESTAMP(6) WITH TIME ZONE,
    p95latency_ms             BIGINT,
    error_rate_percent        DOUBLE PRECISION,
    peak_heap_usage_percent   INTEGER,
    report_path               VARCHAR(255),
    failure_reason            VARCHAR(2000),
    heap_dump_report_path     VARCHAR(255),
    thread_dump_report_path   VARCHAR(255),

    CONSTRAINT pk_test_runs PRIMARY KEY (id),
    CONSTRAINT ck_test_runs_test_type  CHECK (test_type IN ('LOAD', 'ENDURANCE', 'SPIKE')),
    CONSTRAINT ck_test_runs_status     CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_test_runs_sla_result CHECK (sla_result IN ('PASS', 'BREACH', 'UNKNOWN'))
);

-- The V1 history endpoint filters by team and orders by creation time on every call.
CREATE INDEX IF NOT EXISTS ix_test_runs_team_created_at ON test_runs (team, created_at DESC);

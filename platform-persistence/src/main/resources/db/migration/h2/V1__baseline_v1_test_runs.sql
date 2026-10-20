-- H2 variant of the V1 baseline. See the PostgreSQL migration for the rationale.
--
-- H2 is a local-development convenience only. PostgreSQL is the authoritative database and the
-- one integration tests run against (CLAUDE.md §12, §25): H2 will not reproduce PostgreSQL's
-- locking semantics, and a green H2 test proves nothing about concurrent queue claiming.

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

CREATE INDEX IF NOT EXISTS ix_test_runs_team_created_at ON test_runs (team, created_at DESC);

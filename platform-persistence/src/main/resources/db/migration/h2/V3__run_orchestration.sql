-- H2 variant of the run orchestration schema. See the PostgreSQL migration for the rationale
-- behind each table and column.
--
-- Three deliberate differences, all consequences of H2's capabilities:
--
--   1. plan_json and detail are H2's JSON type rather than JSONB. H2 cannot index inside them, so
--      the querying those columns exist for is only exercised against PostgreSQL.
--
--   2. There are no append-only or immutability triggers. PostgreSQL rejects an UPDATE or DELETE of
--      a run event or an execution plan at the database level; H2 keeps only the application-level
--      halves of those guarantees. This is exactly why those integration tests must run against
--      PostgreSQL via Testcontainers — proving them on H2 would prove the weaker claim.
--
--   3. H2 is single-instance by definition, so the concurrent-claim guarantees this schema is
--      shaped around cannot be demonstrated on it at all. The orchestration_locks row exists here
--      so the same adapter code runs unchanged locally, not because it proves anything.

ALTER TABLE test_runs RENAME TO v1_test_runs;
ALTER INDEX ix_test_runs_team_created_at RENAME TO ix_v1_test_runs_team_created_at;

-- Constraint names are schema-global in H2, so renaming the table is not enough: the V2 table
-- below declares constraints with the same names and would collide with the ones the rename left
-- behind. PostgreSQL scopes them per table and needs only the primary key renamed, which is why
-- the two migrations differ here.
ALTER TABLE v1_test_runs RENAME CONSTRAINT pk_test_runs TO pk_v1_test_runs;
ALTER TABLE v1_test_runs RENAME CONSTRAINT ck_test_runs_test_type TO ck_v1_test_runs_test_type;
ALTER TABLE v1_test_runs RENAME CONSTRAINT ck_test_runs_status TO ck_v1_test_runs_status;
ALTER TABLE v1_test_runs RENAME CONSTRAINT ck_test_runs_sla_result TO ck_v1_test_runs_sla_result;

CREATE TABLE execution_plans (
    id                          UUID          NOT NULL,
    definition_version_id       UUID          NOT NULL,
    engine                      VARCHAR(32)   NOT NULL,
    -- TEXT, matching PostgreSQL: the plan digest is taken over these exact bytes, and a JSON column
    -- reorders keys on the way back out.
    plan_json                   TEXT          NOT NULL,
    plan_digest                 VARCHAR(80)   NOT NULL,
    shard_count                 INTEGER       NOT NULL,
    peak_virtual_users          INTEGER       NOT NULL,
    estimated_duration_seconds  BIGINT        NOT NULL,
    created_at                  TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_execution_plans PRIMARY KEY (id),
    CONSTRAINT fk_execution_plans_version
        FOREIGN KEY (definition_version_id) REFERENCES test_definition_versions (id),
    CONSTRAINT ck_execution_plans_shard_count CHECK (shard_count >= 1),
    CONSTRAINT ck_execution_plans_users CHECK (peak_virtual_users >= 0),
    CONSTRAINT ck_execution_plans_duration CHECK (estimated_duration_seconds >= 0)
);

CREATE INDEX ix_execution_plans_digest ON execution_plans (plan_digest);

CREATE TABLE test_runs (
    id                         UUID          NOT NULL,
    team_id                    UUID          NOT NULL,
    definition_version_id      UUID          NOT NULL,
    execution_plan_id          UUID,
    status                     VARCHAR(32)   NOT NULL,
    environment                VARCHAR(63)   NOT NULL,

    idempotency_key            VARCHAR(200)  NOT NULL,
    request_digest             VARCHAR(80)   NOT NULL,
    requested_by               VARCHAR(255)  NOT NULL,

    requested_at               TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    queued_at                  TIMESTAMP(6) WITH TIME ZONE,
    started_at                 TIMESTAMP(6) WITH TIME ZONE,
    completed_at               TIMESTAMP(6) WITH TIME ZONE,

    cancellation_requested_by  VARCHAR(255),
    cancellation_requested_at  TIMESTAMP(6) WITH TIME ZONE,
    cancellation_reason        VARCHAR(500),

    failure_code               VARCHAR(64),
    failure_reason             VARCHAR(2000),

    claim_owner                VARCHAR(255),
    claim_expires_at           TIMESTAMP(6) WITH TIME ZONE,

    lock_version               BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT pk_test_runs PRIMARY KEY (id),
    CONSTRAINT fk_test_runs_team FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT fk_test_runs_version
        FOREIGN KEY (definition_version_id) REFERENCES test_definition_versions (id),
    CONSTRAINT fk_test_runs_plan FOREIGN KEY (execution_plan_id) REFERENCES execution_plans (id),
    CONSTRAINT uq_test_runs_team_idempotency UNIQUE (team_id, idempotency_key),
    CONSTRAINT ck_test_runs_status CHECK (status IN (
        'VALIDATING', 'REJECTED', 'AWAITING_APPROVAL', 'QUEUED', 'PROVISIONING',
        'RUNNING', 'ANALYZING', 'COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_test_runs_claim_paired CHECK (
        (claim_owner IS NULL AND claim_expires_at IS NULL)
        OR (claim_owner IS NOT NULL AND claim_expires_at IS NOT NULL))
);

CREATE INDEX ix_test_runs_queue ON test_runs (status, queued_at, id);
CREATE INDEX ix_test_runs_team_status ON test_runs (team_id, status);
CREATE INDEX ix_test_runs_team_requested ON test_runs (team_id, requested_at DESC);

CREATE TABLE runner_shards (
    id                 UUID          NOT NULL,
    run_id             UUID          NOT NULL,
    engine             VARCHAR(32)   NOT NULL,
    shard_index        INTEGER       NOT NULL,
    shard_count        INTEGER       NOT NULL,
    status             VARCHAR(32)   NOT NULL,

    provider_id        VARCHAR(32),
    external_name      VARCHAR(253),
    external_uid       VARCHAR(128),

    last_heartbeat_at  TIMESTAMP(6) WITH TIME ZONE,
    started_at         TIMESTAMP(6) WITH TIME ZONE,
    completed_at       TIMESTAMP(6) WITH TIME ZONE,
    exit_code          INTEGER,
    failure_code       VARCHAR(64),
    lock_version       BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT pk_runner_shards PRIMARY KEY (id),
    CONSTRAINT fk_runner_shards_run FOREIGN KEY (run_id) REFERENCES test_runs (id),
    CONSTRAINT uq_runner_shards_run_index UNIQUE (run_id, shard_index),
    CONSTRAINT ck_runner_shards_count CHECK (shard_count >= 1),
    CONSTRAINT ck_runner_shards_index CHECK (shard_index >= 0 AND shard_index < shard_count),
    CONSTRAINT ck_runner_shards_status CHECK (status IN (
        'PENDING', 'PROVISIONED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'))
);

CREATE INDEX ix_runner_shards_run ON runner_shards (run_id, shard_index);

CREATE TABLE run_events (
    sequence        BIGINT        GENERATED BY DEFAULT AS IDENTITY,
    run_id          UUID          NOT NULL,
    event_type      VARCHAR(48)   NOT NULL,
    previous_state  VARCHAR(32),
    new_state       VARCHAR(32),
    actor           VARCHAR(255)  NOT NULL,
    detail          JSON          NOT NULL,
    occurred_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_run_events PRIMARY KEY (sequence),
    CONSTRAINT fk_run_events_run FOREIGN KEY (run_id) REFERENCES test_runs (id)
);

CREATE INDEX ix_run_events_run_sequence ON run_events (run_id, sequence);

CREATE TABLE orchestration_locks (
    name        VARCHAR(64)   NOT NULL,
    acquired_by VARCHAR(255),
    acquired_at TIMESTAMP(6) WITH TIME ZONE,

    CONSTRAINT pk_orchestration_locks PRIMARY KEY (name)
);

INSERT INTO orchestration_locks (name) VALUES ('run-admission');

-- V3: durable run orchestration — execution plans, runs, shards and the append-only event log.
--
-- This is the schema that makes a run survive a control-plane restart. V1 kept a status column and
-- nothing else: an @Async thread owned the run, and when the process died the run was lost with no
-- record that anything had been in flight. Everything here exists to make that impossible.
--
--
-- ── A note on the table name ─────────────────────────────────────────────────────────────────────
--
-- CLAUDE.md §12 names the V2 run table `test_runs`, but V1's table already holds that name and §5
-- requires V1 to keep working while both surfaces coexist. V1's table is therefore renamed to
-- `v1_test_runs` and V2 takes the documented name.
--
-- Renaming V1 rather than giving V2 a decorated name is deliberate: V1's table disappears when V1
-- is removed, so the prefix is temporary, whereas a `v2_` prefix on the table the platform will use
-- forever would outlive the reason for it. V1's data, columns, constraints and index are preserved
-- exactly — this is a rename, not a reshape, and V1's behavior is unchanged. The V1 JPA entity is
-- repointed in the same increment; its characterization tests are the check.

ALTER TABLE test_runs RENAME TO v1_test_runs;
ALTER INDEX ix_test_runs_team_created_at RENAME TO ix_v1_test_runs_team_created_at;
ALTER TABLE v1_test_runs RENAME CONSTRAINT pk_test_runs TO pk_v1_test_runs;


-- ── Execution plans ──────────────────────────────────────────────────────────────────────────────
--
-- What a specification compiles to for one engine, frozen. Written once, never updated: a run
-- references a plan and runner results are verified against its digest, and both stop meaning
-- anything if the plan can be edited afterwards.

CREATE TABLE execution_plans (
    id                          UUID          NOT NULL,
    definition_version_id       UUID          NOT NULL,
    engine                      VARCHAR(32)   NOT NULL,
    -- TEXT, not JSONB, and deliberately so. JSONB is a parsed representation: it reorders object
    -- keys and normalizes whitespace, so the text read back is not the text written. The digest
    -- below is taken over these exact bytes and is what runner results are verified against, so a
    -- column that silently reformats its contents would make the stored plan and its stored digest
    -- disagree — and any later integrity check that recomputed the digest would fail on every plan.
    -- Nothing queries inside a plan: it is machine-generated and always reached through its run.
    plan_json                   TEXT          NOT NULL,
    -- SHA-256 over plan_json. Echoed by every runner; results that disagree are rejected rather
    -- than merged, because they measured something other than what this run references.
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


-- ── Runs ─────────────────────────────────────────────────────────────────────────────────────────

CREATE TABLE test_runs (
    id                         UUID          NOT NULL,
    -- Denormalized from the definition rather than supplied by the caller. Every authorization and
    -- quota decision reads this column, and a trigger that could name its own team could spend
    -- another team's capacity and read another team's results.
    team_id                    UUID          NOT NULL,
    -- A run references an immutable version, never a definition. Pointing at a definition would
    -- mean a run's meaning changed whenever someone edited the test.
    definition_version_id      UUID          NOT NULL,
    -- Null until the run is admitted and its plan compiled.
    execution_plan_id          UUID,
    status                     VARCHAR(32)   NOT NULL,
    environment                VARCHAR(63)   NOT NULL,

    -- Idempotency. The unique constraint below is the actual guarantee: a read-then-write check
    -- passes simultaneously on two replicas, and only the constraint is authoritative. Without it
    -- a retried trigger is not a duplicate row but a second load test against a real system.
    idempotency_key            VARCHAR(200)  NOT NULL,
    -- Identifies what the key was first used for, so a key reused with a different definition
    -- version is rejected instead of silently returning the earlier run's results.
    request_digest             VARCHAR(80)   NOT NULL,
    requested_by               VARCHAR(255)  NOT NULL,

    requested_at               TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    queued_at                  TIMESTAMP(6) WITH TIME ZONE,
    started_at                 TIMESTAMP(6) WITH TIME ZONE,
    completed_at               TIMESTAMP(6) WITH TIME ZONE,

    -- Cancellation is recorded when asked for, not when it takes effect. A run with a live runner
    -- cannot stop instantly, and losing the request in that interval would let a control-plane
    -- restart resume a run someone had already stopped.
    cancellation_requested_by  VARCHAR(255),
    cancellation_requested_at  TIMESTAMP(6) WITH TIME ZONE,
    cancellation_reason        VARCHAR(500),

    -- Stable code plus already-sanitized text. Raw runner errors carry object names, connection
    -- strings and occasionally request content, none of which belongs in a consumer's CI log.
    failure_code               VARCHAR(64),
    failure_reason             VARCHAR(2000),

    -- The reconciler lease. It expires: a reconciler that dies mid-provision must not own the run
    -- forever, or nothing would ever finish it.
    claim_owner                VARCHAR(255),
    claim_expires_at           TIMESTAMP(6) WITH TIME ZONE,

    lock_version               BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT pk_test_runs PRIMARY KEY (id),
    CONSTRAINT fk_test_runs_team FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT fk_test_runs_version
        FOREIGN KEY (definition_version_id) REFERENCES test_definition_versions (id),
    CONSTRAINT fk_test_runs_plan FOREIGN KEY (execution_plan_id) REFERENCES execution_plans (id),

    -- Scoped to the team because keys are chosen by consumers: two teams independently using
    -- 'nightly-baseline' must not collide, and no team may reach another's run by guessing a key.
    CONSTRAINT uq_test_runs_team_idempotency UNIQUE (team_id, idempotency_key),

    CONSTRAINT ck_test_runs_status CHECK (status IN (
        'VALIDATING', 'REJECTED', 'AWAITING_APPROVAL', 'QUEUED', 'PROVISIONING',
        'RUNNING', 'ANALYZING', 'COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED')),

    -- A lease without an owner, or an owner without an expiry, is a lease nothing can reclaim.
    CONSTRAINT ck_test_runs_claim_paired CHECK (
        (claim_owner IS NULL AND claim_expires_at IS NULL)
        OR (claim_owner IS NOT NULL AND claim_expires_at IS NOT NULL))
);

-- The claim query orders queued runs by age. Deterministic and auditable ordering is a requirement,
-- not a nicety: "why did their run start before ours" must have an answer.
CREATE INDEX ix_test_runs_queue ON test_runs (status, queued_at, id);

-- Capacity accounting counts runs holding a slot, per team and globally, on every claim.
CREATE INDEX ix_test_runs_team_status ON test_runs (team_id, status);

-- The V2 history endpoint lists a team's runs newest first.
CREATE INDEX ix_test_runs_team_requested ON test_runs (team_id, requested_at DESC);


-- ── Runner shards ────────────────────────────────────────────────────────────────────────────────

CREATE TABLE runner_shards (
    id                 UUID          NOT NULL,
    run_id             UUID          NOT NULL,
    engine             VARCHAR(32)   NOT NULL,
    shard_index        INTEGER       NOT NULL,
    shard_count        INTEGER       NOT NULL,
    status             VARCHAR(32)   NOT NULL,

    -- The provider's handle, persisted before the run is allowed to report RUNNING. A crash in
    -- between would otherwise leave a load generator executing that nothing can find or cancel.
    -- The uid is kept alongside the name because a Job name can be reused after deletion, and
    -- cancelling the wrong generation would terminate an unrelated run.
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
    -- One row per shard of a run. Stops a retried provisioning pass from creating a second record
    -- for the same shard, which would double the run's apparent load.
    CONSTRAINT uq_runner_shards_run_index UNIQUE (run_id, shard_index),
    CONSTRAINT ck_runner_shards_count CHECK (shard_count >= 1),
    CONSTRAINT ck_runner_shards_index CHECK (shard_index >= 0 AND shard_index < shard_count),
    CONSTRAINT ck_runner_shards_status CHECK (status IN (
        'PENDING', 'PROVISIONED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'))
);

CREATE INDEX ix_runner_shards_run ON runner_shards (run_id, shard_index);


-- ── Run events ───────────────────────────────────────────────────────────────────────────────────
--
-- Append-only. This is the record that answers "what happened to this run" months later, and an
-- audit trail that can be rewritten is not an audit trail. A correction is a new event.

CREATE TABLE run_events (
    sequence        BIGINT        GENERATED BY DEFAULT AS IDENTITY,
    run_id          UUID          NOT NULL,
    event_type      VARCHAR(48)   NOT NULL,
    -- Null for events that are not state changes — a deferred admission, a duplicate trigger.
    -- Those are frequently the events that explain a run's history.
    previous_state  VARCHAR(32),
    new_state       VARCHAR(32),
    -- A subject identity for human action, a component name for platform action.
    actor           VARCHAR(255)  NOT NULL,
    -- Short operational facts only: shard index, lease owner, admission reason. Never a request
    -- body, an authorization header or a raw exception — run events are read by every member of a
    -- team and exported to operational tooling.
    detail          JSONB         NOT NULL DEFAULT '{}'::jsonb,
    occurred_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_run_events PRIMARY KEY (sequence),
    CONSTRAINT fk_run_events_run FOREIGN KEY (run_id) REFERENCES test_runs (id)
);

-- Reading a run's history in order, and resuming a dropped stream from a known sequence.
CREATE INDEX ix_run_events_run_sequence ON run_events (run_id, sequence);

CREATE OR REPLACE FUNCTION reject_run_event_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'run_events is append-only: event % of run % cannot be modified or deleted',
        OLD.sequence, OLD.run_id
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_run_events_no_update
    BEFORE UPDATE ON run_events
    FOR EACH ROW
    EXECUTE FUNCTION reject_run_event_mutation();

CREATE TRIGGER trg_run_events_no_delete
    BEFORE DELETE ON run_events
    FOR EACH ROW
    EXECUTE FUNCTION reject_run_event_mutation();


-- ── Execution plan immutability ──────────────────────────────────────────────────────────────────
--
-- Same reasoning as test_definition_versions: application-level checks are bypassed by any direct
-- SQL write, and a rewritten plan invalidates every result already verified against its digest.

CREATE OR REPLACE FUNCTION reject_execution_plan_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'execution_plans is immutable: plan % cannot be modified or deleted', OLD.id
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_execution_plans_no_update
    BEFORE UPDATE ON execution_plans
    FOR EACH ROW
    EXECUTE FUNCTION reject_execution_plan_mutation();

CREATE TRIGGER trg_execution_plans_no_delete
    BEFORE DELETE ON execution_plans
    FOR EACH ROW
    EXECUTE FUNCTION reject_execution_plan_mutation();


-- ── Admission serialization ──────────────────────────────────────────────────────────────────────
--
-- One row, locked FOR UPDATE for the duration of a claim transaction.
--
-- The global concurrency ceiling is only a real invariant if the capacity snapshot and the decision
-- it informs cannot interleave. Two reconcilers locking disjoint queued runs would each observe
-- eight active runs and each admit two, ending at twelve — every individual decision correct and
-- the platform over its limit. Serializing the critical section is what closes that.
--
-- The cost is nothing at this scale: single-digit runs per hour, a claim transaction measured in
-- milliseconds, and polling measured in seconds. A row lock is used rather than a PostgreSQL
-- advisory lock so the same mechanism works on H2 for local development.

CREATE TABLE orchestration_locks (
    name        VARCHAR(64)   NOT NULL,
    acquired_by VARCHAR(255),
    acquired_at TIMESTAMP(6) WITH TIME ZONE,

    CONSTRAINT pk_orchestration_locks PRIMARY KEY (name)
);

INSERT INTO orchestration_locks (name) VALUES ('run-admission');

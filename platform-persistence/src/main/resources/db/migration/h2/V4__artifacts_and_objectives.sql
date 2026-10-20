-- H2 variant. See the PostgreSQL migration for the rationale behind each table and column.
--
-- One deliberate difference: the partial index on artifacts(expires_at) is a plain index here.
-- H2 has no partial indexes, and the index exists only to make retention sweeps cheap — a
-- behaviour that is exercised against PostgreSQL, not locally.

CREATE TABLE artifacts (
    id            UUID          NOT NULL,
    run_id        UUID          NOT NULL,
    shard_id      UUID,
    artifact_type VARCHAR(32)   NOT NULL,
    object_key    VARCHAR(1024) NOT NULL,
    digest        VARCHAR(80)   NOT NULL,
    content_type  VARCHAR(128)  NOT NULL,
    size_bytes    BIGINT        NOT NULL,
    stored_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at    TIMESTAMP(6) WITH TIME ZONE,

    CONSTRAINT pk_artifacts PRIMARY KEY (id),
    CONSTRAINT fk_artifacts_run FOREIGN KEY (run_id) REFERENCES test_runs (id),
    CONSTRAINT fk_artifacts_shard FOREIGN KEY (shard_id) REFERENCES runner_shards (id),
    CONSTRAINT uq_artifacts_object_key UNIQUE (object_key),
    CONSTRAINT ck_artifacts_size CHECK (size_bytes >= 0),
    CONSTRAINT ck_artifacts_type CHECK (artifact_type IN (
        'EXECUTION_PLAN', 'RESULTS', 'RUNNER_LOG', 'RUNNER_MANIFEST', 'REPORT'))
);

CREATE INDEX ix_artifacts_run ON artifacts (run_id, stored_at);
CREATE INDEX ix_artifacts_expiry ON artifacts (expires_at);

CREATE TABLE objective_results (
    id             UUID          NOT NULL,
    run_id         UUID          NOT NULL,
    objective_id   VARCHAR(255)  NOT NULL,
    metric         VARCHAR(128)  NOT NULL,
    scope          VARCHAR(128)  NOT NULL,
    verdict        VARCHAR(16)   NOT NULL,
    observed_value BIGINT        NOT NULL,
    threshold      BIGINT        NOT NULL,
    detail         VARCHAR(1000) NOT NULL DEFAULT '',
    evaluated_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_objective_results PRIMARY KEY (id),
    CONSTRAINT fk_objective_results_run FOREIGN KEY (run_id) REFERENCES test_runs (id),
    CONSTRAINT uq_objective_results_run_objective UNIQUE (run_id, objective_id),
    CONSTRAINT ck_objective_results_verdict
        CHECK (verdict IN ('PASS', 'BREACH', 'INDETERMINATE'))
);

CREATE INDEX ix_objective_results_run ON objective_results (run_id);

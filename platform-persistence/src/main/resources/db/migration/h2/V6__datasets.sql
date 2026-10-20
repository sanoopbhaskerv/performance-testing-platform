-- H2 variant. See the PostgreSQL migration for the rationale behind each column.
--
-- Two deliberate differences, both the same as V2's:
--   1. No COMMENT ON COLUMN. Duplicating prose across two dialects is how the two drift apart.
--   2. No immutability trigger. PostgreSQL rejects an UPDATE to a stored dataset version at the
--      database; H2 does not, so local development has the application-level guarantee only. That
--      is why the immutability test runs against PostgreSQL Testcontainers, not H2.

CREATE TABLE datasets (
    id           UUID          NOT NULL,
    team_id      UUID          NOT NULL,
    name         VARCHAR(63)   NOT NULL,
    version      INTEGER       NOT NULL,
    object_key   VARCHAR(1024) NOT NULL,
    digest       VARCHAR(80)   NOT NULL,
    columns_csv  VARCHAR(4000) NOT NULL,
    row_count    BIGINT        NOT NULL,
    size_bytes   BIGINT        NOT NULL,
    uploaded_by  VARCHAR(255)  NOT NULL,
    uploaded_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_datasets PRIMARY KEY (id),
    CONSTRAINT fk_datasets_team FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT uq_datasets_team_name_version UNIQUE (team_id, name, version),
    CONSTRAINT uq_datasets_object_key UNIQUE (object_key),
    CONSTRAINT ck_datasets_version CHECK (version >= 1),
    CONSTRAINT ck_datasets_row_count CHECK (row_count >= 0),
    CONSTRAINT ck_datasets_size CHECK (size_bytes >= 0)
);

CREATE INDEX ix_datasets_team ON datasets (team_id, name, version DESC);


-- A shard's slice of a dataset is stored as an artifact of the run. See the PostgreSQL migration.
ALTER TABLE artifacts DROP CONSTRAINT ck_artifacts_type;
ALTER TABLE artifacts ADD CONSTRAINT ck_artifacts_type CHECK (artifact_type IN (
    'EXECUTION_PLAN', 'RESULTS', 'RUNNER_LOG', 'RUNNER_MANIFEST', 'REPORT', 'DATASET'));

-- V6: the durable record of which dataset versions exist.
--
-- Increment 2g let a specification declare a dataset, bind to its columns by name and be
-- validated against them. It could not make one arrive. This table, plus a team-prefixed object in
-- the artifact bucket, is what closes that: content lives in object storage, the record lives here.
--
-- The same split as `artifacts`, for the same reasons — different systems, different sizes,
-- different retention. What this carries and a key cannot is the header the file actually had. A
-- specification declares the columns it expects; a run compares the two before generating load, so
-- a re-exported dataset cannot silently bind different values to the same names.

CREATE TABLE datasets (
    id           UUID          NOT NULL,
    team_id      UUID          NOT NULL,

    -- Lowercase alphanumeric with hyphens, like every other name that reaches an object key.
    name         VARCHAR(63)   NOT NULL,
    version      INTEGER       NOT NULL,

    -- A stable storage key, never a pre-signed URL (CLAUDE.md §17).
    object_key   VARCHAR(1024) NOT NULL,
    digest       VARCHAR(80)   NOT NULL,

    -- The header, comma-separated, in file order. A column name is restricted to what a dollar-brace
    -- reference can name, so a comma cannot appear inside one and this needs no escaping. Stored
    -- denormalised rather than as rows: it is read whole, exactly once, immediately before a run.
    columns_csv  VARCHAR(4000) NOT NULL,

    row_count    BIGINT        NOT NULL,
    size_bytes   BIGINT        NOT NULL,
    uploaded_by  VARCHAR(255)  NOT NULL,
    uploaded_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_datasets PRIMARY KEY (id),
    CONSTRAINT fk_datasets_team FOREIGN KEY (team_id) REFERENCES teams (id),

    -- Immutability, enforced where two concurrent uploads meet. Checking for an existing version
    -- and then inserting would let both readers see nothing and both write, and the loser's rows
    -- would silently become what a test pinned to that version sends.
    CONSTRAINT uq_datasets_team_name_version UNIQUE (team_id, name, version),
    CONSTRAINT uq_datasets_object_key UNIQUE (object_key),
    CONSTRAINT ck_datasets_version CHECK (version >= 1),
    CONSTRAINT ck_datasets_row_count CHECK (row_count >= 0),
    CONSTRAINT ck_datasets_size CHECK (size_bytes >= 0)
);

CREATE INDEX ix_datasets_team ON datasets (team_id, name, version DESC);

-- Every column is written once. A dataset version that could be edited is a test whose inputs
-- changed without its specification changing — the same defect as a mutable definition version,
-- which V2 forbids in three places.
CREATE OR REPLACE FUNCTION datasets_are_immutable() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Dataset versions are immutable. Upload the next version instead.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_datasets_immutable
    BEFORE UPDATE ON datasets
    FOR EACH ROW EXECUTE FUNCTION datasets_are_immutable();

COMMENT ON TABLE datasets IS
    'Immutable dataset versions. Content lives in object storage under the object_key; this is the record of it.';
COMMENT ON COLUMN datasets.columns_csv IS
    'The header the uploaded file actually had. Compared against the columns a specification declares, before a run generates any load.';


-- A shard's slice of a dataset is stored as an artifact of the run.
--
-- An input, not an output, and recorded for the same reason the rendered plan is: a result is
-- reproducible only if what produced it is still retrievable. It also answers, months later, which
-- rows a particular shard actually sent — a question that otherwise dies with the pod.
ALTER TABLE artifacts DROP CONSTRAINT ck_artifacts_type;
ALTER TABLE artifacts ADD CONSTRAINT ck_artifacts_type CHECK (artifact_type IN (
    'EXECUTION_PLAN', 'RESULTS', 'RUNNER_LOG', 'RUNNER_MANIFEST', 'REPORT', 'DATASET'));

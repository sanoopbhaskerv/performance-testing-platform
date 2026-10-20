-- V2 specification registry: teams, applications, test definitions and immutable versions.
--
-- The registry replaces V1's in-memory team YAML as the authoritative source of test definitions.
-- YAML remains an authoring and import format; it is no longer the registry, so two control-plane
-- replicas can no longer disagree about what a test is.
--
-- Run, shard, event, artifact and objective tables arrive in Increments 1C and 1E.

CREATE TABLE teams (
    id          UUID          NOT NULL,
    name        VARCHAR(63)   NOT NULL,
    status      VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_teams PRIMARY KEY (id),
    CONSTRAINT uq_teams_name UNIQUE (name),
    CONSTRAINT ck_teams_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

CREATE TABLE applications (
    id            UUID         NOT NULL,
    team_id       UUID         NOT NULL,
    name          VARCHAR(63)  NOT NULL,
    -- Governs which targets may be tested. Declared server-side, never taken from a submitted
    -- specification: a specification must not be able to promote its own target to a safer class.
    target_class  VARCHAR(32)  NOT NULL DEFAULT 'NON_PRODUCTION',
    created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_applications PRIMARY KEY (id),
    CONSTRAINT fk_applications_team FOREIGN KEY (team_id) REFERENCES teams (id),
    -- Application names are unique within a team, not globally: two teams may each own a
    -- "proposal" application.
    CONSTRAINT uq_applications_team_name UNIQUE (team_id, name),
    CONSTRAINT ck_applications_target_class CHECK (target_class IN ('NON_PRODUCTION', 'PRODUCTION'))
);

-- A definition is the mutable handle: its name and latest version pointer change over time.
CREATE TABLE test_definitions (
    id              UUID         NOT NULL,
    team_id         UUID         NOT NULL,
    application_id  UUID         NOT NULL,
    name            VARCHAR(63)  NOT NULL,
    latest_version  INTEGER      NOT NULL DEFAULT 0,
    created_by      VARCHAR(255) NOT NULL,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_test_definitions PRIMARY KEY (id),
    CONSTRAINT fk_test_definitions_team FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT fk_test_definitions_application FOREIGN KEY (application_id) REFERENCES applications (id),
    CONSTRAINT uq_test_definitions_team_name UNIQUE (team_id, name)
);

-- A version is immutable. Nothing updates a row here after insert: reproducing a run months
-- later requires the exact specification that ran, and a mutable version would silently
-- invalidate every run and approval that referenced it.
--
-- Immutability is enforced in three places, because application-level checks alone are bypassed
-- by any direct database write: the repository adapter refuses updates, JPA marks the columns
-- non-updatable, and the trigger below rejects an UPDATE outright.
CREATE TABLE test_definition_versions (
    id                    UUID         NOT NULL,
    definition_id         UUID         NOT NULL,
    version_number        INTEGER      NOT NULL,
    -- JSONB rather than text: later increments compare and query inside stored specifications
    -- (baseline comparison, "which definitions target this host"), and PostgreSQL cannot index
    -- inside a text column.
    specification_json    JSONB        NOT NULL,
    -- SHA-256 over the canonical form. A run references this, and runner results whose plan
    -- digest does not match are rejected rather than merged.
    specification_digest  VARCHAR(80)  NOT NULL,
    validation_status     VARCHAR(32)  NOT NULL,
    approval_status       VARCHAR(32)  NOT NULL DEFAULT 'NOT_REQUIRED',
    created_by            VARCHAR(255) NOT NULL,
    approved_by           VARCHAR(255),
    approved_at           TIMESTAMP(6) WITH TIME ZONE,
    created_at            TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_test_definition_versions PRIMARY KEY (id),
    CONSTRAINT fk_tdv_definition FOREIGN KEY (definition_id) REFERENCES test_definitions (id),
    CONSTRAINT uq_tdv_definition_version UNIQUE (definition_id, version_number),
    CONSTRAINT ck_tdv_version_positive CHECK (version_number > 0),
    CONSTRAINT ck_tdv_validation_status CHECK (validation_status IN ('VALID', 'INVALID')),
    CONSTRAINT ck_tdv_approval_status
        CHECK (approval_status IN ('NOT_REQUIRED', 'PENDING', 'APPROVED', 'REJECTED'))
);

-- Two definitions may legitimately share a digest (a copied test), so the digest is indexed
-- rather than unique. It is looked up to answer "has this exact specification been versioned
-- before" and to verify runner results.
CREATE INDEX ix_tdv_digest ON test_definition_versions (specification_digest);
CREATE INDEX ix_tdv_definition_created ON test_definition_versions (definition_id, version_number DESC);

-- The database-level half of the immutability guarantee. Approval fields are the one permitted
-- change: approving a version records a decision about it without altering what it says.
CREATE OR REPLACE FUNCTION reject_test_definition_version_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.specification_json IS DISTINCT FROM OLD.specification_json
        OR NEW.specification_digest IS DISTINCT FROM OLD.specification_digest
        OR NEW.definition_id IS DISTINCT FROM OLD.definition_id
        OR NEW.version_number IS DISTINCT FROM OLD.version_number
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR NEW.created_by IS DISTINCT FROM OLD.created_by
    THEN
        RAISE EXCEPTION
            'test_definition_versions is immutable: version % of definition % cannot be modified',
            OLD.version_number, OLD.definition_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_tdv_immutable
    BEFORE UPDATE ON test_definition_versions
    FOR EACH ROW
    EXECUTE FUNCTION reject_test_definition_version_mutation();

CREATE TRIGGER trg_tdv_no_delete
    BEFORE DELETE ON test_definition_versions
    FOR EACH ROW
    EXECUTE FUNCTION reject_test_definition_version_mutation();

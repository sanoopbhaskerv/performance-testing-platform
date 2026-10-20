-- H2 variant of the specification registry. See the PostgreSQL migration for the rationale
-- behind each table.
--
-- Two deliberate differences from PostgreSQL, both consequences of H2's capabilities:
--
--   1. specification_json is H2's JSON type rather than PostgreSQL's JSONB. H2 has no JSONB and
--      cannot index inside it, so baseline comparison and content-addressed lookup — the reasons
--      the column is JSON at all — are only exercised against PostgreSQL.
--
--   2. There is no immutability trigger. The PostgreSQL schema rejects an UPDATE to a stored
--      version at the database level; H2 keeps only the JPA and repository layers of that
--      guarantee. This is exactly why the immutability integration test must run against
--      PostgreSQL via Testcontainers — proving it on H2 would prove the weaker claim.

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
    target_class  VARCHAR(32)  NOT NULL DEFAULT 'NON_PRODUCTION',
    created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_applications PRIMARY KEY (id),
    CONSTRAINT fk_applications_team FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT uq_applications_team_name UNIQUE (team_id, name),
    CONSTRAINT ck_applications_target_class CHECK (target_class IN ('NON_PRODUCTION', 'PRODUCTION'))
);

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

CREATE TABLE test_definition_versions (
    id                    UUID         NOT NULL,
    definition_id         UUID         NOT NULL,
    version_number        INTEGER      NOT NULL,
    specification_json    JSON         NOT NULL,
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

CREATE INDEX ix_tdv_digest ON test_definition_versions (specification_digest);
CREATE INDEX ix_tdv_definition_created ON test_definition_versions (definition_id, version_number DESC);

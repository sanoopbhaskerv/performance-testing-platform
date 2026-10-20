-- H2 variant of the durable V2 target registry.

CREATE TABLE registered_targets (
    id                UUID         NOT NULL,
    team_id           UUID         NOT NULL,
    application_id    UUID         NOT NULL,
    application_name  VARCHAR(63)  NOT NULL,
    environment       VARCHAR(63)  NOT NULL,
    target_class      VARCHAR(32)  NOT NULL,
    scheme            VARCHAR(8)   NOT NULL,
    host              VARCHAR(255) NOT NULL,
    port              INTEGER      NOT NULL,
    created_by        VARCHAR(255) NOT NULL,
    created_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_registered_targets PRIMARY KEY (id),
    CONSTRAINT fk_registered_targets_team FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT fk_registered_targets_application FOREIGN KEY (application_id) REFERENCES applications (id),
    CONSTRAINT uq_registered_targets_application_environment
        UNIQUE (team_id, application_id, environment),
    CONSTRAINT ck_registered_targets_target_class
        CHECK (target_class IN ('NON_PRODUCTION', 'PRODUCTION')),
    CONSTRAINT ck_registered_targets_scheme CHECK (scheme IN ('http', 'https')),
    CONSTRAINT ck_registered_targets_port CHECK (port BETWEEN 1 AND 65535)
);

CREATE INDEX ix_registered_targets_lookup
    ON registered_targets (team_id, application_name, environment);

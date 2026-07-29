-- V4: durable artifact references and objective outcomes.
--
-- These are the two things a run leaves behind that outlive it: the files it produced, and the
-- verdict on whether it met its objectives.
--
-- V1 stored neither durably. Report paths were local filesystem paths inside a pod, so V1's run
-- history accumulated links to reports nobody could open again, and the SLA verdict was a single
-- enum on the run row with no record of which objective produced it or what it measured.

CREATE TABLE artifacts (
    id            UUID          NOT NULL,
    run_id        UUID          NOT NULL,
    -- Null for run-level artifacts such as the generated report.
    shard_id      UUID,
    artifact_type VARCHAR(32)   NOT NULL,

    -- A stable storage key, never a pre-signed URL. A stored URL expires, which makes it a
    -- reference that works until it silently does not — and CLAUDE.md §17 forbids storing them.
    -- Download grants are minted per request, after authorization.
    object_key    VARCHAR(1024) NOT NULL,
    digest        VARCHAR(80)   NOT NULL,
    content_type  VARCHAR(128)  NOT NULL,
    size_bytes    BIGINT        NOT NULL,
    stored_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    -- When this may be reclaimed. Null means the retention policy has not been applied yet.
    expires_at    TIMESTAMP(6) WITH TIME ZONE,

    CONSTRAINT pk_artifacts PRIMARY KEY (id),
    CONSTRAINT fk_artifacts_run FOREIGN KEY (run_id) REFERENCES test_runs (id),
    CONSTRAINT fk_artifacts_shard FOREIGN KEY (shard_id) REFERENCES runner_shards (id),
    -- One artifact per key. A repeated reconciliation pass re-stores the same content under the
    -- same key, and a second row would double-count a run's evidence.
    CONSTRAINT uq_artifacts_object_key UNIQUE (object_key),
    CONSTRAINT ck_artifacts_size CHECK (size_bytes >= 0),
    CONSTRAINT ck_artifacts_type CHECK (artifact_type IN (
        'EXECUTION_PLAN', 'RESULTS', 'RUNNER_LOG', 'RUNNER_MANIFEST', 'REPORT'))
);

CREATE INDEX ix_artifacts_run ON artifacts (run_id, stored_at);
CREATE INDEX ix_artifacts_expiry ON artifacts (expires_at) WHERE expires_at IS NOT NULL;


-- One row per objective per run: what was required, what was measured, and the verdict.
--
-- Stored rather than recomputed on read so a report is stable, but recomputable from the stored
-- artifacts so a disputed verdict can be settled by re-deriving it. Both properties matter: the
-- first makes a report trustworthy to link to, the second makes it trustworthy at all.
CREATE TABLE objective_results (
    id             UUID          NOT NULL,
    run_id         UUID          NOT NULL,
    -- Derived from metric, scope and percentile rather than from position, so the same objective
    -- keeps its identifier when another is added above it. Comparing two runs otherwise lines up
    -- different objectives and reports a regression that did not happen.
    objective_id   VARCHAR(255)  NOT NULL,
    metric         VARCHAR(128)  NOT NULL,
    scope          VARCHAR(128)  NOT NULL,

    -- INDETERMINATE is a real outcome, not an error: an objective evaluated against incomplete or
    -- absent data has not passed, and recording it as a pass is how a platform reports that a
    -- system is healthy when nobody measured it.
    verdict        VARCHAR(16)   NOT NULL,

    -- Scaled integers in the threshold's own units — milliseconds, or hundredths of a percent.
    -- Not floating point: this comparison is the verdict, and one that can differ in the last bit
    -- between two runs of the same analysis is not a deterministic answer.
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

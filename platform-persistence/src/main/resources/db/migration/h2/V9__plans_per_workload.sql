-- H2 variant. See the PostgreSQL migration for the rationale behind each change.
--
-- Two deliberate differences: no COMMENT ON, and the unique index on (run_id, workload_name) is not
-- partial. H2 has no partial indexes, and it treats NULLs as distinct in a unique index — so rows
-- with no run_id do not collide, which is the same effect the PostgreSQL WHERE clause achieves.

ALTER TABLE execution_plans ADD COLUMN workload_name VARCHAR(63);
ALTER TABLE execution_plans ADD COLUMN run_id UUID;

UPDATE execution_plans SET workload_name = '*' WHERE workload_name IS NULL;

ALTER TABLE execution_plans ALTER COLUMN workload_name SET NOT NULL;

ALTER TABLE execution_plans
    ADD CONSTRAINT fk_execution_plans_run FOREIGN KEY (run_id) REFERENCES test_runs (id);

CREATE UNIQUE INDEX uq_execution_plans_run_workload
    ON execution_plans (run_id, workload_name);

CREATE INDEX ix_execution_plans_run ON execution_plans (run_id);

ALTER TABLE runner_shards ADD COLUMN workload_name VARCHAR(63);

UPDATE runner_shards SET workload_name = '*' WHERE workload_name IS NULL;

ALTER TABLE runner_shards ALTER COLUMN workload_name SET NOT NULL;

ALTER TABLE runner_shards DROP CONSTRAINT uq_runner_shards_run_index;

ALTER TABLE runner_shards
    ADD CONSTRAINT uq_runner_shards_run_workload_index
        UNIQUE (run_id, workload_name, shard_index);

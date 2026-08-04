-- V9: a run has one plan per workload, not one plan.
--
-- A specification can declare a protocol load and a browser cohort measuring the same system, or two
-- protocol workloads with different shapes. Until now a run compiled to exactly one plan for exactly
-- one engine: a mixed-engine document was refused at compile time, and before that its later
-- workloads were silently dropped — the run executed the first workload's engine, reported success,
-- and nothing anywhere said that half the test had not run.
--
-- The workload name is part of a plan's identity rather than a label on it. It is what tells two
-- plans of the same run apart, what names the shards that execute each one, and what scopes the
-- samples they produce so an objective written about one workload is judged against that workload's
-- traffic.


-- ── Plans belong to a run, and name their workload ───────────────────────────
--
-- run_id is nullable because every plan written before this migration belongs to a run that
-- references it from the other side (test_runs.execution_plan_id), and back-filling would mean
-- guessing for any plan compiled but never attached. New plans always carry it.
ALTER TABLE execution_plans
    ADD COLUMN workload_name VARCHAR(63),
    ADD COLUMN run_id        UUID;

-- Existing rows predate per-workload plans: each was the whole of its run's execution, so it is
-- described accurately by the reserved name below rather than by a guess at which workload it was.
-- A plan named this way still executes exactly as it did.
UPDATE execution_plans SET workload_name = '*' WHERE workload_name IS NULL;

ALTER TABLE execution_plans
    ALTER COLUMN workload_name SET NOT NULL;

ALTER TABLE execution_plans
    ADD CONSTRAINT fk_execution_plans_run FOREIGN KEY (run_id) REFERENCES test_runs (id);

-- One plan per workload per run. This is what makes provisioning idempotent across passes: a
-- reconciler that recompiled after a crash cannot create a second plan for a workload that already
-- has one, and therefore cannot start a second set of runners for it.
CREATE UNIQUE INDEX uq_execution_plans_run_workload
    ON execution_plans (run_id, workload_name)
    WHERE run_id IS NOT NULL;

CREATE INDEX ix_execution_plans_run ON execution_plans (run_id);

COMMENT ON COLUMN execution_plans.workload_name IS
    'The workload this plan executes, unique within its run. ''*'' on rows that predate '
    'per-workload plans, where one plan was the whole of a run''s execution.';


-- ── Shards belong to a workload ──────────────────────────────────────────────
--
-- A shard already carried its engine. It now carries which workload it is a shard *of*, because two
-- workloads of a run can both have a shard 0 — and without this, the unique key below would have
-- refused the second one, silently reducing a hybrid run to a single workload.
ALTER TABLE runner_shards
    ADD COLUMN workload_name VARCHAR(63);

UPDATE runner_shards SET workload_name = '*' WHERE workload_name IS NULL;

ALTER TABLE runner_shards
    ALTER COLUMN workload_name SET NOT NULL;

-- The old key was (run_id, shard_index), which is exactly the constraint that would break a
-- multi-workload run. Replaced rather than added to: leaving it would make the new key unreachable.
ALTER TABLE runner_shards
    DROP CONSTRAINT uq_runner_shards_run_index;

ALTER TABLE runner_shards
    ADD CONSTRAINT uq_runner_shards_run_workload_index
        UNIQUE (run_id, workload_name, shard_index);

COMMENT ON COLUMN runner_shards.workload_name IS
    'The workload this shard executes. Two workloads of one run can each have a shard 0.';

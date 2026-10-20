-- Existing summaries remain NULL: percentiles cannot be inferred from other percentiles.
ALTER TABLE run_metrics ADD COLUMN p75_millis BIGINT;

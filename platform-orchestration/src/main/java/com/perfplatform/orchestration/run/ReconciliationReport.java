package com.perfplatform.orchestration.run;

/**
 * What one reconciliation pass did.
 *
 * <p>Returned rather than only logged so the scheduler can export it as metrics and tests can
 * assert on it without parsing log output. Counts are per pass, not cumulative.
 *
 * @param validated  runs whose validation outcome was recovered after an interrupted trigger
 * @param admitted   queued runs that were admitted and provisioned this pass
 * @param advanced   active runs that were observed and moved forward
 * @param completed  runs that reached {@code COMPLETED}
 * @param failed     runs that reached {@code FAILED}
 * @param cancelled  runs that reached {@code CANCELLED}
 * @param errors     runs whose reconciliation raised; they keep their state and are retried next
 *                   pass, which is why one failing run cannot stall the loop for everyone else
 */
public record ReconciliationReport(
        int validated,
        int admitted,
        int advanced,
        int completed,
        int failed,
        int cancelled,
        int errors) {

    public static final ReconciliationReport EMPTY =
            new ReconciliationReport(0, 0, 0, 0, 0, 0, 0);

    /** Whether the pass did anything at all. Lets an idle scheduler log at a quieter level. */
    public boolean isIdle() {
        return validated == 0 && admitted == 0 && advanced == 0
                && completed == 0 && failed == 0 && cancelled == 0 && errors == 0;
    }

    /** Accumulates counts, used to assemble a pass from its stages. */
    public static final class Builder {
        private int validated;
        private int admitted;
        private int advanced;
        private int completed;
        private int failed;
        private int cancelled;
        private int errors;

        public Builder validated() {
            validated++;
            return this;
        }

        public Builder admitted() {
            admitted++;
            return this;
        }

        public Builder advanced() {
            advanced++;
            return this;
        }

        public Builder completed() {
            completed++;
            return this;
        }

        public Builder failed() {
            failed++;
            return this;
        }

        public Builder cancelled() {
            cancelled++;
            return this;
        }

        public Builder error() {
            errors++;
            return this;
        }

        public ReconciliationReport build() {
            return new ReconciliationReport(validated, admitted, advanced, completed, failed,
                    cancelled, errors);
        }
    }
}

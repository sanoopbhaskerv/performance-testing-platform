package com.perfplatform.domain.run;

import java.util.Objects;

/**
 * A changed run together with the event that records the change.
 *
 * <p>The pairing is the point. CLAUDE.md §11 requires every transition to persist an event, and
 * making that a convention means it holds only for as long as everyone remembers it. Here the
 * transition methods on {@link TestRun} return this type and nothing else, so there is no way to
 * obtain a moved run without also holding the event that explains it — the store then writes both
 * in one transaction.
 *
 * @param run   the run after the change
 * @param event the event describing it, not yet sequenced
 */
public record RunTransition(TestRun run, RunEvent event) {

    public RunTransition {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(event, "event");
        if (!run.id().equals(event.runId())) {
            throw new IllegalArgumentException(
                    "Event " + event.type() + " belongs to run " + event.runId()
                            + " but was paired with run " + run.id());
        }
    }
}

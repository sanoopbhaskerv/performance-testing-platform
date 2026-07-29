package com.perfplatform.v2;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for durable run orchestration, under {@code platform.orchestration}.
 *
 * <p>Every value here is enforced server-side and none of it can be influenced by a submitted
 * specification. That separation is the point: a specification describes a test, and a test must
 * not be able to raise the ceilings that exist to stop it overwhelming a shared environment.
 */
@ConfigurationProperties(prefix = "platform.orchestration")
public class OrchestrationProperties {

    /**
     * Which execution provider runs workloads.
     *
     * <p>{@code fake} generates no load and exists to exercise the control loop. The Kubernetes
     * provider arrives in Increment 1E; until then, a production profile with {@code fake} selected
     * fails startup rather than reporting successful runs that never ran.
     */
    private String executionProvider = "fake";

    /** Whether the reconciliation loop runs. Disabled in tests that drive it explicitly. */
    private boolean reconciliationEnabled = true;

    /**
     * How often a reconciliation pass runs.
     *
     * <p>Seconds, not milliseconds. Runs last minutes to hours, so polling faster buys nothing and
     * costs a database round trip per replica per tick.
     */
    private Duration reconciliationInterval = Duration.ofSeconds(10);

    /** How long a reconciler's claim on a run holds before a peer may take it over. */
    private Duration leaseDuration = Duration.ofMinutes(2);

    /** How long a shard may go silent before it is declared lost. */
    private Duration heartbeatTolerance = Duration.ofMinutes(5);

    /** Added to a plan's duration to form the runner's hard deadline. */
    private Duration executionGrace = Duration.ofMinutes(10);

    /** How long a run may sit unvalidated before reconciliation re-decides it. */
    private Duration validationGrace = Duration.ofMinutes(1);

    /** Most runs one reconciliation pass will claim. */
    private int claimBatchSize = 20;

    private final Capacity capacity = new Capacity();
    private final Fake fake = new Fake();

    /**
     * Concurrency ceilings (CLAUDE.md §13).
     *
     * <p>Ten globally is the worst-case ceiling, not a target — normal operation is three or four.
     * One per team by default, raised only where policy has approved it, is what stops a single team
     * occupying every global slot and leaving nine others waiting.
     */
    public static class Capacity {
        private int globalMaxRuns = 10;
        private int defaultTeamMaxRuns = 1;

        /** Per-team ceilings by team name, for teams policy has granted more than the default. */
        private Map<String, Integer> teamOverrides = new LinkedHashMap<>();

        public int getGlobalMaxRuns() {
            return globalMaxRuns;
        }

        public void setGlobalMaxRuns(int globalMaxRuns) {
            this.globalMaxRuns = globalMaxRuns;
        }

        public int getDefaultTeamMaxRuns() {
            return defaultTeamMaxRuns;
        }

        public void setDefaultTeamMaxRuns(int defaultTeamMaxRuns) {
            this.defaultTeamMaxRuns = defaultTeamMaxRuns;
        }

        public Map<String, Integer> getTeamOverrides() {
            return teamOverrides;
        }

        public void setTeamOverrides(Map<String, Integer> teamOverrides) {
            this.teamOverrides = teamOverrides;
        }
    }

    /** Timings for the fake provider, so tests can model slow starts and long runs. */
    public static class Fake {
        private Duration startupDelay = Duration.ZERO;
        private Duration executionDuration = Duration.ZERO;

        public Duration getStartupDelay() {
            return startupDelay;
        }

        public void setStartupDelay(Duration startupDelay) {
            this.startupDelay = startupDelay;
        }

        public Duration getExecutionDuration() {
            return executionDuration;
        }

        public void setExecutionDuration(Duration executionDuration) {
            this.executionDuration = executionDuration;
        }
    }

    public String getExecutionProvider() {
        return executionProvider;
    }

    public void setExecutionProvider(String executionProvider) {
        this.executionProvider = executionProvider;
    }

    public boolean isReconciliationEnabled() {
        return reconciliationEnabled;
    }

    public void setReconciliationEnabled(boolean reconciliationEnabled) {
        this.reconciliationEnabled = reconciliationEnabled;
    }

    public Duration getReconciliationInterval() {
        return reconciliationInterval;
    }

    public void setReconciliationInterval(Duration reconciliationInterval) {
        this.reconciliationInterval = reconciliationInterval;
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public Duration getHeartbeatTolerance() {
        return heartbeatTolerance;
    }

    public void setHeartbeatTolerance(Duration heartbeatTolerance) {
        this.heartbeatTolerance = heartbeatTolerance;
    }

    public Duration getExecutionGrace() {
        return executionGrace;
    }

    public void setExecutionGrace(Duration executionGrace) {
        this.executionGrace = executionGrace;
    }

    public Duration getValidationGrace() {
        return validationGrace;
    }

    public void setValidationGrace(Duration validationGrace) {
        this.validationGrace = validationGrace;
    }

    public int getClaimBatchSize() {
        return claimBatchSize;
    }

    public void setClaimBatchSize(int claimBatchSize) {
        this.claimBatchSize = claimBatchSize;
    }

    public Capacity getCapacity() {
        return capacity;
    }

    public Fake getFake() {
        return fake;
    }
}

package com.perfplatform.domain.admission;

import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.WorkloadType;
import com.perfplatform.domain.validation.ValidationFinding;
import com.perfplatform.domain.validation.ValidationResult;

import java.time.Duration;
import java.util.ArrayList;

/** Operator-owned limits on one run, independent of the number of admitted runs. */
public record ExecutionLimits(int maxProtocolUsersPerRun, Duration maxDuration) {
    public static final ExecutionLimits DEFAULTS = new ExecutionLimits(1_000, Duration.ofHours(1));

    public ExecutionLimits {
        if (maxProtocolUsersPerRun < 1 || maxDuration == null
                || maxDuration.isZero() || maxDuration.isNegative()) {
            throw new IllegalArgumentException("Execution limits must be positive");
        }
    }

    public ValidationResult validate(PerformanceTestSpecification specification) {
        var findings = new ArrayList<ValidationFinding>();
        if (specification == null || specification.spec() == null) return ValidationResult.valid();
        // Sum as long: several individually valid integer ceilings must not overflow the quota.
        long users = specification.workloadsOfType(WorkloadType.PROTOCOL).stream()
                .mapToLong(PerformanceTestSpecification.WorkloadSpec::peakUsers).sum();
        if (users > maxProtocolUsersPerRun) {
            findings.add(ValidationFinding.error("policy.protocolUsers.exceeded", "/spec/workloads",
                    "This run requests " + users + " protocol users; the deployment permits at most "
                            + maxProtocolUsersPerRun + ".",
                    "Reduce staged users or arrival-rate maxConcurrency across the workloads."));
        }
        boolean tooLong = specification.spec().workloads().stream()
                .anyMatch(workload -> workload.totalDuration().compareTo(maxDuration) > 0);
        var governance = specification.spec().governance();
        if (tooLong || governance != null && governance.maxDuration() != null
                && governance.maxDuration().compareTo(maxDuration) > 0) {
            findings.add(ValidationFinding.error("policy.duration.exceeded", "/spec/governance/maxDuration",
                    "The deployment permits at most " + maxDuration + " of workload execution.",
                    "Shorten the workload and its declared maxDuration; specifications cannot raise this limit."));
        }
        return ValidationResult.of(findings);
    }

    public void require(PerformanceTestSpecification specification) {
        ValidationResult findings = validate(specification);
        if (!findings.isValid()) throw new ExceededException(findings);
    }

    /** Engine-derived estimates are checked too, including browser definitions with no staged model. */
    public void requireDuration(Duration plannedDuration) {
        if (plannedDuration.compareTo(maxDuration) > 0) {
            throw new ExceededException(ValidationResult.of(ValidationFinding.error(
                    "policy.duration.exceeded", "/spec/governance/maxDuration",
                    "The compiled workload exceeds the deployment maximum of " + maxDuration + ".",
                    "Shorten the workload or set a permitted maxDuration explicitly.")));
        }
    }

    public static final class ExceededException extends IllegalArgumentException {
        private final ValidationResult findings;
        public ExceededException(ValidationResult findings) {
            super(findings.errors().get(0).message());
            this.findings = findings;
        }
        public ValidationResult findings() { return findings; }
    }
}

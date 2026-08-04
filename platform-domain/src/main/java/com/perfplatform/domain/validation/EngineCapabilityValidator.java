package com.perfplatform.domain.validation;

import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.WorkloadSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks that this deployment can actually execute what the specification asks for.
 *
 * <p>The fourth validation layer in CLAUDE.md §10, and the one that depends on <em>where</em> the
 * specification is being validated rather than on what it says. A document naming {@code playwright}
 * is perfectly well formed and perfectly sensible; whether it can run depends on whether this
 * platform has a Playwright engine registered, which is a deployment fact.
 *
 * <p>Pure, like every other validator here: the caller supplies the set of registered engines rather
 * than the validator reaching for a registry. That keeps the domain free of the engine SPI and makes
 * every finding reproducible from its inputs.
 *
 * <h2>Why this layer had to exist</h2>
 * Until it did, a workload naming an engine this deployment does not have was accepted by
 * validation, stored as an immutable definition version, triggered, admitted, and only <em>then</em>
 * refused — during compilation, inside the reconciler, as a failed run. Worse, before S6 a
 * multi-engine specification had its later workloads silently dropped: the run executed the first
 * workload's engine and reported success, and nothing anywhere said that half the test had not run.
 *
 * <p>A person learns about this at the earliest possible moment now: when they ask whether their
 * document is valid.
 */
public final class EngineCapabilityValidator {

    private EngineCapabilityValidator() {
    }

    /**
     * @param registeredEngines identifiers this deployment can execute, lower-case
     * @return findings, empty when every workload names an engine that is present
     */
    public static ValidationResult validate(PerformanceTestSpecification specification,
            Set<String> registeredEngines) {

        List<ValidationFinding> findings = new ArrayList<>();

        if (specification == null || specification.spec() == null) {
            return ValidationResult.of(findings);
        }

        Set<String> available = new TreeSet<>();
        if (registeredEngines != null) {
            registeredEngines.stream()
                    .filter(engine -> engine != null && !engine.isBlank())
                    .map(engine -> engine.trim().toLowerCase(Locale.ROOT))
                    .forEach(available::add);
        }

        List<WorkloadSpec> workloads = specification.spec().workloads();
        for (int index = 0; index < workloads.size(); index++) {
            WorkloadSpec workload = workloads.get(index);
            String path = "/spec/workloads/" + index + "/engine";

            // A workload with no engine is already an error from the semantic layer. Reporting it
            // again here would give a consumer two findings for one mistake.
            if (workload.engine() == null || workload.engine().isBlank()) {
                continue;
            }

            String engine = workload.engine().trim().toLowerCase(Locale.ROOT);
            if (available.contains(engine)) {
                continue;
            }

            findings.add(ValidationFinding.error("spec.workload.engine.unavailable", path,
                    "This platform has no '" + workload.engine().trim() + "' engine, so workload '"
                            + workload.name() + "' could not be executed.",
                    available.isEmpty()
                            ? "No execution engine is registered in this deployment at all, which "
                                    + "is a platform configuration problem rather than a problem "
                                    + "with this document."
                            : "Available engines: " + String.join(", ", available) + "."));
        }

        return ValidationResult.of(findings);
    }
}

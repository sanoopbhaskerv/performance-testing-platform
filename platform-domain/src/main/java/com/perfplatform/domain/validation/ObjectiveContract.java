package com.perfplatform.domain.validation;

import com.perfplatform.domain.spec.PerformanceTestSpecification.ObjectiveSpec;
import com.perfplatform.domain.spec.Threshold;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/** Shared authoring/analysis contract: never compare unlike units or substitute percentiles. */
public final class ObjectiveContract {
    public static final Set<Integer> PERCENTILES = Set.of(50, 75, 90, 95, 99, 100);
    private static final Map<String, Threshold.Unit> UNITS = Map.ofEntries(
            Map.entry("http.response.time", Threshold.Unit.MILLISECONDS),
            Map.entry("browser.journey.duration", Threshold.Unit.MILLISECONDS),
            Map.entry("browser.step.duration", Threshold.Unit.MILLISECONDS),
            Map.entry("web.lcp", Threshold.Unit.MILLISECONDS),
            Map.entry("web.inp", Threshold.Unit.MILLISECONDS),
            Map.entry("web.ttfb", Threshold.Unit.MILLISECONDS),
            Map.entry("web.tbt", Threshold.Unit.MILLISECONDS),
            Map.entry("http.error.rate", Threshold.Unit.PERCENT),
            Map.entry("browser.error.rate", Threshold.Unit.PERCENT),
            Map.entry("web.lighthouse.score", Threshold.Unit.PERCENT),
            Map.entry("http.throughput", Threshold.Unit.PER_SECOND),
            Map.entry("web.cls", Threshold.Unit.SCORE));

    private ObjectiveContract() { }

    public static ValidationResult validate(ObjectiveSpec objective, String path) {
        var findings = new ArrayList<ValidationFinding>();
        Threshold.Unit expected = objective.metric() == null ? null : UNITS.get(objective.metric());
        if (expected != null && objective.threshold() != null && objective.threshold().unit() != expected) {
            findings.add(ValidationFinding.error("spec.objective.threshold.unit", path + "/threshold",
                    "Metric '" + objective.metric() + "' requires " + expected + " thresholds.",
                    "Use ms/s for durations, % for error rates/Lighthouse, /s for throughput, or score for CLS."));
        }
        if (objective.percentile() != null && !PERCENTILES.contains(objective.percentile())) {
            findings.add(ValidationFinding.error("spec.objective.percentile.invalid", path + "/percentile",
                    "Supported percentiles are 50, 75, 90, 95, 99 and 100 (maximum).",
                    "Choose a supported percentile; the platform never substitutes a different one."));
        }
        return ValidationResult.of(findings);
    }
}

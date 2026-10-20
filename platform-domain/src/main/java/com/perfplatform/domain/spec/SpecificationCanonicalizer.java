package com.perfplatform.domain.spec;

import com.perfplatform.domain.spec.PerformanceTestSpecification.AuthenticationSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.DatasetSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadModel;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadStage;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ObjectiveSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ObservabilitySpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.StepSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.SourceSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.WorkloadSpec;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * Produces the canonical text form of a specification, from which its digest is taken.
 *
 * <p>Two specifications that mean the same thing must produce the same digest. Without
 * canonicalization the digest would follow the input's incidental details — YAML key order,
 * indentation, quoting style, {@code 60m} versus {@code 1h}, {@code 1s} versus {@code 1000ms} —
 * and a reformatted file would look like a different test. That matters because the digest is
 * what makes a run reproducible and what a runner's results are checked against before they are
 * accepted.
 *
 * <p>The rules:
 * <ul>
 *   <li>Fields are emitted in a fixed order defined here, not in input order.</li>
 *   <li>Map keys are sorted; list order is preserved, because list order is meaningful — load
 *       stages run in sequence and reordering them is a different test.</li>
 *   <li>Durations and thresholds are normalized to a single form.</li>
 *   <li>Absent and empty values are emitted identically, so adding an empty list changes nothing.</li>
 *   <li>Values are escaped, so a value containing a delimiter cannot forge structure.</li>
 * </ul>
 *
 * <p>Deliberately not JSON: this is a digest input, not an interchange format. Writing it by hand
 * keeps the digest independent of any serialization library's formatting decisions, which would
 * otherwise become an unversioned part of the contract — a library upgrade could silently change
 * every stored digest and invalidate every existing definition version.
 *
 * <p>This encoding is part of the {@code v1alpha1} contract. Changing it changes every digest,
 * so it must be versioned alongside the schema.
 */
public final class SpecificationCanonicalizer {

    /** Bumped whenever the encoding changes, so stored digests can be distinguished. */
    public static final String CANONICAL_FORM_VERSION = "v1alpha1";

    private static final String ABSENT = "~";

    private SpecificationCanonicalizer() {
    }

    /** The canonical form of a specification. Never null; a null input yields a stable marker. */
    public static String canonicalize(PerformanceTestSpecification specification) {
        StringBuilder out = new StringBuilder(512);
        out.append("canonicalForm=").append(CANONICAL_FORM_VERSION).append('\n');

        if (specification == null) {
            return out.append("specification=").append(ABSENT).append('\n').toString();
        }

        field(out, "apiVersion", specification.apiVersion());
        field(out, "kind", specification.kind());

        var metadata = specification.metadata();
        if (metadata == null) {
            field(out, "metadata", null);
        } else {
            field(out, "metadata.name", metadata.name());
            field(out, "metadata.team", metadata.team());
            field(out, "metadata.application", metadata.application());
            // Sorted so that label declaration order cannot change the digest.
            for (Map.Entry<String, String> label : new TreeMap<>(metadata.labels()).entrySet()) {
                field(out, "metadata.labels[" + escape(label.getKey()) + "]", label.getValue());
            }
        }

        var body = specification.spec();
        if (body == null) {
            field(out, "spec", null);
            return out.toString();
        }

        canonicalizeEnvironment(out, body.environment());
        canonicalizeSources(out, body.sources());
        canonicalizeAuthentication(out, body.authentication());
        canonicalizeDatasets(out, body.datasets());
        canonicalizeWorkloads(out, body.workloads());
        canonicalizeObjectives(out, body.objectives());
        canonicalizeObservability(out, body.observability());

        var governance = body.governance();
        if (governance == null) {
            field(out, "spec.governance", null);
        } else {
            field(out, "spec.governance.maxDuration", duration(governance.maxDuration()));
            field(out, "spec.governance.approvalPolicy", governance.approvalPolicy());
            field(out, "spec.governance.retention", duration(governance.retention()));
        }

        return out.toString();
    }

    private static void canonicalizeEnvironment(StringBuilder out,
            PerformanceTestSpecification.EnvironmentSpec environment) {
        if (environment == null) {
            field(out, "spec.environment", null);
            return;
        }
        field(out, "spec.environment.name", environment.name());
        field(out, "spec.environment.runnerCell", environment.runnerCell());
        field(out, "spec.environment.targetClass", name(environment.targetClass()));
    }

    private static void canonicalizeSources(StringBuilder out, List<SourceSpec> sources) {
        field(out, "spec.sources.count", String.valueOf(sources.size()));
        for (int i = 0; i < sources.size(); i++) {
            SourceSpec source = sources.get(i);
            String prefix = "spec.sources[" + i + "]";
            field(out, prefix + ".name", source.name());
            field(out, prefix + ".type", name(source.type()));
            field(out, prefix + ".uri", source.uri());
            field(out, prefix + ".digest", source.digest());
            var git = source.git();
            if (git == null) {
                field(out, prefix + ".git", null);
            } else {
                field(out, prefix + ".git.repository", git.repository());
                field(out, prefix + ".git.ref", git.ref());
                field(out, prefix + ".git.path", git.path());
                field(out, prefix + ".git.project", git.project());
            }
        }
    }

    private static void canonicalizeAuthentication(StringBuilder out,
            List<AuthenticationSpec> authentications) {
        field(out, "spec.authentication.count", String.valueOf(authentications.size()));
        for (int i = 0; i < authentications.size(); i++) {
            AuthenticationSpec authentication = authentications.get(i);
            String prefix = "spec.authentication[" + i + "]";
            field(out, prefix + ".name", authentication.name());
            field(out, prefix + ".type", authentication.type());
            field(out, prefix + ".injectAs", authentication.injectAs());
            // The reference, not a resolved value: the digest must stay stable across rotation.
            field(out, prefix + ".secretRef", authentication.secretRef());
        }
    }

    private static void canonicalizeDatasets(StringBuilder out, List<DatasetSpec> datasets) {
        field(out, "spec.datasets.count", String.valueOf(datasets.size()));
        for (int i = 0; i < datasets.size(); i++) {
            DatasetSpec dataset = datasets.get(i);
            String prefix = "spec.datasets[" + i + "]";
            field(out, prefix + ".name", dataset.name());
            var source = dataset.source();
            field(out, prefix + ".source.type", source == null ? null : source.type());
            field(out, prefix + ".source.uri", source == null ? null : source.uri());
            // In declaration order, not sorted: a scenario binds ${column} by name, but the order is
            // the file's own and a test that expects a different one is a different test.
            field(out, prefix + ".columns.count", String.valueOf(dataset.columns().size()));
            for (int c = 0; c < dataset.columns().size(); c++) {
                field(out, prefix + ".columns[" + c + "]", dataset.columns().get(c));
            }
            field(out, prefix + ".partitionByShard", String.valueOf(dataset.partitionByShard()));
        }
    }

    private static void canonicalizeWorkloads(StringBuilder out, List<WorkloadSpec> workloads) {
        field(out, "spec.workloads.count", String.valueOf(workloads.size()));
        for (int i = 0; i < workloads.size(); i++) {
            WorkloadSpec workload = workloads.get(i);
            String prefix = "spec.workloads[" + i + "]";
            field(out, prefix + ".name", workload.name());
            field(out, prefix + ".type", name(workload.type()));
            field(out, prefix + ".engine", workload.engine());
            field(out, prefix + ".sourceRef", workload.sourceRef());
            field(out, prefix + ".authRef", workload.authRef());

            LoadModel model = workload.model();
            if (model == null) {
                field(out, prefix + ".model", null);
            } else {
                field(out, prefix + ".model.type", name(model.type()));

                // ── Extending this encoding without invalidating every stored digest ──────────
                //
                // The open model's two fields are emitted *only for an open model*. A closed-model
                // document therefore canonicalizes byte-for-byte as it did before they existed, so
                // every definition version already stored still verifies against its recorded
                // digest — which is the whole point of storing one.
                //
                // This is safe against collision because the discriminator is inside the canonical
                // form itself, one line above: a reader knows from `.model.type` exactly which
                // fields to expect, and no other field can produce these keys. It is conditional on
                // the *type*, never on whether a value happens to be present — that distinction is
                // what keeps "absent" and "empty" from colliding everywhere else in this file.
                //
                // The ceiling is part of an open-model test's identity, not metadata about it: two
                // runs at the same rate through different ceilings are different tests, and the one
                // with the lower ceiling may never have reached the rate at all.
                boolean openModel = model.type() != null && model.type().isOpen();
                if (openModel) {
                    field(out, prefix + ".model.maxConcurrency",
                            model.maxConcurrency() == null
                                    ? null : String.valueOf(model.maxConcurrency()));
                }

                field(out, prefix + ".model.stages.count", String.valueOf(model.stages().size()));
                for (int s = 0; s < model.stages().size(); s++) {
                    LoadStage stage = model.stages().get(s);
                    // Stage order is preserved: stages run in sequence, so reordering them is a
                    // different test and must produce a different digest.
                    field(out, prefix + ".model.stages[" + s + "].name", stage.name());
                    field(out, prefix + ".model.stages[" + s + "].duration", duration(stage.duration()));
                    field(out, prefix + ".model.stages[" + s + "].targetUsers",
                            String.valueOf(stage.targetUsers()));
                    if (openModel) {
                        field(out, prefix + ".model.stages[" + s + "].targetRate",
                                stage.targetRate() == null
                                        ? null : String.valueOf(stage.targetRate()));
                    }
                }
            }

            List<ScenarioSpec> scenarios = workload.scenarios();
            field(out, prefix + ".scenarios.count", String.valueOf(scenarios.size()));
            for (int s = 0; s < scenarios.size(); s++) {
                ScenarioSpec scenario = scenarios.get(s);
                field(out, prefix + ".scenarios[" + s + "].name", scenario.name());
                field(out, prefix + ".scenarios[" + s + "].weight", String.valueOf(scenario.weight()));
                field(out, prefix + ".scenarios[" + s + "].operationId", scenario.operationId());
                // Part of the digest, because they are part of what executes. Two versions that
                // differ only in the path they call — or in a header, a query parameter or a body —
                // are different tests, and a digest that could not tell them apart would let results
                // from one be accepted for the other.
                field(out, prefix + ".scenarios[" + s + "].method", scenario.method());
                field(out, prefix + ".scenarios[" + s + "].path", scenario.path());

                String scenarioPrefix = prefix + ".scenarios[" + s + "]";
                // Sorted, so that two documents differing only in the order they list headers
                // produce the same digest: they describe the same request.
                canonicalMap(out, scenarioPrefix + ".headers", scenario.headers());
                canonicalMap(out, scenarioPrefix + ".query", scenario.query());

                var body = scenario.body();
                if (body == null) {
                    field(out, scenarioPrefix + ".body", null);
                } else {
                    field(out, scenarioPrefix + ".body.contentType", body.contentType());
                    field(out, scenarioPrefix + ".body.content", body.content());
                }

                canonicalizeSteps(out, scenarioPrefix, scenario.steps());
            }

            var startWhen = workload.startWhen();
            if (startWhen == null) {
                field(out, prefix + ".startWhen", null);
            } else {
                field(out, prefix + ".startWhen.workload", startWhen.workload());
                field(out, prefix + ".startWhen.stage", startWhen.stage());
            }

            field(out, prefix + ".browserUsers", number(workload.browserUsers()));
            field(out, prefix + ".iterationsPerUser", number(workload.iterationsPerUser()));
            var browserProfile = workload.browserProfile();
            if (browserProfile == null) {
                field(out, prefix + ".browserProfile", null);
            } else {
                field(out, prefix + ".browserProfile.device", browserProfile.device());
                field(out, prefix + ".browserProfile.network", browserProfile.network());
                field(out, prefix + ".browserProfile.cpuThrottle",
                        number(browserProfile.cpuThrottle()));
            }
        }
    }

    /**
     * A journey's steps, and nothing at all when it has none.
     *
     * <p>Written only when steps exist, which is a deliberate departure from the {@code .count}
     * convention used for headers and query parameters. Those emit a count even when empty so that
     * "no headers" cannot be confused with "headers absent". Here there is nothing to confuse: a
     * scenario with no steps is a single request stated through {@code method} and {@code path}, and
     * a scenario with steps writes keys that no single-request scenario can produce.
     *
     * <p>The payoff is that <b>every specification written before journeys existed keeps its
     * digest</b>. Increment 2g changed the canonical form and every stored digest with it — harmless,
     * because nothing recomputes a stored digest, but it meant a document re-validated afterwards no
     * longer matched what was recorded for it. Avoiding that here costs one conditional.
     *
     * <p>Order is preserved and never sorted: steps run in sequence, a later one may depend on a
     * value an earlier one extracted, and reordering them is a different test.
     */
    private static void canonicalizeSteps(StringBuilder out, String scenarioPrefix,
            List<StepSpec> steps) {

        if (steps.isEmpty()) {
            return;
        }

        field(out, scenarioPrefix + ".steps.count", String.valueOf(steps.size()));
        for (int i = 0; i < steps.size(); i++) {
            StepSpec step = steps.get(i);
            String prefix = scenarioPrefix + ".steps[" + i + "]";

            field(out, prefix + ".name", step.name());
            field(out, prefix + ".method", step.method());
            field(out, prefix + ".path", step.path());
            // Sorted, as elsewhere: two documents differing only in the order they list headers
            // describe the same request.
            canonicalMap(out, prefix + ".headers", step.headers());
            canonicalMap(out, prefix + ".query", step.query());

            var body = step.body();
            if (body == null) {
                field(out, prefix + ".body", null);
            } else {
                field(out, prefix + ".body.contentType", body.contentType());
                field(out, prefix + ".body.content", body.content());
            }

            field(out, prefix + ".thinkTime", duration(step.thinkTime()));

            // Extractions are ordered because a later one may match against text an earlier one
            // shortened, and because the order is what the author wrote.
            field(out, prefix + ".extract.count", String.valueOf(step.extract().size()));
            for (int e = 0; e < step.extract().size(); e++) {
                var extraction = step.extract().get(e);
                String ePrefix = prefix + ".extract[" + e + "]";
                field(out, ePrefix + ".name", extraction.name());
                field(out, ePrefix + ".from", name(extraction.from()));
                field(out, ePrefix + ".expression", extraction.expression());
                field(out, ePrefix + ".required", String.valueOf(extraction.required()));
            }

            field(out, prefix + ".expect.count", String.valueOf(step.expect().size()));
            for (int a = 0; a < step.expect().size(); a++) {
                var assertion = step.expect().get(a);
                String aPrefix = prefix + ".expect[" + a + "]";
                field(out, aPrefix + ".subject", name(assertion.subject()));
                field(out, aPrefix + ".operator", name(assertion.operator()));
                field(out, aPrefix + ".value", assertion.value());
            }
        }
    }

    private static void canonicalizeObjectives(StringBuilder out, List<ObjectiveSpec> objectives) {
        field(out, "spec.objectives.count", String.valueOf(objectives.size()));
        for (int i = 0; i < objectives.size(); i++) {
            ObjectiveSpec objective = objectives.get(i);
            String prefix = "spec.objectives[" + i + "]";
            field(out, prefix + ".metric", objective.metric());
            field(out, prefix + ".scope", objective.scope());
            field(out, prefix + ".percentile", number(objective.percentile()));
            field(out, prefix + ".threshold",
                    objective.threshold() == null ? null : objective.threshold().canonicalForm());
        }
    }

    private static void canonicalizeObservability(StringBuilder out, ObservabilitySpec observability) {
        if (observability == null) {
            field(out, "spec.observability", null);
            return;
        }
        field(out, "spec.observability.provider", observability.provider());
        field(out, "spec.observability.correlationHeader", observability.correlationHeader());
        field(out, "spec.observability.serviceTagSelector", observability.serviceTagSelector());

        // Sorted: which series are collected is a set, so declaration order is not meaningful.
        StringJoiner collected = new StringJoiner(",");
        observability.collect().stream().sorted().forEach(item -> collected.add(escape(item)));
        field(out, "spec.observability.collect", collected.toString());
    }

    // ── Encoding ─────────────────────────────────────────────────────────

    private static void field(StringBuilder out, String key, String value) {
        out.append(key).append('=').append(value == null ? ABSENT : escape(value)).append('\n');
    }

    /**
     * Writes a map in sorted order, with its size.
     *
     * <p>Sorted so that two documents listing the same headers in a different order canonicalize
     * identically — they describe the same request, and a digest that disagreed would treat an
     * editor reformatting a file as a new test.
     *
     * <p>The count is written even when the map is empty, so that "no headers" and "headers absent"
     * cannot collide with a map whose single entry happened to canonicalize to nothing.
     */
    private static void canonicalMap(StringBuilder out, String prefix, Map<String, String> values) {
        field(out, prefix + ".count", String.valueOf(values == null ? 0 : values.size()));
        if (values == null || values.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : new TreeMap<>(values).entrySet()) {
            field(out, prefix + "[" + escape(entry.getKey()) + "]", entry.getValue());
        }
    }

    /**
     * Escapes so that no value can introduce a delimiter and forge structure. Without this a
     * label value containing a newline could make two different specifications canonicalize
     * identically, and therefore share a digest.
     */
    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '=' -> escaped.append("\\u003d");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    private static String duration(java.time.Duration value) {
        return value == null ? null : HumanDuration.format(value);
    }

    private static String number(Integer value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}

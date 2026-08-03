package com.perfplatform.domain.dataset;

import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.StepSpec;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the dataset columns a scenario binds to.
 *
 * <p>A scenario says {@code path: /orders/${orderId}} or sends a body containing {@code ${sku}}, and
 * each iteration takes the next row. This class answers the only question the platform needs to ask
 * of that syntax before a run: <em>which names does this test expect to be filled in?</em>
 *
 * <h2>Why an unresolved reference has to be an error</h2>
 * {@code ${orderId}} against a dataset that has no such column is not filled in. JMeter sends the
 * literal text {@code ${orderId}} as part of the path, every request 404s, and the run reports a
 * fast, healthy-looking latency for a target that did no work. It is the same failure the importers
 * guard against when they refuse to invent a path parameter, arriving by a different route — and a
 * typo in a column name is a far easier mistake to make than a missing import.
 *
 * <h2>The syntax is JMeter's, and that is a decision worth stating</h2>
 * {@code ${name}} is what JMeter's own variable references look like, so a bound value passes
 * through the renderer untouched rather than being rewritten into an engine-specific form. That is a
 * convenience the JMeter engine happens to give, not a contract: a second engine is free to render
 * the same canonical {@code ${name}} into whatever its own templating needs. What must not happen is
 * a specification carrying JMeter syntax the platform does not understand — so every reference is
 * resolved here, against the declared columns, rather than being passed through unread.
 */
public final class DatasetBinding {

    /**
     * A {@code ${name}} reference.
     *
     * <p>Names are restricted to what a CSV column can sensibly be called. The restriction matters:
     * JMeter's own syntax also covers function calls such as {@code ${__threadNum}} and
     * {@code ${__Random(1,9)}}, and this pattern deliberately does not match them — a specification
     * is not a place to smuggle in engine functions the platform never validated.
     *
     * <p>The leading {@code (?!__)} is what excludes them, and it is doing more work than it looks
     * like. A function taking arguments is already excluded by its parentheses, but
     * {@code ${__threadNum}} takes none and is otherwise indistinguishable from a column name. Every
     * JMeter function begins with a double underscore; a column reasonably might begin with a single
     * one, so that is the line drawn.
     */
    private static final Pattern REFERENCE =
            Pattern.compile("\\$\\{(?!__)([A-Za-z_][A-Za-z0-9_.-]*)}");

    private DatasetBinding() {
    }

    /**
     * Every column name this scenario refers to, across its path, query, headers and body.
     *
     * <p>In first-appearance order, so a finding that lists them reads the way the document does.
     */
    public static Set<String> referencesIn(ScenarioSpec scenario) {
        Set<String> names = new LinkedHashSet<>();
        // Every step, whichever form the scenario was written in. `asSteps` gives a single-request
        // scenario back as a journey of one, so there is no branch here and no second code path to
        // forget when a field is added.
        for (StepSpec step : scenario.asSteps()) {
            names.addAll(referencesIn(step));
        }
        return names;
    }

    /**
     * Every name this step refers to, across its path, query, headers and body.
     *
     * <p>The names may be dataset columns or values an earlier step extracted — this cannot tell
     * them apart and deliberately does not try. Both are filled in the same way at run time, and
     * which source supplies a name is a question for the validator, which knows the whole journey.
     */
    public static Set<String> referencesIn(StepSpec step) {
        Set<String> names = new LinkedHashSet<>();
        collect(step.path(), names);
        step.query().forEach((key, value) -> {
            collect(key, names);
            collect(value, names);
        });
        step.headers().forEach((key, value) -> {
            collect(key, names);
            collect(value, names);
        });
        if (step.body() != null) {
            collect(step.body().content(), names);
        }
        return names;
    }

    /**
     * The names a journey supplies itself, in the order they become available.
     *
     * <p>A step may only use what an <em>earlier</em> step extracted. Returning them in order is what
     * lets the validator refuse a reference to a value that does not exist yet — which otherwise
     * fails at run time as an unfilled {@code ${...}} sent as literal text, and reports as fast,
     * healthy-looking latency for a target that did no work.
     */
    public static Set<String> extractedBefore(java.util.List<StepSpec> steps, int stepIndex) {
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < stepIndex && i < steps.size(); i++) {
            steps.get(i).extract().forEach(extraction -> names.add(extraction.name()));
        }
        return names;
    }

    /** Every column name referenced anywhere in a map of values. */
    public static Set<String> referencesIn(Map<String, String> values) {
        Set<String> names = new LinkedHashSet<>();
        values.forEach((key, value) -> {
            collect(key, names);
            collect(value, names);
        });
        return names;
    }

    /** Whether any text carries a reference at all. */
    public static boolean hasReference(String text) {
        return text != null && REFERENCE.matcher(text).find();
    }

    private static void collect(String text, Set<String> names) {
        if (text == null) {
            return;
        }
        Matcher matcher = REFERENCE.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
    }
}

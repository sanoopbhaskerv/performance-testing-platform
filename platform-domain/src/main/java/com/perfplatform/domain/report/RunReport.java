package com.perfplatform.domain.report;

import com.perfplatform.domain.port.ArtifactStore.ArtifactReference;
import com.perfplatform.domain.results.NormalizedRunResult;
import com.perfplatform.domain.results.ObjectiveOutcome;
import com.perfplatform.domain.run.RunEvent;
import com.perfplatform.domain.run.TestRun;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.SpecificationDigest;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything a person needs to understand one run, assembled from durable state.
 *
 * <p>A read model, not an aggregate: nothing transitions it and nothing stores it. It exists so the
 * report is composed in one place with one set of rules about what is present and what is absent,
 * rather than by a controller stitching six lookups together and a UI guessing what a missing field
 * means.
 *
 * <p>Every part is read from PostgreSQL, not recomputed from artifacts. That is what makes a report
 * still work after the results file has been reclaimed by its retention policy — a report that stops
 * working when the evidence ages out is not a report. Recomputation from the stored artifacts remains
 * the way to settle a disputed number, and produces the same values by construction.
 *
 * <h2>Absence is information</h2>
 * The optional parts are optional because a run genuinely may not have them yet, and each says
 * something different: no execution means the run was never admitted, no metrics means it was never
 * analyzed, no definition means the version it referenced is no longer readable. None of them is
 * rendered as a zero. A report showing {@code 0ms} for a run that has not started would describe a
 * finished test that does not exist.
 *
 * @param run         the run itself, including whether its load was synthetic
 * @param definition  the immutable test it executed, absent if that version is no longer readable
 * @param execution   how it was compiled, absent until the run was admitted and a plan was built
 * @param objectives  the stored verdicts, empty until analysis has run
 * @param metrics     what was measured, absent until analysis has run
 * @param timeline    the append-only history, in order
 * @param artifacts   the evidence, as references; content is fetched through a separate authorized
 *                    request, never as a stored URL
 */
public record RunReport(
        TestRun run,
        Optional<TestedDefinition> definition,
        Optional<Execution> execution,
        List<ObjectiveOutcome> objectives,
        Optional<NormalizedRunResult> metrics,
        List<RunEvent> timeline,
        List<ArtifactReference> artifacts) {

    /**
     * The immutable test a run executed.
     *
     * @param versionNumber the contiguous version within its definition
     * @param digest        digest of the stored specification, the platform's identity for this test
     * @param specification the specification itself, from which the workload profile is read
     */
    public record TestedDefinition(
            int versionNumber,
            SpecificationDigest digest,
            PerformanceTestSpecification specification) {

        public TestedDefinition {
            Objects.requireNonNull(digest, "digest");
            Objects.requireNonNull(specification, "specification");
            if (versionNumber < 1) {
                throw new IllegalArgumentException("versionNumber must be positive");
            }
        }

        /** The test's name, or empty when the specification carries no metadata. */
        public String name() {
            return specification.metadata() == null ? "" : specification.metadata().name();
        }

        /** The application under test, or empty when the specification carries no metadata. */
        public String application() {
            return specification.metadata() == null ? "" : specification.metadata().application();
        }
    }

    /**
     * What the platform compiled the specification into.
     *
     * <p>The plan digest is included because it is the answer to "are these results from the test I
     * approved". Results whose digest does not match the run's plan are rejected rather than merged,
     * and a report that omitted it would ask a reader to take that check on trust.
     *
     * @param engine                    which engine executed it
     * @param planDigest                digest of the compiled plan
     * @param shardCount                how many runners the plan called for
     * @param peakVirtualUsers          the highest concurrency any stage asked for
     * @param estimatedDurationSeconds  the wall-clock time the stages describe
     */
    public record Execution(
            String engine,
            SpecificationDigest planDigest,
            int shardCount,
            int peakVirtualUsers,
            long estimatedDurationSeconds) {

        public Execution {
            Objects.requireNonNull(planDigest, "planDigest");
            if (engine == null || engine.isBlank()) {
                throw new IllegalArgumentException("engine must not be null or blank");
            }
        }
    }

    public RunReport {
        Objects.requireNonNull(run, "run");
        definition = orEmpty(definition);
        execution = orEmpty(execution);
        metrics = orEmpty(metrics);
        objectives = objectives == null ? List.of() : List.copyOf(objectives);
        timeline = timeline == null ? List.of() : List.copyOf(timeline);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }

    /**
     * Whether this run has been analyzed at all.
     *
     * <p>Distinct from having no objectives: a run may be analyzed and have asked no questions. The
     * report renders the two differently, because "not measured yet" and "measured, nothing asked"
     * lead a reader to do different things.
     */
    public boolean hasAnalysis() {
        return metrics.isPresent();
    }

    /**
     * Whether every measurement here is invented.
     *
     * <p>Read from the run, so it is true for the whole report and not only for the part of it that
     * happens to display a number. When this is true no objective on the report may read as passing.
     */
    public boolean isSynthetic() {
        return run.synthetic();
    }

    /** Objectives with the given verdict, for the report's summary line. */
    public long objectivesWith(ObjectiveOutcome.Verdict verdict) {
        return objectives.stream().filter(outcome -> outcome.verdict() == verdict).count();
    }

    /**
     * Whether the run demonstrated what it set out to.
     *
     * <p>Requires analysis to have happened, at least one objective, every objective to have passed,
     * and the run not to have been synthetic. Each condition removes a way of reporting a pass that
     * was never shown: an unanalyzed run has no evidence, a run with no objectives asked nothing, and
     * a synthetic run measured nothing at all.
     */
    public boolean demonstratedItsObjectives() {
        return hasAnalysis()
                && !isSynthetic()
                && !objectives.isEmpty()
                && objectives.stream().allMatch(ObjectiveOutcome::isPass);
    }

    private static <T> Optional<T> orEmpty(Optional<T> value) {
        return value == null ? Optional.empty() : value;
    }
}

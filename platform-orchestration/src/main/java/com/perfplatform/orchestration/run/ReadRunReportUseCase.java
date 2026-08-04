package com.perfplatform.orchestration.run;

import com.perfplatform.domain.identity.ArtifactId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.port.ArtifactStore;
import com.perfplatform.domain.port.ArtifactStore.ArtifactReference;
import com.perfplatform.domain.port.DefinitionVersionCatalog;
import com.perfplatform.domain.port.DefinitionVersionCatalog.DefinitionVersion;
import com.perfplatform.domain.port.ExecutionPlanRepository;
import com.perfplatform.domain.port.ResultCatalog;
import com.perfplatform.domain.plan.ExecutionPlan;
import com.perfplatform.domain.report.RunReport;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.spec.SpecificationDigest;
import com.perfplatform.domain.run.TestRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Assembles a run's report, and serves the evidence behind it.
 *
 * <p>Separate from {@link ReadTestRunUseCase} because these two reads answer different questions and
 * carry different risk. That one answers "what is this run doing", is polled every couple of seconds,
 * and touches one table. This one answers "what did this run prove", is opened deliberately, and
 * gathers six things — including artifact <em>content</em>, which is the only read in the platform
 * that returns bytes a runner produced.
 *
 * <p>Authorization is delegated to {@link ReadTestRunUseCase#get}, which already resolves the run and
 * checks the caller against its owning team. Reimplementing that check here is exactly how the two
 * would eventually disagree.
 */
public final class ReadRunReportUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReadRunReportUseCase.class);

    /**
     * How much history a report includes.
     *
     * <p>Generous enough for any real run's transitions and shard events, bounded so that a run which
     * somehow accumulated thousands of events cannot be turned into one enormous response.
     */
    private static final int TIMELINE_LIMIT = 500;

    private final ReadTestRunUseCase runs;
    private final ResultCatalog results;
    private final ExecutionPlanRepository plans;
    private final DefinitionVersionCatalog versions;
    private final ArtifactStore artifacts;

    public ReadRunReportUseCase(ReadTestRunUseCase runs, ResultCatalog results,
            ExecutionPlanRepository plans, DefinitionVersionCatalog versions, ArtifactStore artifacts) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.results = Objects.requireNonNull(results, "results");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.versions = Objects.requireNonNull(versions, "versions");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    }

    /**
     * Raised when an artifact's stored content does not match the digest recorded for it.
     *
     * <p>Its own type because it is not a missing artifact and not an unauthorized one: something
     * changed the bytes after they were recorded. Serving them anyway would mean the platform
     * presenting as evidence a file it can prove is not the file it measured.
     */
    public static class ArtifactCorruptedException extends RuntimeException {
        public ArtifactCorruptedException(ArtifactId artifactId) {
            super("Artifact " + artifactId + " does not match the digest recorded when it was stored. "
                    + "Its content has changed since the run produced it and it will not be served.");
        }
    }

    /** Raised when a run has no artifact with the given identifier. */
    public static class UnknownArtifactException extends RuntimeException {
        public UnknownArtifactException(TestRunId runId, ArtifactId artifactId) {
            super("Run " + runId + " has no artifact " + artifactId + ".");
        }
    }

    /** An artifact's content together with the reference describing it. */
    public record ArtifactContent(ArtifactReference reference, byte[] content) {

        public ArtifactContent {
            Objects.requireNonNull(reference, "reference");
            content = content == null ? new byte[0] : content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    /**
     * The report for one run.
     *
     * <p>Assembled from what is persisted and nothing else. Every part that a run may not have yet is
     * returned absent rather than defaulted, so the caller can say "not measured yet" instead of
     * displaying a zero that reads as a finished test.
     */
    public RunReport report(TestRunId runId, CallerContext caller) {
        TestRun run = runs.get(runId, caller);

        Optional<DefinitionVersion> version = versions.find(run.definitionVersionId());
        // The run's first plan, in workload order. A report describing a hybrid run's execution
        // properly is S11's work, when a hybrid can actually be executed; taking the first here
        // keeps a single-workload run — every run today — reporting exactly as it did.
        Optional<ExecutionPlan> plan = plans.findByRun(runId).stream().findFirst();

        RunReport report = new RunReport(
                run,
                version.map(ReadRunReportUseCase::testedDefinition),
                plan.map(ReadRunReportUseCase::execution),
                results.objectiveOutcomesFor(runId),
                results.runMetricsFor(runId),
                runs.events(runId, 0, TIMELINE_LIMIT, caller),
                results.artifactsFor(runId));

        log.debug("Report assembled runId={} teamId={} analyzed={} synthetic={} objectives={} "
                        + "artifacts={}",
                runId, run.teamId(), report.hasAnalysis(), report.isSynthetic(),
                report.objectives().size(), report.artifacts().size());

        return report;
    }

    /**
     * One artifact's content, authorized and verified.
     *
     * <p>The artifact is located among the run's own artifacts rather than looked up by identifier
     * globally. That is what makes authorizing the run sufficient: an identifier belonging to another
     * team's run is simply not in this run's list, so it is reported as unknown — the same answer as
     * an identifier that never existed, which keeps the endpoint from confirming which identifiers are
     * real.
     *
     * <p>The digest is verified before the bytes are returned. Storage is not assumed to be
     * trustworthy, and the alternative is presenting a file as a run's evidence while being able to
     * prove it is not.
     *
     * @throws UnknownArtifactException   if the run has no such artifact, or its content is missing
     * @throws ArtifactCorruptedException if the stored bytes do not match the recorded digest
     */
    public ArtifactContent artifactContent(TestRunId runId, ArtifactId artifactId,
            CallerContext caller) {

        TestRun run = runs.get(runId, caller);

        ArtifactReference reference = results.artifactsFor(runId).stream()
                .filter(candidate -> candidate.id().equals(artifactId))
                .findFirst()
                .orElseThrow(() -> new UnknownArtifactException(runId, artifactId));

        byte[] content = artifacts.read(reference).orElseThrow(() -> {
            // The reference is recorded but the content is gone — retention reclaimed it, or the
            // store lost it. Reported as unknown rather than as an error: nothing is wrong with the
            // request, and the run's own report already lists what it should have produced.
            log.warn("Artifact content missing runId={} teamId={} artifactId={} objectKey={}",
                    runId, run.teamId(), artifactId, reference.objectKey());
            return new UnknownArtifactException(runId, artifactId);
        });

        if (!digestOf(content).matches(SpecificationDigest.parse(reference.digest()))) {
            log.error("Artifact digest mismatch runId={} teamId={} artifactId={} objectKey={} "
                            + "expected={} sizeBytes={}",
                    runId, run.teamId(), artifactId, reference.objectKey(), reference.digest(),
                    content.length);
            throw new ArtifactCorruptedException(artifactId);
        }

        log.info("Artifact served runId={} teamId={} artifactId={} type={} sizeBytes={}",
                runId, run.teamId(), artifactId, reference.type(), content.length);

        return new ArtifactContent(reference, content);
    }

    /**
     * A download file name, derived from the stored key.
     *
     * <p>The last segment of the object key, which is the file name the runner or the platform chose.
     * Keys are built from identifiers and a validated plain file name, so this cannot contain a path
     * separator — but it is sanitized anyway, because the value ends up in a
     * {@code Content-Disposition} header and a header built from storage metadata is a header built
     * from something this code did not write.
     */
    public static String downloadFileName(ArtifactReference reference) {
        String key = reference.objectKey();
        String last = key.substring(key.lastIndexOf('/') + 1);
        String safe = last.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isBlank() ? reference.type().name().toLowerCase(Locale.ROOT) : safe;
    }

    private static RunReport.TestedDefinition testedDefinition(DefinitionVersion version) {
        return new RunReport.TestedDefinition(
                version.versionNumber(), version.digest(), version.specification());
    }

    private static RunReport.Execution execution(ExecutionPlan plan) {
        return new RunReport.Execution(
                plan.engine(),
                plan.planDigest(),
                plan.capacity().shardCount(),
                plan.capacity().peakVirtualUsers(),
                plan.capacity().estimatedDurationSeconds());
    }

    /**
     * The digest of some content, in the stored {@code sha256:<hex>} form.
     *
     * <p>Reuses {@link SpecificationDigest} so the comparison is the platform's one constant-time
     * digest comparison rather than a second implementation that happens to be equivalent today.
     */
    private static SpecificationDigest digestOf(byte[] content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(content);
            return new SpecificationDigest("sha256", HexFormat.of().formatHex(hash));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }

}

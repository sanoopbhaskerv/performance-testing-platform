package com.perfplatform.api.v2.spec;

import com.perfplatform.domain.port.EngineCatalog;
import com.perfplatform.domain.admission.ExecutionLimits;
import com.perfplatform.domain.spec.GitSourceAllowlist;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.SpecificationDigest;
import com.perfplatform.domain.validation.EngineCapabilityValidator;
import com.perfplatform.domain.validation.GitSourceValidator;
import com.perfplatform.domain.validation.SemanticValidator;
import com.perfplatform.domain.validation.ValidationResult;

import java.util.Optional;

/**
 * Runs the validation layers in order and reports once.
 *
 * <p>The layers are deliberately separate and deliberately ordered:
 * <ol>
 *   <li><b>Structural</b> — is the document well formed, are the types right, are there unknown
 *       fields ({@link SpecificationParser}).</li>
 *   <li><b>Semantic</b> — do the cross-references resolve, do the numbers make sense
 *       ({@link SemanticValidator}).</li>
 *   <li><b>Engine capability</b> — can <em>this deployment</em> execute what the document names
 *       ({@link EngineCapabilityValidator}). A document naming an engine that is not installed is
 *       well formed and sensible and cannot run, and the answer depends on where it is validated.</li>
 * </ol>
 *
 * <p>Semantic validation runs even when structural findings exist, so a consumer sees every
 * problem in one response rather than fixing one field per round trip. It is skipped only when
 * the document could not be mapped at all, since there would be nothing to inspect.
 *
 * <p>Per-run user/duration policy is applied using operator-owned limits and rechecked at trigger
 * and provisioning. Live queue capacity and team concurrency are enforced by admission control.
 * <b>Connectivity preflight</b> — DNS, TLS, target health — requires the network and runs from
 * the runner cell that will execute the test; folding it in here would make validation slow,
 * flaky and unusable in a pull request.
 *
 * <p>A digest is returned whenever the specification is valid, so a consumer can tell whether a
 * change actually altered the test before creating a new version.
 */
public class SpecificationValidationService {

    private final SpecificationParser parser;

    /**
     * The engines this deployment has.
     *
     * <p>A port rather than the registry itself, so validation stays testable without an engine and
     * so the domain never meets the engine SPI.
     */
    private final EngineCatalog engines;

    /**
     * The repositories this deployment will fetch a Playwright test from.
     *
     * <p>Deployment policy rather than document shape, so it is passed in for the same reason the
     * engine catalogue is: the same specification must not be silently valid in one deployment and
     * invalid in another without a finding that says which.
     */
    private final GitSourceAllowlist gitSources;
    private final ExecutionLimits executionLimits;

    public SpecificationValidationService(SpecificationParser parser, EngineCatalog engines) {
        this(parser, engines, GitSourceAllowlist.none());
    }

    public SpecificationValidationService(SpecificationParser parser, EngineCatalog engines,
            GitSourceAllowlist gitSources) {
        this(parser, engines, gitSources, ExecutionLimits.DEFAULTS);
    }

    public SpecificationValidationService(SpecificationParser parser, EngineCatalog engines,
            GitSourceAllowlist gitSources, ExecutionLimits executionLimits) {
        this.parser = parser;
        this.engines = engines;
        this.gitSources = gitSources == null ? GitSourceAllowlist.none() : gitSources;
        this.executionLimits = java.util.Objects.requireNonNull(executionLimits);
    }

    /**
     * The outcome of validating a submitted document.
     *
     * @param specification the mapped specification, absent when the document could not be parsed
     * @param findings      every finding from every layer that ran
     * @param digest        present only when the specification is valid; a digest for a rejected
     *                      specification would imply it is a versionable identity
     */
    public record ValidationOutcome(
            Optional<PerformanceTestSpecification> specification,
            ValidationResult findings,
            Optional<SpecificationDigest> digest) {

        public boolean isValid() {
            return findings.isValid();
        }
    }

    public ValidationOutcome validate(String document) {
        SpecificationParser.ParseOutcome parsed = parser.parse(document);

        if (!parsed.isMapped()) {
            return new ValidationOutcome(Optional.empty(), parsed.findings(), Optional.empty());
        }

        PerformanceTestSpecification specification = parsed.specification().orElseThrow();
        ValidationResult combined = parsed.findings()
                .merge(SemanticValidator.validate(specification))
                .merge(executionLimits.validate(specification))
                .merge(EngineCapabilityValidator.validate(specification, engines.engineIds()))
                .merge(GitSourceValidator.validate(specification, gitSources));

        Optional<SpecificationDigest> digest = combined.isValid()
                ? Optional.of(SpecificationDigest.of(specification))
                : Optional.empty();

        return new ValidationOutcome(Optional.of(specification), combined, digest);
    }

    /**
     * Validates an already-mapped specification, for callers that did not submit a document —
     * the UI builds one directly rather than serializing it first.
     *
     * <p>Deliberately not an overload of {@link #validate(String)}: a {@code null} argument would
     * be ambiguous, and the two entry points differ in whether structural validation ran at all.
     */
    public ValidationOutcome validateSpecification(PerformanceTestSpecification specification) {
        ValidationResult findings = SemanticValidator.validate(specification)
                .merge(executionLimits.validate(specification))
                .merge(EngineCapabilityValidator.validate(specification, engines.engineIds()))
                .merge(GitSourceValidator.validate(specification, gitSources));
        Optional<SpecificationDigest> digest = findings.isValid()
                ? Optional.of(SpecificationDigest.of(specification))
                : Optional.empty();
        return new ValidationOutcome(Optional.ofNullable(specification), findings, digest);
    }
}

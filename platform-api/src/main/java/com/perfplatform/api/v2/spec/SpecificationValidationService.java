package com.perfplatform.api.v2.spec;

import com.perfplatform.domain.port.EngineCatalog;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.SpecificationDigest;
import com.perfplatform.domain.validation.EngineCapabilityValidator;
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
 * <p>Two later layers are intentionally absent here. <b>Policy</b> validation — team quotas,
 * target allowlists, duration ceilings — is enforced server-side by admission control at trigger
 * time, against configured limits rather than against values the document supplies about itself.
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

    public SpecificationValidationService(SpecificationParser parser, EngineCatalog engines) {
        this.parser = parser;
        this.engines = engines;
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
                .merge(EngineCapabilityValidator.validate(specification, engines.engineIds()));

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
                .merge(EngineCapabilityValidator.validate(specification, engines.engineIds()));
        Optional<SpecificationDigest> digest = findings.isValid()
                ? Optional.of(SpecificationDigest.of(specification))
                : Optional.empty();
        return new ValidationOutcome(Optional.ofNullable(specification), findings, digest);
    }
}

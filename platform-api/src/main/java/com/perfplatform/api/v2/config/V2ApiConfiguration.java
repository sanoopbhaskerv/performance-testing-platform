package com.perfplatform.api.v2.config;

import com.perfplatform.api.v2.spec.SpecificationParser;
import com.perfplatform.api.v2.spec.SpecificationValidationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the V2 API's collaborators.
 *
 * <p>The parser and validation service are plain classes with constructor injection and no
 * stereotype annotations, so they can be unit-tested without Spring and so the domain rules they
 * apply are not entangled with the framework. This configuration is the single place that turns
 * them into beans.
 */
@Configuration
public class V2ApiConfiguration {

    @Bean
    public SpecificationParser specificationParser() {
        return new SpecificationParser();
    }

    /**
     * @param engines    the engines this deployment has, so a document naming one it does not have
     *                   is refused while somebody is still editing it rather than at execution time
     * @param gitSources the repositories a Playwright test may be imported from. Supplied by the
     *                   composition root; absent, nothing may be imported, which is the correct
     *                   reading of a deployment that has not declared any
     */
    @Bean
    public SpecificationValidationService specificationValidationService(SpecificationParser parser,
            com.perfplatform.domain.port.EngineCatalog engines,
            com.perfplatform.domain.spec.GitSourceAllowlist gitSources,
            org.springframework.beans.factory.ObjectProvider<com.perfplatform.domain.admission.ExecutionLimits> limits) {
        return new SpecificationValidationService(parser, engines, gitSources,
                limits.getIfAvailable(() -> com.perfplatform.domain.admission.ExecutionLimits.DEFAULTS));
    }

    /**
     * The fail-closed default.
     *
     * <p>Overridden by the composition root when an operator has declared repositories. Defined
     * here so that a deployment which says nothing still starts, and refuses git-pinned imports with
     * a finding that names the missing configuration rather than failing to wire.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public com.perfplatform.domain.spec.GitSourceAllowlist gitSourceAllowlist() {
        return com.perfplatform.domain.spec.GitSourceAllowlist.none();
    }
}

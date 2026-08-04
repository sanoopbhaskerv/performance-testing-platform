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
     * @param engines the engines this deployment has, so a document naming one it does not have is
     *                refused while somebody is still editing it rather than at execution time
     */
    @Bean
    public SpecificationValidationService specificationValidationService(SpecificationParser parser,
            com.perfplatform.domain.port.EngineCatalog engines) {
        return new SpecificationValidationService(parser, engines);
    }
}

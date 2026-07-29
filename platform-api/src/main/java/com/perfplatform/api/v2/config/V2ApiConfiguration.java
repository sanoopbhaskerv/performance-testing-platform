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

    @Bean
    public SpecificationValidationService specificationValidationService(SpecificationParser parser) {
        return new SpecificationValidationService(parser);
    }
}

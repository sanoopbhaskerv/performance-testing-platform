package com.perfplatform.domain.spec;

import com.perfplatform.domain.spec.support.SpecEnums;

/**
 * Where a workload's requests or journeys come from.
 *
 * <p>Each type is handled by its own importer, which produces a draft specification plus
 * validation findings. An importer never grants permission to execute against the target.
 */
public enum SourceType {
    OPENAPI,
    POSTMAN,
    HAR,
    CURL,
    MANUAL,
    /** An existing test imported by repository, immutable ref and path. */
    PLAYWRIGHT;

    public static SourceType from(String raw) {
        return SpecEnums.parse(SourceType.class, raw, "source type");
    }
}

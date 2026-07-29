package com.perfplatform.domain.spec;

import com.perfplatform.domain.spec.support.SpecEnums;

/**
 * Classification of the environment a test targets.
 *
 * <p>This drives policy, not documentation: production targets require approval, and the
 * classification is one of the inputs admission control uses to decide whether a run may proceed.
 * It is declared by the consumer but verified server-side against the application's registered
 * environments — a specification cannot promote its own target to a safer class.
 */
public enum TargetClass {
    NON_PRODUCTION,
    PRODUCTION;

    public static TargetClass from(String raw) {
        return SpecEnums.parse(TargetClass.class, raw, "target class");
    }
}

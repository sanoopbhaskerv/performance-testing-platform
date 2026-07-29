package com.perfplatform.domain.spec;

import com.perfplatform.domain.spec.support.SpecEnums;

/**
 * How a protocol workload's load is shaped over time.
 *
 * <p>Only {@code STAGES} is supported initially. Load, stress, spike and endurance tests are all
 * expressible as a staged ramp, so the platform models the shape rather than naming fixed test
 * types — which is what forced V1 to keep three near-duplicate JMX templates in step.
 */
public enum LoadModelType {
    STAGES;

    public static LoadModelType from(String raw) {
        return SpecEnums.parse(LoadModelType.class, raw, "load model type");
    }
}

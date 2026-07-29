package com.perfplatform.domain.spec;

import com.perfplatform.domain.spec.support.SpecEnums;

/**
 * The class of workload a spec entry describes.
 *
 * <p>Kept separate from the engine name: a workload declares what it needs, and the engine that
 * satisfies it is resolved through the engine SPI. Protocol and browser workloads are budgeted
 * separately because a browser session costs orders of magnitude more than a protocol virtual
 * user.
 */
public enum WorkloadType {
    PROTOCOL,
    BROWSER;

    public static WorkloadType from(String raw) {
        return SpecEnums.parse(WorkloadType.class, raw, "workload type");
    }
}

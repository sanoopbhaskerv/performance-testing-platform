package com.perfplatform.engine.spi;

/**
 * The class of workload an engine produces.
 *
 * <p>This distinction is load-bearing for admission control. A protocol virtual user and a
 * browser session differ by roughly two orders of magnitude in resource cost, so they are
 * budgeted, sharded and capped separately. Treating 1,000 browser sessions as 1,000 protocol
 * virtual users would exhaust a cluster while appearing to be within quota.
 */
public enum WorkloadKind {

    /** HTTP or other protocol-level load. Cheap per virtual user; scales to thousands. */
    PROTOCOL,

    /** Real browser sessions measuring navigation, rendering and user-visible behavior. */
    BROWSER
}

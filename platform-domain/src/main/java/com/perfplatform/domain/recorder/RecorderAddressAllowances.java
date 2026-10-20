package com.perfplatform.domain.recorder;

/**
 * Named exceptions to the recorder address floor.
 *
 * <p>This type exists so a relaxation is a visible object rather than a missing check. The floor in
 * {@link RecorderAddressPolicy} denies loopback because an interactive browser inside a runner
 * network must not use the pod's own localhost as a tunnel. That reasoning does not hold on a
 * developer's laptop, where the target genuinely <em>is</em> {@code http://localhost:8081} and the
 * strict floor makes the recorder impossible to exercise at all.
 *
 * <p>{@link #strict()} is the default everywhere. The relaxed variant is reachable only from
 * configuration that a production profile refuses, and the session that uses it says so in its audit
 * detail — the same treatment the network-enforcement bypass already gets.
 *
 * @param loopbackPermitted whether a destination may resolve to a loopback address
 */
public record RecorderAddressAllowances(boolean loopbackPermitted) {

    private static final RecorderAddressAllowances STRICT = new RecorderAddressAllowances(false);
    private static final RecorderAddressAllowances LOOPBACK =
            new RecorderAddressAllowances(true);

    /** The floor every shared or production deployment uses. */
    public static RecorderAddressAllowances strict() {
        return STRICT;
    }

    /**
     * Permits loopback destinations. Local development only.
     *
     * <p>Everything else in the floor — metadata endpoints, link-local, wildcard, multicast and the
     * configured prohibited ranges — still applies. This is not a way to turn the floor off.
     */
    public static RecorderAddressAllowances permittingLoopback() {
        return LOOPBACK;
    }

    public boolean isRelaxed() {
        return loopbackPermitted;
    }
}

package com.perfplatform.v2;

/**
 * Recorder security mode chosen by deployment configuration.
 *
 * <p>The setting exists primarily for local testing. Production profiles refuse to disable network
 * enforcement before any recorder implementation is exposed.
 */
public record RecorderSecuritySettings(boolean networkEnforcementEnabled) {
}

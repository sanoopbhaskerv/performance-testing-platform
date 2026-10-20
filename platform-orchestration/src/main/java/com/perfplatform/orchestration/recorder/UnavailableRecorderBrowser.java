package com.perfplatform.orchestration.recorder;

import com.perfplatform.domain.port.RecorderBrowser;

/**
 * The bridge for a deployment that has no browser to offer.
 *
 * <p>Selected when the local browser is switched off, when no Chrome is installed, or in the
 * shared-runner profile before a recorder cell exists. It exists so the absence of a browser is a
 * configured, describable state rather than a null: a session against this adapter stays in
 * {@code PENDING_BROWSER_BRIDGE} and {@link #describe()} tells the user why, instead of the console
 * reporting a wait that will never end.
 */
public final class UnavailableRecorderBrowser implements RecorderBrowser {

    private final String reason;

    public UnavailableRecorderBrowser(String reason) {
        this.reason = reason == null || reason.isBlank()
                ? "No recorder browser is configured for this deployment."
                : reason;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String describe() {
        return "none (" + reason + ")";
    }

    @Override
    public RecorderBrowserHandle open(RecorderBrowserRequest request) {
        throw new RecorderBrowserUnavailableException(reason);
    }
}

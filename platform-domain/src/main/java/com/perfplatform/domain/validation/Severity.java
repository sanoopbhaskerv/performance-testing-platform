package com.perfplatform.domain.validation;

/**
 * How seriously a validation finding should be taken.
 *
 * <p>Severity decides outcomes, not presentation: a specification with any {@link #ERROR} cannot
 * be saved as a definition version or executed, while warnings are surfaced and proceed. Keeping
 * that rule in one place stops "advisory" findings from quietly becoming blocking ones.
 */
public enum Severity {

    /** Informational. Something worth knowing; changes nothing. */
    INFO,

    /**
     * Accepted, but likely not what the author intended — for example a scenario weight set that
     * does not sum to 100, or an endurance run long enough to need a scheduled window.
     */
    WARNING,

    /** Rejected. The specification cannot be versioned or executed until it is fixed. */
    ERROR
}

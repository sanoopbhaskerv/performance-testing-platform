package com.perfplatform.domain.port;

import java.util.Set;

/**
 * Which execution engines this deployment has.
 *
 * <p>A port so that validation can ask the question without the domain acquiring the engine SPI. The
 * answer is a deployment fact, not a property of a specification: the same document is executable on
 * a platform with a Playwright engine and not on one without.
 *
 * <p>Deliberately just the identifiers. Richer capability questions — maximum concurrency, supported
 * source types, whether an engine can shard — belong on the SPI's own capability contract, and
 * asking them here would drag the whole of it into the domain for one validation rule.
 */
public interface EngineCatalog {

    /** Registered engine identifiers, lower-case. Empty means the platform can execute nothing. */
    Set<String> engineIds();
}

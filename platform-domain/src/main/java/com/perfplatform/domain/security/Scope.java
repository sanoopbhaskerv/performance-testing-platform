package com.perfplatform.domain.security;

/**
 * The permissions a caller can hold.
 *
 * <p>Constants rather than an enum: scopes are carried in OIDC tokens as strings and the set will be
 * extended by configuration, not only by a release. An enum would force every unrecognized scope in
 * a token to be either dropped or a parse failure.
 *
 * <p>Holding a scope is necessary but never sufficient. Every check is scope <em>and</em> team
 * ownership — {@code runs:execute} means "may execute runs for teams you belong to", never "may
 * execute any run". V1's flaw was precisely this: a valid key authorized every team's data.
 */
public final class Scope {

    /** Read test definitions and their versions. */
    public static final String DEFINITIONS_READ = "definitions:read";

    /** Create definitions and append versions. */
    public static final String DEFINITIONS_WRITE = "definitions:write";

    /** Trigger runs. This is the scope that causes load to be generated. */
    public static final String RUNS_EXECUTE = "runs:execute";

    /** Stop a run. Separate from execute so a responder can stop a run without starting any. */
    public static final String RUNS_CANCEL = "runs:cancel";

    /** Read run state, events, results and artifacts. */
    public static final String RUNS_READ = "runs:read";

    /** Start a browser recording session for an owned application target. */
    public static final String RECORDINGS_CREATE = "recordings:create";

    /** Read a recorder session's status, blocked destinations and generated draft artifacts. */
    public static final String RECORDINGS_READ = "recordings:read";

    /** Stop an active browser recording session. */
    public static final String RECORDINGS_CANCEL = "recordings:cancel";

    /** Approve a version that governance requires approval for. */
    public static final String APPROVALS_GRANT = "approvals:grant";

    /** Platform administration, including cross-team visibility. */
    public static final String PLATFORM_ADMIN = "platform:admin";

    private Scope() {
    }
}

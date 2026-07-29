/**
 * The {@code /api/v2/**} REST surface.
 *
 * <h2>Module contract</h2>
 * Controllers, request and response DTOs, bean validation and RFC 7807 problem responses. Calls
 * orchestration use cases; never a Spring Data repository directly. Persistence entities are
 * never returned as responses — V1 does exactly that, which makes the storage schema the public
 * contract and leaks internal paths to clients.
 *
 * <h2>Rules this module enforces</h2>
 * <ul>
 *   <li>{@code POST /api/v2/test-runs} requires an idempotency key, references an immutable
 *       definition version, accepts only permitted runtime overrides, returns {@code 202
 *       Accepted} with a {@code Location} header, and does not block until the test finishes.</li>
 *   <li>Errors carry a stable error code, HTTP status, human-readable detail, correlation ID and
 *       field findings where relevant. Stack traces, SQL errors, Kubernetes objects and
 *       secret-provider details are never exposed.</li>
 *   <li>Authorization is evaluated on both scope and team resource ownership, for every request.</li>
 *   <li>Server-Sent Events may carry live status but are never the durable event store; clients
 *       must be able to fall back to polling.</li>
 * </ul>
 *
 * <h2>Coexistence with V1</h2>
 * V2 is additive and never reuses a V1 path. {@code /api/v1/**} stays operational, served by
 * {@code com.perfplatform.api.PlatformController} in {@code platform-core}, until its V2
 * replacement is verified and removal is explicitly approved.
 *
 * <h2>Increment status</h2>
 * Increment 1A establishes the module and its dependency direction.
 * {@code POST /api/v2/test-definitions/validate} arrives in Increment 1B; the run endpoints
 * arrive in Increment 1C.
 *
 * @see <a href="../../../../../../../docs/migration/v1-to-v2.md">Migration strategy</a>
 */
package com.perfplatform.api.v2;

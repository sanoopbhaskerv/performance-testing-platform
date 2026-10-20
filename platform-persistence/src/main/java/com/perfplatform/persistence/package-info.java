/**
 * PostgreSQL persistence adapters and Flyway migrations.
 *
 * <h2>Module contract</h2>
 * Implements repository ports defined by the domain; the domain never depends on this module.
 * Allowed dependencies are {@code platform-domain}, Spring Data and Flyway. JPA entities defined
 * here are never exposed as REST responses — mapping to DTOs is {@code platform-api}'s job.
 *
 * <h2>Rules this module enforces</h2>
 * <ul>
 *   <li>Schema changes go through Flyway migrations. {@code ddl-auto: update} is disabled outside
 *       local-only development, so a schema change is a reviewed artifact rather than a side
 *       effect of an entity edit.</li>
 *   <li>Queue claiming uses PostgreSQL row-level claim semantics with a durable owner and an
 *       expiring lease, isolated in this module so no database-specific logic leaks into domain
 *       services.</li>
 *   <li>Concurrent state transitions are guarded by optimistic locking.</li>
 *   <li>PostgreSQL via Testcontainers is the authoritative integration-test database. H2 remains
 *       a local-development convenience only: it will not reproduce the locking semantics the
 *       queue depends on, so a green H2 test proves nothing about concurrent claiming.</li>
 * </ul>
 *
 * <h2>Increment status</h2>
 * Increment 1A establishes the module and its dependency direction. The schema — {@code teams},
 * {@code applications}, {@code test_definitions}, {@code test_definition_versions},
 * {@code execution_plans}, {@code test_runs}, {@code runner_shards}, {@code run_events},
 * {@code artifacts} and {@code objective_results} — arrives in Increments 1B and 1C.
 *
 * <p>V1 persistence remains in {@code com.perfplatform.storage} in {@code platform-core} and
 * continues to serve {@code /api/v1/**} until its V2 replacement is verified.
 *
 * @see <a href="../../../../../../docs/adr/002-postgresql-durable-orchestration.md">ADR 002</a>
 */
package com.perfplatform.persistence;

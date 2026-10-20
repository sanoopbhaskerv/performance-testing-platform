/**
 * Admission control, queue claiming, the run state machine and reconciliation.
 *
 * <h2>Module contract</h2>
 * Depends on {@code platform-domain} and reaches engines and runners only through
 * {@code platform-engine-spi} and execution ports. It must not construct engine commands, talk to
 * a Kubernetes client directly, or import persistence entities: those are adapter concerns,
 * injected as port implementations by the composition root.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li><b>Admission control</b> — global and per-team concurrency, expected virtual users,
 *       browser budget, target and environment windows, runner-cell health and approval
 *       requirements. Enforced server-side: values embedded in a generated JMX or an imported
 *       Playwright script are never trusted to enforce a quota.</li>
 *   <li><b>Queue claiming</b> — multiple replicas must not claim the same run, claims expire and
 *       recover when a scheduler dies, and per-team fairness stops one team occupying every
 *       global slot.</li>
 *   <li><b>State transitions</b> — centralized validation, an append-only event per transition,
 *       optimistic locking, and idempotent cancellation.</li>
 *   <li><b>Reconciliation</b> — every operation is idempotent, because the reconciler
 *       re-observes the same state repeatedly and must survive a control-plane restart mid-run.</li>
 * </ul>
 *
 * <h2>Safety rule</h2>
 * A failed runner is never automatically replayed unless the execution policy explicitly allows
 * it. Retrying a load test duplicates real traffic against a real target and invalidates the
 * results.
 *
 * <h2>Increment status</h2>
 * Increment 1A establishes the module and its dependency direction. The state machine, admission
 * policy, queue claiming, cancellation and reconciler arrive in Increment 1C.
 *
 * @see <a href="../../../../../../docs/adr/001-control-execution-separation.md">ADR 001</a>
 */
package com.perfplatform.orchestration;

/**
 * Engine-neutral domain model for the performance testing platform.
 *
 * <h2>Module contract</h2>
 * This is the innermost module. It depends on the Java standard library and narrow validation
 * libraries only. The following are forbidden here and the build fails if one appears:
 * <ul>
 *   <li>Spring ({@code org.springframework.*})</li>
 *   <li>JPA and servlet types ({@code jakarta.persistence.*}, {@code jakarta.servlet.*})</li>
 *   <li>Jackson and other serialization annotations</li>
 *   <li>Kubernetes clients</li>
 *   <li>Engine-specific types such as JMeter or Freemarker</li>
 *   <li>API DTOs</li>
 * </ul>
 *
 * <p>The reason is not stylistic. A domain that carries JPA annotations is shaped by its storage
 * schema, and a domain that carries engine types is shaped by whichever engine was implemented
 * first — which is how V1's model became JMeter-shaped and unable to express a browser workload.
 *
 * <h2>Contents</h2>
 * Increment 1A establishes strongly typed resource identifiers
 * ({@link com.perfplatform.domain.identity}). The aggregates, the canonical
 * {@code PerformanceTestSpecification} and the run state machine arrive in Increments 1B and 1C.
 *
 * @see <a href="../../../../../../docs/architecture/performance-testing-platform-target-architecture.md">Target architecture §7</a>
 */
package com.perfplatform.domain;

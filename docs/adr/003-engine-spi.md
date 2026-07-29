# ADR 003 — Engines integrate through an SPI

**Status:** Accepted
**Date:** 2026-07-29
**Relates to:** [Target architecture §5.8, §6](../architecture/performance-testing-platform-target-architecture.md)

## Context

V1 has no engine boundary. `TestRunService` calls `JmeterTestOrchestrator` directly, the domain
model (`TeamConfig`) is shaped around JMeter concepts, and the API accepts only `team` and `type`.
Adding a second engine would mean branching on engine type through orchestration, reporting and
persistence.

Two engines are already required — JMeter for protocol load and Playwright for real-browser
journeys — and they differ fundamentally. A JMeter thread is not a browser session; 1,000
protocol virtual users and 1,000 browser sessions are not comparable quantities and must not be
admitted, sharded or costed the same way. A third engine (k6) is plausible later but is **not
approved** and must not be introduced into the MVP.

The baseline JMeter compiler also demonstrates what happens without a boundary: it flattens all
scenarios into one endpoint list, always reads the load test's scenarios regardless of test type,
and corrupts any numeric value of 1,000 or more via Freemarker digit grouping. These defects are
invisible because nothing separates "what the user asked for" from "what JMeter was told".

## Decision

Engines integrate through a service provider interface. Orchestration talks to the SPI and never
to an engine directly.

The provider contract covers: engine identity, declared capabilities, specification validation,
compilation of an immutable execution plan, sharding against a capacity profile, and
normalization of runner artifacts into an engine-neutral result.

Capabilities are declarative — protocol vs browser, supported source types, supported workload
models, maximum configured concurrency, correlation support, sharding support, required runner
image — so admission control and validation can reason about an engine without knowing which one
it is.

An engine provider must **not**:

- authorize a user or select a team;
- override global or per-team quotas;
- mutate run state in the database;
- return local filesystem paths as durable artifact references.

Those are control-plane responsibilities. An engine that could do them would be able to bypass
the platform's safety controls, which is precisely what a load-generation platform must prevent.

## Alternatives considered

| Alternative | Why it was rejected |
|---|---|
| Branch on engine type in orchestration | Every new engine edits orchestration, persistence and reporting. Engine-specific leakage into the domain is how V1 became JMeter-shaped |
| Ship JMeter only, add a boundary later | The Playwright requirement is already known. Retrofitting a boundary after browser support exists means migrating a second engine's worth of coupling |
| Model browser sessions as ordinary virtual users | Wrong by roughly two orders of magnitude in resource cost, and it would let a browser workload consume the protocol capacity budget |
| Adopt an existing plugin framework | Disproportionate for a small, fixed set of first-party engines |

## Consequences

**Easier.** Playwright and BrowserStack plug in without touching orchestration. Capability
declarations make validation and admission engine-agnostic. Engine defects stay contained in the
engine module. The JMeter compiler's flattening and number-formatting defects become fixable in
one place with contract tests around them.

**Harder.** The normalized result model must be genuinely engine-neutral, which takes real design
work — protocol latency percentiles and browser journey durations must coexist without one being
bolted onto the other. Sharding and capacity profiles must be expressed generically. There is
indirection to read through when debugging a single engine.

**Obligations.** `platform-engine-spi` depends only on `platform-domain` and stays free of
Spring, JPA and Kubernetes types. Engine modules depend on the domain and the SPI, never on
persistence or the API. This is enforced by
`ModuleDependencyRulesTest`, which fails the build on a forbidden import.

**Explicitly out of scope.** k6 is not approved for the MVP. It is technically suitable but is
AGPL-3.0 licensed, which requires enterprise review before adoption. The SPI must leave room for
it without depending on it.

---
name: performance-testing
description: Author, validate, execute, cancel, and inspect API or browser performance tests through a self-hosted Performance Testing Platform. Use for performance requirements, reviewed Playwright journeys, and measured run reports; not for ordinary functional tests.
---

Use the platform's canonical PerformanceTest YAML and REST API. Never invoke a second load generator alongside the platform or reinterpret a synthetic report as measured performance.

Read [the configuration guide](../../docs/user-guide/configuration.md) for the specification and [the CLI guide](../../docs/user-guide/ai-editors.md) for authentication and commands. The deterministic helper is [scripts/perf.py](../../scripts/perf.py); it needs Python 3 only. Resolve these paths relative to this skill's directory, not the editor's current working directory.

- Establish the deployment URL, team, registered application/environment, required traffic, duration, and objectives. Use the user's supplied requirements; ask only for missing material information. Keep API virtual users separate from real browsers. Begin a first browser check with one browser and one iteration.
- Describe API journeys using imported endpoints or ordered steps. Browser tests import an existing reviewed Playwright script or an allowlisted HTTPS repository at a complete immutable commit SHA. The platform does not generate browser scripts from HAR. Generated candidate scripts require human review before material execution.
- Configure secrets by reference or runtime environment. Read the access token from PERF_PLATFORM_TOKEN_FILE or PERF_PLATFORM_TOKEN; never print it or place it in a specification, shell history, source file, or report.
- Upload reviewed browser source with `upload-browser --reviewed`, then reference the returned URI and digest. A changed script must be uploaded again. Do not label a candidate as reviewed on the user's behalf.
- Run `validate` first. This is a configuration check and sends no workload traffic. A user request to run the reviewed requirements authorizes that run; don't ask again when the target and limits are already authorized. Otherwise prepare the exact specification for review before triggering material load.
- Create the definition, or append a version to an explicitly selected existing definition. Trigger that immutable version with a stable idempotency key. Reuse the key on a transport retry; a new key creates a new material run. Never automatically replay failed load.
- Wait for the durable run, read the report, and distinguish COMPLETED, failed objectives, execution failures, cancellation, incomplete evidence, and INDETERMINATE objectives. A wait timeout does not stop the run: report its ID and use `cancel` only when requested or when an authorized stop condition applies.
- Report the run ID, definition version, synthetic flag, actual sample counts, objective verdicts, and evidence references. Missing measurements are not zero. If synthetic=true or NO_LOAD_GENERATED, say clearly that no real performance test occurred.

Keep extensions behind the domain ports and engine SPI. Use [the extension guide](../../docs/development/extending.md) when modifying the product rather than invoking it.

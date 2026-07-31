# Start here

**Read `HANDOFF.md` first** (state, decisions, gotchas), then this. `CLAUDE.md` is the contract.

Branch `feature/platform-v2-foundation`. Working tree clean. **Nothing is pushed** — pushing, tagging
and opening a PR each need explicit authorization (CLAUDE.md §6).

---

## 1. Build and run

```bash
# JAVA_HOME is NOT set in the shell profile. Every mvn call must supply it.
# Only JDK 21 is installed; the project targets Java 17. Do not change java.version.
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  mvn -B -f pom.xml clean verify
```

Expect **648 tests, 0 failures, 0 skipped**. If a PostgreSQL module fails wholesale with
`An error occurred while setting up the SSL connection`, that is a known flaky Testcontainers
start — retry once before investigating. If any integration test reports `Skipped`, that is a
false green: investigate, do not accept it.

```bash
# Run it (terminal 1)
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  java -jar platform-core/target/platform-core-1.0.0.jar --platform.config-dir=config/teams

# Run the UI (terminal 2)
cd platform-ui && npm run dev
```

Open <http://localhost:5173>, **Performance tests** tab, key `local-dev-key-not-a-secret`.
New test → the **Demo service** template is pre-selected and runs unedited → Save test → Run this.
A run reaches a terminal state in ~30s (three 10s reconciliation passes).

**It ends in `Failed` / `NO_LOAD_GENERATED`, and that is correct.** The default provider generates no
load, so the run measured nothing, so no objective can pass. See `HANDOFF.md` §3b. Real load needs
`brew install jmeter` and `--platform.orchestration.execution-provider=local-jmeter`.

Other traps, all real, all hit at least once:

- Bash working directory **persists between calls**. `cd` into `platform-ui` breaks later commands.
  Always `cd` to the repo root or use absolute paths.
- Single test across the reactor needs `-Dsurefire.failIfNoSpecifiedTests=false`.
- An IDE language server can leave **Eclipse-compiled `.class` files** in `target/test-classes` that
  throw `java.lang.Error: Unresolved compilation problem` at runtime while `mvn test-compile` reports
  success. If a test fails with that message, `mvn clean verify` — it is stale bytecode, not a bug.
- `platform-ui/src/App.css` is **imported by nothing** — `main.tsx` loads only `index.css`.
  Rules added to App.css silently do nothing.
- A background `java -jar` **survives `TaskStop`**. The shell dies, the JVM does not, and the next
  start fails with `Port 8080 was already in use` — while `curl` answers happily from the *stale*
  jar. `lsof -ti:8080 | xargs kill -9` first, or you will verify the wrong build.

---

## 2. Next: Increment 2 — importers

**Increment 1E is finished and proven.** A real Kubernetes Job has run a performance test end to end
on kind: 7,932 requests, two objectives passed and one breached, results in a report. The procedure
is [`docs/operations/local-kubernetes-run.md`](docs/operations/local-kubernetes-run.md) — repeat it
after changing the runner image, the Job manifest or the artifact store. It found three real defects
the first time (`HANDOFF.md` §3f), all presenting as *a run that measured nothing*.

**Increment 2 — importers**: OpenAPI, Postman, HAR, cURL, manual. A scenario can already carry its
own `method` and `path`, so an importer's job is to produce those plus **headers, query parameters
and request bodies**, which the specification still cannot express at all. That is the gap between
this platform and a genuinely useful test of a real API.

Smaller things still open, none blocking:

- **Sharding is untested and unpartitioned.** Everything so far is one shard; multiple shards would
  replay identical work. Dataset partitioning has to come before shard count rises above 1, and
  calibration before that (`docs/operations/runner-calibration.md`).
- **`ConcurrencyThreadGroup` is in the runner image but unused** — the plan generator still emits
  stepwise thread groups. A change to `JmeterWorkloadRenderer`.
- **Not run on EKS**, so IRSA specifically is unexercised; kind used a Secret instead.

**Already delivered — do not rebuild these:** the report endpoint and UI, artifact download with
per-request authorization and digest verification, persisted `run_metrics`, the synthetic-run
marking, the Kubernetes execution provider, the S3 artifact store, artifact adoption with plan-digest
verification, and the JMeter runner image. `HANDOFF.md` §3b–§3f.

---

## 3. Rules that do not bend

- **Stop after the increment you were asked for.** Produce the CLAUDE.md §30 completion report.
- Do not push, tag, merge or publish without explicit authorization.
- V1 must keep working. `/api/v1/**` and the **Legacy (v1)** UI tab are the check.
- Tests named `KNOWN DEFECT` / `KNOWN GAP` / `KNOWN SECURITY GAP` assert behaviour that is wrong on
  purpose. Invert them in place when the owning increment fixes them; never delete one to go green.
- Pause and ask before: a new unapproved dependency, k6, a secret manager, an identity provider, a
  production S3 bucket, production or unrestricted targets, or real credentials.
- **Never present a synthetic run's numbers as evidence of anything**, and never weaken the marking
  that says so. The platform now enforces this in four places; each one is there because a report
  travels further than its caveats.

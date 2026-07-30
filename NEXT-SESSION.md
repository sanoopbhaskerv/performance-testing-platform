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

Expect **580 tests, 0 failures, 0 skipped**. If a PostgreSQL module fails wholesale with
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
New test → **edit `metadata.team` to `local-dev` and `metadata.application` to `local-service`** →
Save test → Run this. A run reaches a terminal state in ~30s (three 10s reconciliation passes).

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
- The console's starter specification names team `alpha` / application `alpha-service`, which do not
  exist in the shipped `config/teams`. Saving it unedited fails with `specification.team.unknown`.

---

## 2. Next: Increment 1E

In order — `HANDOFF.md` §6 has the detail:

1. **Kubernetes execution provider** behind `ExecutionProvider`. Job manifest builder, resource and
   security policy, `backoffLimit: 0`, `activeDeadlineSeconds`, persisted Job UID, observe/cancel.
   **Never auto-replay a failed material run** — that is duplicate load on a real system. It returns
   `true` from `generatesRealLoad()`, which is also what lets its runs reach `COMPLETED`.
2. **S3-compatible `ArtifactStore`.** The port and key layout already match, and
   `FileSystemArtifactStore.read` now resolves a key against a root rather than an in-memory index,
   so the substitution is straight and the download endpoint needs no change.
3. **Runner image with `ConcurrencyThreadGroup`**, so load stages stop being stepwise. Write
   `docs/operations/runner-calibration.md` alongside — shard count is still hardcoded to 1 pending
   calibration.
4. **Housekeeping:** delete `BaselineExecutionPlanCompiler` (dead since `EngineDispatchingPlanCompiler`
   replaced it); add the missing tests listed in `HANDOFF.md` §3a; align the console's starter
   specification with the shipped team config.

**Already delivered — do not rebuild these:** the report endpoint, the report UI, artifact download
with per-request authorization and digest verification, persisted `run_metrics`, and the synthetic-run
marking. `HANDOFF.md` §3b is the record.

Not 1E, but worth knowing the shape of: a scenario's `operationId` has no real path, method, headers
or body until importers land in **Increment 2**. The renderer emits `GET /{operationId}` —
deliberately obvious rather than plausible. Reports look thin for that reason, and that is honest
rather than a defect to hide.

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

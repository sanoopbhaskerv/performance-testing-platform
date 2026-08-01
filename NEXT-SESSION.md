# Start here

**Read `HANDOFF.md` first** (state, decisions, gotchas), then this. `CLAUDE.md` is the contract.

Branch `feature/platform-v2-foundation`. Working tree clean at `9fcefe1`. **Nothing is pushed** —
pushing, tagging and opening a PR each need explicit authorization (CLAUDE.md §6).

---

## 1. Build and run

```bash
# JAVA_HOME is NOT set in the shell profile. Every mvn call must supply it.
# Only JDK 21 is installed; the project targets Java 17. Do not change java.version.
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  mvn -B -f pom.xml clean verify
```

Expect **684 tests, 0 failures, 0 skipped** across 11 modules. Docker must be running (Testcontainers
uses PostgreSQL and MinIO). If a PostgreSQL module fails wholesale with `An error occurred while
setting up the SSL connection`, that is a known flaky start — retry once. **If any integration test
reports `Skipped`, that is a false green: investigate, do not accept it.**

```bash
# Run it (terminal 1)
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  java -jar platform-core/target/platform-core-1.0.0.jar --platform.config-dir=config/teams

# Run the UI (terminal 2)
cd platform-ui && npm run dev
```

Open <http://localhost:5173>, **Performance tests** tab, key `local-dev-key-not-a-secret`.
New test → the **Demo service** template is pre-selected and runs unedited → Save test → Run this.

**It ends in `Failed` / `NO_LOAD_GENERATED`, and that is correct.** The default provider generates no
load, so the run measured nothing, so no objective can pass (`HANDOFF.md` §3b). For real load:
`brew install jmeter`, start the demo service, and add
`--platform.orchestration.execution-provider=local-jmeter`. Full guide in `README.md`.

For the Kubernetes path, `docs/operations/local-kubernetes-run.md` is the whole procedure on kind.

### Traps, all real, all hit at least once

- Bash working directory **persists between calls**. `cd platform-ui` breaks every later command.
  Always `cd` to the repo root or use absolute paths. This has bitten four times.
- A background `java -jar` **survives `TaskStop`**. The shell dies, the JVM does not; the next start
  fails with `Port 8080 was already in use` while `curl` answers happily from the *stale* jar.
  `lsof -ti:8080 | xargs kill -9` first, or you will verify the wrong build. Same for vite on 5173.
- An IDE language server can leave **Eclipse-compiled `.class` files** in `target/test-classes` that
  throw `java.lang.Error: Unresolved compilation problem` at runtime while `mvn test-compile` reports
  success. `mvn clean verify` — it is stale bytecode, not a bug.
- Single test across the reactor needs `-Dsurefire.failIfNoSpecifiedTests=false`.
- `platform-ui/src/App.css` is **imported by nothing** — `main.tsx` loads only `index.css`.
- **UI lint reports 10 errors, 3 warnings, all pre-existing**, in `App.tsx` and the pre-1D parts of
  `PerformanceConsole.tsx`. Verified by linting the committed files. Do not "fix" them as part of
  something else; the count rose from 3 without code changing, because eslint's React rules began
  analysing a file that always had them.

---

## 2. Where I stopped, mid-Increment 2

Increment 2 (CLAUDE.md §28) is large: five importers plus dataset partitioning, dry-run preflight,
baseline comparisons and Jenkins integration. I split it. **Two parts are done and committed:**

- **2a — a specification can express a whole request** (`1b61d0e`). Headers, query parameters and
  bodies, in the digest, with credential refusals. This was the gap; everything else builds on it.
- **2b — the cURL importer** (`9fcefe1`). `SourceImporter` port, `platform-importers/importer-curl`,
  and `POST /api/v2/imports/{sourceType}`.

**The immediate next step is 2c — the OpenAPI importer.** The scaffolding is all in place:

1. New module `platform-importers/importer-openapi`, modelled exactly on `importer-curl` — same pom
   shape, `platform-domain` only, no Spring. Register it in the root `pom.xml` `<modules>` and
   `<dependencyManagement>`, add it to `platform-core/pom.xml`, and add a `@Bean` beside
   `curlImporter()` in `OrchestrationConfiguration`. The controller finds it through the port;
   nothing enumerates importers.
2. Add it to `ModuleDependencyRulesTest.importersAreIsolated()` — that test currently names
   `importer-curl` explicitly and will not cover a new module until you add it.
3. **It needs a YAML/JSON parser and `platform-domain` has none by design.** Jackson is already a
   dependency of `platform-api`, not of the domain. Either put the module's parsing behind its own
   Jackson dependency (fine — an importer is an adapter, and `ModuleDependencyRulesTest` does not
   forbid `SERIALIZATION` for importers as written), or pass a parsed tree in. Decide deliberately.
4. CLAUDE.md §23 is specific about OpenAPI: import operations, methods, paths, parameters and
   schemas; **preserve `operationId`**; allow user mapping of examples to datasets; and
   **do not invent valid business data silently.** A schema says a field is a string, not which
   string. Inventing one produces a test that exercises validation-failure paths at scale and reports
   them as latency — emit a finding and leave it for a person.

Then the rest of Increment 2, in rough order of value: **HAR** (sanitize cookies, auth and sensitive
query parameters; group into candidate journeys), **Postman** (flag scripts, never execute them),
**dataset partitioning** (also unblocks sharding — see below), dry-run preflight, baseline
comparisons, Jenkins shared library.

### Smaller things still open

- **Sharding is untested and unpartitioned.** Everything so far is `PERF_SHARD_COUNT=1`; multiple
  shards would replay identical work. Dataset partitioning must land before shard count rises, and
  calibration before that (`docs/operations/runner-calibration.md`).
- **`ConcurrencyThreadGroup` is in the runner image but unused** — the plan generator still emits
  stepwise thread groups. A change to `JmeterWorkloadRenderer`.
- **Not run on EKS**, so IRSA specifically is unexercised; the kind run used a credentials Secret.
- **A UI path for importing does not exist.** The endpoint works; the console has no button.

### Already delivered — do not rebuild

The report endpoint and UI, artifact download with per-request authorization and digest verification,
persisted `run_metrics`, synthetic-run marking, the Kubernetes execution provider, the S3 artifact
store, artifact adoption with plan-digest verification, the JMeter runner image, the request model,
and the cURL importer. `HANDOFF.md` §3b–§3g.

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
  that says so. The platform enforces this in four places; each is there because a report travels
  further than its caveats.
- **A specification never contains a credential value.** Headers, query parameters and bodies are all
  checked, and importers strip what they find. If you add a field that can hold a string a consumer
  authored, ask whether a secret can be written into it.

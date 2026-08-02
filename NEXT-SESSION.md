# Start here

**Read `HANDOFF.md` first** (state, decisions, gotchas), then this. `CLAUDE.md` is the contract.

Branch `feature/platform-v2-foundation`. **Nothing is pushed** — pushing, tagging and opening a PR
each need explicit authorization (CLAUDE.md §6).

---

## 1. Run it

```bash
./scripts/start-platform.sh
```

One command. It starts the **demo service** (:8081), the **control plane** (:8080) and the
**console** (:5173), clears stale ports first, waits for each to answer, and prints the API key and
what to click. Ctrl-C stops all three; `--stop` cleans up after a run that was killed.

- `--build` rebuilds the Java modules first. Without it, existing jars are reused.
- `--fake` forces the non-load-generating provider.
- It picks **local-jmeter** automatically when `jmeter` is on PATH, and says which it chose. With
  the fake provider a run ends in **`Failed` / `NO_LOAD_GENERATED`** — correct, not broken: the run
  measured nothing, so nothing can pass (`HANDOFF.md` §3b). `brew install jmeter` for real load.

Open <http://localhost:5173>, **Performance tests** tab, key `local-dev-key-not-a-secret`.
New test → the **Demo service** template runs unedited → Save test → Run this. Or **Import
journeys** and paste a cURL command, an OpenAPI document, a HAR or a Postman collection.

### Verifying a change

```bash
# JAVA_HOME is NOT set in the shell profile. Every mvn call must supply it.
# Only JDK 21 is installed; the project targets Java 17. Do not change java.version.
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  mvn -B -f pom.xml clean verify
```

Expect **833 tests, 0 failures, 0 skipped** across 14 modules. Docker must be running (Testcontainers
uses PostgreSQL and MinIO). If a PostgreSQL module fails wholesale with `An error occurred while
setting up the SSL connection`, that is a known flaky start — retry once. **If any integration test
reports `Skipped`, that is a false green: investigate, do not accept it.**

```bash
cd platform-ui && npm run lint && npm run build     # 10 errors, 3 warnings, all pre-existing
```

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
- **`Tests run: 0` for a class with `@Nested` classes was cosmetic, never lost coverage** — Surefire
  ≤3.5.2 filed the outer class's own tests into the last nested class's report file. The root pom now
  pins **Surefire 3.5.3**, which attributes correctly. Mixing top-level and `@Nested` tests is safe.
  A `Tests run: 0` line is still correct for a class where every test is nested. `HANDOFF.md` §2/§3h.

---

## 2. What to do next

**`ROADMAP.md` is the full list. Read §0 of it first — it says where to stop.**

The short version: **the foundation is complete** (CLAUDE.md §32, with team authorization enforced
against a development identity provider until Increment 4). There is **one loose thread**:

> **A dataset can be declared, bound to with `${column}` and validated — but not delivered.**
> Uploading and storing the file is Increment 2h. A specification can reference `${orderId}`, pass
> validation, and find no data at run time.

**Recommended: do 2h, then stop.** One increment; it closes Increment 2 and turns the last
half-built thing in the tree into a whole one. `ROADMAP.md` §1 has the four steps and the one real
decision (the artifact port is run-scoped; a dataset outlives a run).

Everything else — dry-run preflight, baseline comparisons, correlation and extraction, Playwright,
enterprise identity, assisted authoring — is in `ROADMAP.md` §§2–5, with a size and a reason. None of
it is urgent, and none of it should be started because it is next in a list.

### What is already done — do not rebuild

Increments 0 through 2g. The canonical specification, durable run orchestration, the JMeter engine
behind the SPI, the Kubernetes provider proven on a real cluster, the S3 artifact store, the runner
image, report and artifact download, synthetic-run marking, **four source importers**, the console's
import panel, and dataset binding and partitioning. `HANDOFF.md` §3–§3l is the account of each.

---

## 3. Rules that do not bend

- **Stop after the increment you were asked for.** Produce the CLAUDE.md §30 completion report.
- **Finish every increment the same way**, before anything else is started:
  1. Update `HANDOFF.md` — the status table, the test count, and a new `§3x` section saying what was
     built, what was decided and *why*, and what was deliberately left undone.
  2. Update `NEXT-SESSION.md` §2 and `ROADMAP.md` so the next person's first move is obvious.
  3. **Commit**, code and documents together, with a message in this repo's style: an imperative
     sentence naming the outcome a consumer gets, not the files touched. `git log` for examples —
     *"Import a cURL command into draft scenarios"*, *"Let a specification express a whole request"*,
     *"Prove the Kubernetes path on a real cluster, and fix what that found"*. The body carries the
     detail: what changed, what was decided, what is verified, what is not.
  A commit whose documents still describe the previous state is how a fresh context starts by
  rebuilding something that already exists.
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

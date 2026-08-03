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
**New test** opens the **studio**: choose *Test an API*, bring a source (a cURL command, an OpenAPI
document, a HAR or a Postman collection), review the journeys, set the load and the objectives, and
save. **Write it as YAML** is the expert path and still carries the Demo service template, which
runs unedited.

### Verifying a change

```bash
# JAVA_HOME is NOT set in the shell profile. Every mvn call must supply it.
# Only JDK 21 is installed; the project targets Java 17. Do not change java.version.
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  mvn -B -f pom.xml clean verify
```

Expect **898 tests, 0 failures, 0 skipped** across 14 modules. Docker must be running (Testcontainers
uses PostgreSQL and MinIO). If a PostgreSQL module fails wholesale with `An error occurred while
setting up the SSL connection`, that is a known flaky start — retry once. **If any integration test
reports `Skipped`, that is a false green: investigate, do not accept it.**

```bash
cd platform-ui && npm test && npm run lint && npm run build   # 3 errors, 3 warnings, all pre-existing in V1's App.tsx
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
- **UI lint reports 3 errors, 3 warnings, all pre-existing**, all in V1's `App.tsx`. Do not "fix"
  them as part of something else. A previous session recorded 10; that was a **drifted
  `node_modules`**, not a real change — `npm ci` in a clean worktree at HEAD gives 3. Reinstall
  before believing a different number.
- **`Tests run: 0` for a class with `@Nested` classes was cosmetic, never lost coverage** — Surefire
  ≤3.5.2 filed the outer class's own tests into the last nested class's report file. The root pom now
  pins **Surefire 3.5.3**, which attributes correctly. Mixing top-level and `@Nested` tests is safe.
  A `Tests run: 0` line is still correct for a class where every test is nested. `HANDOFF.md` §2/§3h.

---

## 2. What to do next

**Read [`PLAN.md`](PLAN.md) first. It is the decision about what happens next**; `ROADMAP.md` is now
the inventory of what exists, and `HANDOFF.md` §3 is the account of how each piece was built.

**The direction: turn this into a Performance Studio.** One UI where someone with **no performance
testing experience** configures a test, watches it run live, and reads a report they can act on. Two
first-class capabilities — **API load testing** (one or many APIs) and **web performance testing**
(record a journey, upload a HAR, or bring a Playwright script; Core Web Vitals) — plus the pairing of
the two, which is the flagship: *is the page still fast when the backend is at 500 users?*

`PLAN.md` sequences this as **S1–S12**, each shipping a backend capability **and** the studio screen
that exposes it. Nothing lands API-only.

### Release 1 is S1–S6. Decisions are made — do not relitigate them

| Decision | Chosen |
|---|---|
| **vitest** | **Approved and added in S1.** §31 pause cleared. 88 tests. |
| **Recorder** | Deferred to S10. Release 1 ships **guided capture wizards**: DevTools HAR for protocol journeys, `npx playwright codegen` for browser journeys. |
| **Playwright import** | **In release 1** — upload or paste the `.spec.ts`, stored immutably by content digest. **Not** a git clone: no credentials, no `GitSourceFetcher`, no §31 pause. Git-pinned import is S10. |
| **Live monitoring** | Deferred to S7 (needs a runner-image change). Release 1 keeps the existing status page plus elapsed, current stage and a progress bar — all derivable from data already persisted. |
| **Arrival-rate sizing, hybrid under-load testing** | Deferred to S8 and S11. |

**S1 is complete** (`HANDOFF.md` §3n). The studio exists: *New test* → pick a test type → import a
source → review journeys → set load → set objectives → the platform validates live → Save. Verified
end to end in a browser against a running control plane. **88 UI tests.**

**The next increment is S2 — the report a novice can read**, plus the status page gaining elapsed
time, current stage and a progress bar. No runner-image change: all of it is derivable from the
plan's stage arithmetic and the shard's persisted `startedAt`. `PLAN.md` §11.

**The saving that makes release 1 affordable:** a browser-**only** web performance test is one
workload, one engine, one plan. It does not need S9's multi-plan refactor — only the hybrid does.

### The three facts S1 onward has to work around

Found by reading the code, and each is recorded in `PLAN.md` §2:

1. **A journey is one request.** No correlation, so no login. Fixed in S3.
2. **`spec.authentication` is parsed, validated and used by nothing.** Fixed in S5.
3. **A run has one plan with one engine** — `EngineDispatchingPlanCompiler.primaryEngine()` takes the
   first workload's engine and silently ignores the rest, so the shipped `hybrid.yaml` would drop its
   browser workload with no error. Fixed in S7.

### The caveat to carry forward

2h changed the runner image's entrypoint (it now fetches dataset partitions), and that change has
**not been run on a cluster**. The local-JMeter path is verified with real load. Before trusting the
Kubernetes path with a dataset, re-run `docs/operations/local-kubernetes-run.md`. S2 changes the
runner image again (progress snapshots), so the same procedure applies then.

### Datasets, in one paragraph

`POST /api/v2/datasets?team=…&name=…` with the CSV as the body. It returns a
`dataset://{name}/{version}` uri; put that in `spec.datasets[].source.uri` along with the columns
the test expects, and bind to a column with `${column}` in a journey's path, query, headers or body.
Versions are allocated, never chosen, and never overwritten. A column named as a credential is
refused and nothing is stored. `docs/api/v2-examples.md` has the whole flow.

### What is already done — do not rebuild

Increments 0 through 2h. The canonical specification, durable run orchestration, the JMeter engine
behind the SPI, the Kubernetes provider proven on a real cluster, the S3 artifact store, the runner
image, report and artifact download, synthetic-run marking, **four source importers**, the console's
import panel, dataset binding and partitioning, and dataset upload, storage and delivery.
`HANDOFF.md` §3–§3m is the account of each.

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

# Start here

**Read `HANDOFF.md` first** (state, decisions, gotchas), then this. `CLAUDE.md` is the contract.

Branch `feature/platform-v2-foundation`. **Nothing is pushed** — pushing, tagging and opening a PR
each need explicit authorization (CLAUDE.md §6). Every increment now ends with updated handoff
documents and a commit unless the user explicitly says not to commit.

---

## 1. Run it

```bash
./scripts/start-platform.sh
```

One command. It starts the **demo service** (:8081), the **control plane** (:8080) and the
**console** (:5173), clears stale ports first, waits for each to answer, and prints the API key and
what to click. Ctrl-C stops all three; `--stop` cleans up after a run that was killed.

- `--build` rebuilds the Java modules first. Without it, existing jars are reused.
- It installs JMeter's **Custom Thread Groups plugin** (`jpgc-casutg`) if your JMeter lacks it. Every
  plan needs it now, and the control plane refuses to start without it — JMeter would otherwise skip
  the thread group it cannot resolve and report a successful run that generated no load.
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

S9 ran this to completion: **1,106 tests, 0 failures, 0 skipped** across all 15 modules. S7 was the
first fully green `mvn clean verify` on the branch and it has stayed green since. Docker must be running: Testcontainers uses PostgreSQL
and MinIO. The environment limits earlier sessions recorded (Mockito inline self-attach, sockets
refusing to bind) did **not** recur; if they reappear, note them rather than assuming they are
permanent. If a PostgreSQL module fails wholesale with `An error occurred while setting up the SSL
connection`, that is a known flaky start — retry once. **If any integration test reports `Skipped`,
that is a false green: investigate, do not accept it.**

```bash
cd platform-ui && npm test && npm run lint && npm run build   # 158 tests; 3 errors, 3 warnings, all pre-existing in V1's App.tsx
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
| **vitest** | **Approved and added in S1.** §31 pause cleared. **158 UI tests** now. |
| **Recorder** | S10 has started. The required security review is complete in [`docs/architecture/recorder-security-review.md`](docs/architecture/recorder-security-review.md). S10a added domain-level allowlist, address and route-guard policy; S10b added the recorder pod manifest, egress policy renderer and local-only network-enforcement bypass. Release 1 ships **guided capture wizards** until S10 implements the rest of the reviewed controls. |
| **Playwright import** | **In release 1** — upload or paste the `.spec.ts`, stored immutably by content digest. **Not** a git clone: no credentials, no `GitSourceFetcher`, no §31 pause. Git-pinned import is S10. |
| **Live monitoring** | **Built in S7.** Runners publish rolling snapshots to object storage; the reconciler merges them; the console shows throughput, latency, errors, per-journey breakdown, per-generator health, live objectives and rule-based observations. |
| **Arrival-rate sizing** | **Built in S8**: a test can hold a rate instead of a number of users, with the concurrency ceiling an open model cannot run without. Hybrid under-load testing is still S11. |
| **Dropping V1** | **Authorized** (§31 pause cleared), and the user asked for it **after** the studio build phases. S5 removed the credentials/session blocker, but removal still needs a focused audit because development identity remains local-only and V1 endpoints must stay operational until that increment is explicitly picked up. |

### What is built

- **S1** (`HANDOFF.md` §3n) — the studio. *New test* → pick a test type → import a source → review
  journeys → set load → set objectives → the platform validates live → Save. Verified end to end in
  a browser against a running control plane.
- **S2** (§3o) — the report opens with the answer in a sentence, every metric explains itself where
  it appears, and a running test shows where it is in its plan.
- **S3** (§3p, §3q, §3r) — a test can sign in, a HAR capture becomes an ordered journey, and the
  Rules screen proposes correlations with evidence. Scenarios carry ordered steps, values are
  extracted and carried forward, and a broken correlation fails loudly instead of reporting
  excellent latency. Proposed correlations are accepted or rejected by a person and never applied
  silently.
- **S4** (§3s) — the Validator runs one real protocol iteration before Save and shows request,
  response, extraction and assertion evidence.
- **S5** (§3t) — targets, credentials and sessions: runtime credential references, `SecretResolver`,
  broker-backed `run://session`, mounted session partitions, lifecycle cleanup, Helm RBAC and
  production docs.
- **S6a** (§3u) — production-readiness validation for broker-backed `run://session`: kind smoke
  procedure with a stub broker Service, mounted broker-client Secret, runner session Secret
  creation/cleanup checks and a failed-acquisition runbook.
- **S6** (§3v) — browser-only Playwright execution behind the engine SPI, artifact-backed script
  digests, browser profiles, Core Web Vitals objectives and explicit rejection of mixed-engine specs
  until hybrid orchestration exists.

- **S7** (§3w) — live monitoring. Runners publish `progress.json` every five seconds; the reconciler
  merges across shards into a durable series; `GET /api/v2/test-runs/{id}/progress` serves it with
  live objectives and rule-based observations; the console's Monitor shows it. Verified with real
  load at **11–14 s freshness**. Also fixed two pre-existing failures it uncovered — the Spring
  context could not start (two `TargetResolver` beans, since S5), and that masked seventeen broken
  end-to-end tests.

- **S8** (§3x) — load models. A test can hold **requests per second** instead of users, with the
  mandatory concurrency ceiling that admission budgets against instead of the rate; staged
  concurrency now **glides** rather than stepping at boundaries. Both render through JMeter's Custom
  Thread Groups plugin, which the start script installs and the control plane refuses to start
  without. Also fixed a throughput objective that could not be written at all (`Threshold` had no
  per-second unit).

- **S9** (§3y) — one run, many workloads. A run compiles **one plan per workload**, provisions
  shards per workload and analyses every workload's results together, so a protocol load and a
  browser cohort can belong to one run. A workload naming an engine this deployment lacks is now
  refused **at validation**, closing a defect that once dropped later workloads silently. The
  browser-session budget moved to S11, where the thing it governs first becomes possible.

**S10b: 1,115 Java tests, 158 UI tests, `mvn clean verify` fully green.**

**Next: continue S10, the recorder and git-pinned Playwright sources.** `PLAN.md` §5 and §11. The
security review required by `PLAN.md` §12 R2 is complete:
[`docs/architecture/recorder-security-review.md`](docs/architecture/recorder-security-review.md).
S10a added the domain-level target allowlist, address policy and route guard. S10b added the
recorder pod manifest, per-session egress `NetworkPolicy` renderer and local-only network
enforcement bypass. Next, add recorder audit events and capture sanitization. Do not add a clickable
recorder entry point before sanitization exists.

A smaller alternative, if one is wanted: **S11's prerequisites** — the browser-session budget
deferred from S9, and `LoadStage` gaining a name so `startWhen` resolves against something.

**The saving that makes release 1 affordable:** a browser-**only** web performance test is one
workload, one engine, one plan. It does not need S9's multi-plan refactor — only the hybrid does.

### The three facts the studio work has to deal with

Found by reading the code, and each is recorded in `PLAN.md` §2:

1. ~~**A journey is one request.**~~ **Fixed in S3.** Scenarios carry ordered steps with extraction
   and assertions.
2. ~~**`spec.authentication` is parsed, validated and used by nothing.**~~ **S5 added runtime
   credential references, `SecretResolver`, and broker-backed `run://session`.** Corporate identity
   and non-Kubernetes secret managers remain later pause-condition decisions.
3. ~~**A run still has one plan with one engine.**~~ **Fixed in S9.** A run compiles one plan per
   workload, shards are scoped by workload, and analysis merges sample sets across engines. What
   remains for a hybrid is *coordination* — `startWhen` resolving, and the browser-session budget —
   which is **S11**.

### The caveat to carry forward

2h changed the JMeter runner image's entrypoint (it now fetches dataset partitions), **S7 changed it
again** (a rolling progress publisher, plus a Python reducer added to the image), and none of it has
been run on a cluster. S6 added a separate Playwright runner image contract and S7 added a Node
reducer to it; that image has still never been built or run on kind. The local-JMeter path is
verified with real load — including S3's journeys and S7's live view. Before trusting either
Kubernetes runner path, re-run `docs/operations/local-kubernetes-run.md`, whose new §7 covers the
live-monitoring checks including the two-shard generator kill.

### Environment caveat, now lifted

Earlier sessions could not run the full suite here (Mockito inline self-attach, sockets refusing to
bind). S7 ran `mvn clean verify` end to end with Docker running and got 1,058 green tests with
nothing skipped. Treat a recurrence as a new observation, not as the expected state.

### Datasets, in one paragraph

`POST /api/v2/datasets?team=…&name=…` with the CSV as the body. It returns a
`dataset://{name}/{version}` uri; put that in `spec.datasets[].source.uri` along with the columns
the test expects, and bind to a column with `${column}` in a journey's path, query, headers or body.
Versions are allocated, never chosen, and never overwritten. A column named as a credential is
refused and nothing is stored. `docs/api/v2-examples.md` has the whole flow.

### What is already done — do not rebuild

Increments 0 through 2h, then S1–S6. The canonical specification, durable run orchestration, the
JMeter engine behind the SPI, the Kubernetes provider proven on a real cluster, the S3 artifact
store, the runner image, report and artifact download, synthetic-run marking, **four source
importers**, dataset binding, partitioning, upload, storage and delivery — and then **the studio**
(shell, sources, refine, load, objectives, review), **a report a non-expert can read**, **run
progress**, **multi-step journeys with reviewed correlation rules**, **a live Validator before
Save**, **S5 runtime credentials, broker-backed sessions and cleanup**, and **S6a broker-path
production-readiness docs**, plus **S6 browser-only Playwright/Core Web Vitals support**.
`HANDOFF.md` §3–§3v is the account of each.

## 3. Rules that do not bend

- **Stop after the increment you were asked for.** Produce the CLAUDE.md §30 completion report.
- **Finish every increment the same way**, before anything else is started. **All four documents,
  every time** — the list is four because it was three when `PLAN.md` did not exist, and updating
  only the old three is a mistake that has already been made once:
  1. **`HANDOFF.md`** — the status table, the test counts, and a new `§3x` section saying what was
     built, what was decided and *why*, and what was deliberately left undone.
  2. **`PLAN.md`** — the status line under the title, and the increment's row in the §11 sequence
     table. It is the plan of record; a plan that still describes work as pending after it shipped is
     the document most likely to make somebody rebuild it.
  3. **`NEXT-SESSION.md` §2** and **`ROADMAP.md`**, so the next person's first move is obvious and
     the inventory of what exists is true.
  4. **Commit**, code and documents together, with a message in this repo's style: an imperative
     sentence naming the outcome a consumer gets, not the files touched. `git log` for examples —
     *"Import a cURL command into draft scenarios"*, *"Let a specification express a whole request"*,
     *"Prove the Kubernetes path on a real cluster, and fix what that found"*. The body carries the
     detail: what changed, what was decided, what is verified, what is not.

  A commit whose documents still describe the previous state is how a fresh context starts by
  rebuilding something that already exists.

  **The check that catches a miss**, before committing — the four must agree on all three:

  ```bash
  grep -ohE "9[0-9]{2} (Java )?tests|1[0-9]{2} UI tests" HANDOFF.md NEXT-SESSION.md ROADMAP.md PLAN.md | sort -u
  grep -n "S7\|live monitoring" HANDOFF.md PLAN.md                       # the same next increment is outstanding
  grep -n "next" NEXT-SESSION.md ROADMAP.md | grep -i "increment\|S[0-9]"   # the same next move
  ```
- Do not push, tag, merge or publish without explicit authorization.
- **V1 still works and still must, for now.** Its removal is authorized but deferred until after the
  studio build phases. S5 removed the credentials/session blocker, but V1 removal still needs an
  explicit increment and audit of development identity, team sync and target resolution. Until then
  `/api/v1/**` and the **Legacy (v1)** tab are the check.
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

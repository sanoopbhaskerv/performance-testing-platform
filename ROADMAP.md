# Roadmap

What is left, and what it is worth. `HANDOFF.md` says where things stand; this says where they could
go. Nothing here is committed to — it exists so that "not done" is a list somebody chose from rather
than a surprise.

**Read the recommendation in §0 before picking anything up.**

---

## 0. Where to stop

**The foundation is complete.** CLAUDE.md §32's fifteen conditions are met, with one partial
(team authorization is enforced, but against a development identity provider — corporate OIDC is
Increment 4 and needs a decision this project has not been given).

There is exactly **one loose thread**, and it is the only thing that would mislead somebody:

> **A dataset can be declared, bound to and validated, but not delivered** (Increment 2g, `HANDOFF.md`
> §3l). A specification can reference `${orderId}`, pass validation, and then find no data at run
> time, because uploading and storing the file is Increment 2h.

**Recommendation: do 2h, then stop.** It is one increment, it closes Increment 2 completely, and it
turns the last half-built thing in the tree into a whole one. After that the platform does what it
says on every path a consumer can reach, and Increment 3 (Playwright) is a clean, large chapter to
begin — or not — with fresh eyes.

**If you would rather stop now:** that is defensible. Datasets are loudly flagged as undelivered in
`HANDOFF.md` §3l, in `NEXT-SESSION.md` §2 and in the validator's own findings. Nothing silently
lies. But move 2h to the top of this file so the next person meets it first.

---

## 1. Increment 2h — dataset storage and delivery

**The one recommended piece of work.** Everything else below is optional.

A dataset is declared and partitioned but never arrives. Four steps, in order:

1. **Decide the port.** `ArtifactStore` is run-scoped to its foundations — `ArtifactDescriptor`
   *requires* a `TestRunId` — and a dataset outlives any single run. Either widen it with
   dataset-scoped methods or add a sibling port. `teams/{teamId}/datasets/{name}/{version}` keeps
   the key layout as the authorization boundary. **Everything else follows from this decision.**
2. **Upload.** `POST /api/v2/datasets`: team-scoped, size-capped, header row required. Run
   `SensitiveFieldDetector.scanNames` over the header — a column named `password` is exactly what
   that class exists to catch, and a CSV of real credentials is now the likeliest way one enters the
   platform, since every importer refuses them.
3. **Slice at preparation.** `RunWorkloadService.prepareShard` already stores the rendered JMX per
   shard; have it call `DatasetPartitioner.partition` and store each shard's slice beside it as
   `JmeterWorkloadRenderer.partitionFileName(dataset)`. **Compare the file's header against the
   declared `columns` and fail the run on a mismatch** — that comparison is what makes the
   declaration a contract rather than a second source of truth.
4. **Deliver.** The local-jmeter provider writes it next to the JMX. The Kubernetes provider passes
   the object key in the Job manifest and the runner image fetches it — a runner-image change, so it
   needs another kind run (`docs/operations/local-kubernetes-run.md`).

The control plane slices rather than the runner taking every Nth row: testable without an image
rebuild, keeps the runner dumb, and makes each partition an auditable stored artifact. The cost is
that the control plane reads the whole file, so cap the size and say so.

---

## 2. Increment 2, remaining

Worth doing, in this order, none urgent.

| Item | Why it matters | Size |
|---|---|---|
| **Dry-run preflight** | CLAUDE.md §10 lists it as a validation layer. One iteration of each journey against the real target before committing a slot, so a wrong path or a missing credential fails in seconds instead of after a twenty-minute ramp. | Medium |
| **Baseline comparisons** | A single run's p95 means little; the question is always "worse than last week?". Needs a stored baseline per definition and a comparison in the report. | Medium |
| **Jenkins shared library** | CLAUDE.md §20 wants CI to use a scoped service identity, not a person's key. Blocked on Increment 4's identity work to be done properly; a thin version using an API key is possible sooner. | Small–Medium |
| **Manual correlation and extraction** | Pull a value out of one response and use it in the next. The specification cannot express it, so any journey needing a session token is currently out of reach. **This is the largest functional gap left in protocol testing.** | Large |

---

## 3. Increment 3 — Playwright and hybrid runs

A clean, large chapter. CLAUDE.md §21 has the full contract. In short: import an existing Playwright
test by repository, immutable commit SHA and path — never a moving branch; run it in a pinned,
restricted runner image; normalise browser results into the same model as protocol results; then
coordinate a small browser cohort during JMeter steady state. Default browser concurrency 5, policy
maximum 20 — **not** 1,000, and never equated with protocol virtual users.

The engine SPI was built for this and has not been exercised by a second engine, so expect the first
real test of whether the abstraction holds.

---

## 4. Increment 4 — enterprise integration

Every item is a **pause condition** (CLAUDE.md §31): corporate OIDC, a secret manager, a production
S3 bucket, Dynatrace tokens. None can be chosen by whoever is writing the code.

What exists today: the authorization domain, the Spring Security seams, a development identity
provider that maps an API key to its owning team and **refuses to start under a production profile**.
What does not: any real identity provider, and the `SecretResolver` implementations.

Also here: defect **D7** — unauthenticated calls return 403 rather than 401, inherited from V1's
shared filter chain and pinned by `RunLifecycleEndToEndTest.v2RequiresAuthentication` so it stays
visible.

---

## 5. Increment 5 — assisted authoring

Recorder, AI-assisted draft generation, diff and review, historical recommendations. CLAUDE.md §28
is explicit that this comes **only after governance**, and that no unreviewed AI-generated material
test may run. The importers already establish the shape: produce a draft plus findings, and never let
it become executable without a person acting.

---

## 6. Smaller open items

Each is real, none blocks anything.

- **Sharding is untested.** Everything so far is one shard. `DatasetPartitioner` exists now, so the
  data half is solved; what remains is 2h wiring plus calibration
  (`docs/operations/runner-calibration.md`) before the count rises.
- **`ConcurrencyThreadGroup` is in the runner image but unused.** The plan generator still emits
  stepwise thread groups, so concurrency steps at stage boundaries rather than gliding. A change to
  `JmeterWorkloadRenderer`, and it changes what the numbers mean at transitions.
- **Not run on EKS.** IRSA is unexercised; the kind run used a credentials Secret.
- **`platform-ui` has no test runner.** No vitest, no jest. `scenarioYaml.ts` (YAML escaping,
  indentation detection, block extent) is real logic pinned only by a browser walkthrough. Adding
  vitest is a §31 pause condition — **ask first**.
- **`/actuator/health` returns 503 locally** (defect D6): the mail health indicator cannot reach
  SMTP. Readiness should distinguish essential dependencies from optional enrichment (CLAUDE.md §26).
- **UI lint reports 10 errors, 3 warnings**, all pre-existing in `App.tsx` and the pre-1D parts of
  `PerformanceConsole.tsx`. Deliberately not fixed as a side effect of other work.
- **`platform-ui/src/App.css` is imported by nothing.** Rules added there silently do nothing.

---

## 7. Things deliberately not on this list

- **k6.** Not approved. CLAUDE.md §3.
- **Autonomous browser-script generation.** MVP imports an existing Playwright test; generation is
  §5 material and needs governance first.
- **Heap and thread dump collection in the run path.** CLAUDE.md §22 puts diagnostics behind
  separate, explicitly authorised providers. V1 coupled them to the run lifecycle; that coupling is
  not coming back.
- **Microservice decomposition.** The architecture asks for a modular monolith and one deployable
  control plane. §4.

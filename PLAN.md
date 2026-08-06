# Plan — the Performance Studio

**What this is.** A plan to make this platform a **studio**: one UI where a person configures an
entire performance test — API load or web experience — without writing code, and runs it at a scale
no desktop tool can reach.

**The goal, stated plainly:** *someone with no performance testing experience should be able to use
this tool and trust what it tells them.* That is a harder bar than "has a UI", and it is the bar
every decision below is measured against. It rules out a studio that produces a number a novice
cannot interpret, and it makes **live monitoring** (§4.1) and **plain-language reporting** (§4.2)
core features rather than polish.

**Read first:** `HANDOFF.md` for state, `CLAUDE.md` for the contract, `ROADMAP.md` for what remains.

**Status: S1 through S11 are built**, and **S10j made browser journeys executable** — until it,
nothing in the platform could actually run a Playwright workload. See §11 for the per-increment state
and `HANDOFF.md`
§3n–§3ad for what each delivered. The S10 recorder security review is complete in
[`docs/architecture/recorder-security-review.md`](docs/architecture/recorder-security-review.md).
S10a and S10b added the security foundation with domain-level recorder allowlist, address and route
guard policy, plus the recorder pod manifest and egress policy renderer. S10c added durable audit
events, capture sanitization and a safe pending console entry point. **S10d built the guarded CDP
bridge**: a session now attaches a real browser behind a `RecorderBrowser` port, streams it to the
console and accepts a closed vocabulary of ten interactions. **S10e turned a recording into a draft
test**: the captures are rendered as a HAR and read by the ordinary HAR importer, so a recording
arrives in the studio with journeys, weights and correlation suggestions. **S10h closed the
recorder's own gap**: the Validator now runs against the recording and reports a verdict on every
accepted correlation, so a rule that was applied to nothing fails instead of passing on a replayed
capture (`HANDOFF.md` §3ak). **S10i finished S10**: a git-pinned Playwright source now reaches the
runner and cannot name a repository this deployment has not allowlisted (§3al). S11 web performance
under load is built.

---

## 0. What changed

Earlier drafts of this plan sequenced backend increments and put the console last. That is backwards
for what is actually wanted: **the studio is the product**, and the engine work exists to make its
screens mean something. Every increment below therefore ships a backend capability *and* the studio
surface that exposes it. Nothing lands API-only.

Two capabilities, equally first-class:

1. **Load test one or many APIs at a time.**
2. **Web performance testing** — pick an application URL, record a journey, upload a HAR, or bring an
   existing Playwright script.

### On CLAUDE.md

Two clauses move, and both are the user's call under CLAUDE.md §2's precedence (a current explicit
instruction outranks this file):

- **§24** — "Do not add browser recording or AI generation UI in the foundation phase." The foundation
  is complete (`ROADMAP.md` §0), so this no longer binds.
- **§28** — the recorder sits in Increment 5, after governance. It moves forward to §11's S10, after release 1. Note
  that a recorder is **not** what §21 forbids: §21 prohibits inferring a script from *intent*, and
  §21's own later-phase list names "visual recording" as a legitimate ingredient. A recorder produces
  a transcript of what a person actually did. There is nothing inferred to review.

**k6 is still prohibited** (CLAUDE.md §3, §31). k6 Studio appears below purely as a **UX reference** —
it is the most complete studio model published, and its screen decomposition is worth learning from.
No k6 code, dependency or engine enters this platform.

### On V1

**The user has authorized V1's removal** — the CLAUDE.md §31 pause on deleting V1 functionality is
cleared — and has asked for it **after** the studio build phases rather than during them. It splits
in two, and only the first half is cheap:

- **The V1 UI** (`App.tsx`'s legacy screens and the tab switcher) can go whenever. It makes the
  studio simpler: the `.v2` scoping prefix becomes unnecessary, the light theme can follow
  `prefers-color-scheme` instead of being an opt-in, and the last 3 lint errors go with it.
- **The V1 API and engine still need a focused removal audit.** S5 removed the credentials/session
  blocker, but development identity remains local-only and V1 endpoints must stay operational until
  a removal increment explicitly verifies identity, target resolution and team sync replacements.

---

## 1. What a studio is, from the state of the art

Two products define this category, and they agree on the shape.

**[Gatling Studio](https://gatling.io/blog/gatling-studio)** — the tool in the video. Desktop app,
four steps: **Record → Refine → Generate → Export**. It launches a controlled browser and captures
requests, responses, headers, cookies, timings and pauses; or it imports a HAR. It then strips static
assets, analytics, trackers, fonts and ads so only the functional path remains, groups requests, and
emits a Gatling Java/Maven project. Notably, [it does not yet handle dynamic token
correlation](https://gatling.io/blog/gatling-studio) — authenticated flows still need manual work.

**[Grafana k6 Studio](https://grafana.com/docs/k6-studio/)** — three components, **Recorder → Test
Generator → Validator**, and a rule model that is the best-articulated in the field:

| Rule | What it does |
|---|---|
| **Verification** | On by default. Adds a check after each request that the status matches the recording. |
| **Correlation** | An **Extractor** pulls a dynamic value from a response; a **Replacer** injects it into later requests. |
| **Parameterization** | Replaces hardcoded values with data — variables, CSV/JSON files, or a snippet. |
| **Custom code** | Arbitrary snippet before or after matching requests. |

Extraction offers three selectors — **Begin-End** (between delimiters), **JSON** (dot notation) and
**Regex** (capturing group). A Test options panel carries **load profile**, **thresholds**, **think
time** and load zones. Test data holds variables and CSV/JSON files. The **Validator** runs a single
iteration and lets you inspect every request and response before committing to a real run.

### What this platform must not copy

Both are **desktop apps that generate code you then run somewhere else**. This platform is a
governed, server-side control plane. That difference is the advantage, not a limitation:

| | Desktop studio | This platform |
|---|---|---|
| Scale | your laptop | Kubernetes, hundreds–thousands of VUs |
| Output | a script in your IDE | an immutable, digested, approved test version |
| Governance | none | team authorization, quotas, target allowlist, approvals |
| Correlation | Gatling Studio: not yet | designed in from the start (§7) |
| Result | a report on your machine | durable evidence, comparable across runs |

The studio's *screens* are worth copying. Its *architecture* is not.

---

## 2. Where this platform already stands

Measured against the k6 Studio model. This is why the pivot is a reordering, not a restart.

| Studio capability | Here today |
|---|---|
| HAR import | ✅ built — §3i, and **imports ordered journeys** since S3 — §3q |
| Noise filtering (assets, analytics, third-party, failed requests) | ✅ built — dominant-host filter, asset filter, failure filter |
| Credential stripping on import | ✅ built, and stronger than either reference tool |
| Weights from observed frequency | ✅ built — largest-remainder apportionment |
| OpenAPI / Postman / cURL import | ✅ built — §3h, §3j, §3g |
| Parameterization from CSV | ✅ built — `${column}` binding, per-shard partitioning, upload |
| Load profile (staged concurrency) | ✅ built |
| Thresholds | ✅ built — objectives with verdicts |
| Generated-plan preview | ✅ built |
| Durable runs, cancel, quotas, authorization | ✅ built — and neither reference tool has any of it |
| **Correlation rules** | ✅ **built in S3** — §3p, §3r. Offered as reviewed suggestions with evidence, never applied silently |
| **Verification rules (assertions)** | ✅ built in S3 — expressible in a document and editable in the Rules screen |
| **Think time** | ✅ built in S3 — expressible in a document and editable in the Rules screen |
| **Studio shell** | ✅ built in S1 — §3n |
| **A report a non-expert can read** | ✅ built in S2 — §3o |
| **Validator / single-iteration dry run** | ✅ built in S4 — live protocol validation before Save |
| **Arrival-rate load profile** | ❌ S8. Only staged concurrency today |
| **Recorder** | ✅ built in S10a–S10e — `HANDOFF.md` §3ag, §3ah. Records, streams, and becomes a draft test |
| **Browser engine, Core Web Vitals** | ✅ built in S6 — `HANDOFF.md` §3v |

The table above was the state when this plan was written. Most of the gaps are closed; what
remains is marked with the increment that owns it.

### Three structural facts that shape everything below

Found by reading the code, not the docs:

1. ~~**A journey is one request.**~~ **Fixed in S3** (`HANDOFF.md` §3p): `ScenarioSpec` carries
   ordered steps, values are extracted and carried forward, and a failed extraction fails the sample
   rather than reporting good latency.
2. ~~**`spec.authentication` is parsed, validated and used by nothing.**~~ S5 added runtime
   credential handling, `SecretResolver`, broker-backed `run://session`, mounted session partitions
   and production cleanup.
3. ~~**A run still has one plan with one engine.**~~
   [`EngineDispatchingPlanCompiler.primaryEngine()`](platform-orchestration/src/main/java/com/perfplatform/orchestration/plan/EngineDispatchingPlanCompiler.java:62)
   first made this failure explicit; S9 replaced it with one plan per workload, and S11 added
   delayed browser start coordination for hybrid runs.

---

## 3. The studio: three test types

A person starting a test picks one of three things. Each is a different question.

### Type 1 — API load test

*"Can these endpoints handle the load?"*

One or many APIs in a single test. Sources: OpenAPI, Postman, cURL, HAR, or typed by hand. Multiple
targets in one test, so a journey spanning two services is one test rather than two.

### Type 2 — Web experience test

*"Is the page fast?"*

Pick an application URL, record a journey or bring a Playwright script, run a handful of real
browsers, measure **Core Web Vitals**. No load. Fast, cheap, and the right first test — it catches a
front-end regression in ninety seconds without booking a load slot. Neither Gatling Studio nor k6
Studio offers this; it is a distinct product in most organisations (Lighthouse CI), and folding it in
means one tool and one history.

### Type 3 — Web experience under load ★

*"Is the page still fast when the backend is at 500 users?"*

Types 1 and 2 in one run, correlated in time. **This is the flagship**, and it is the question
nothing else in the category answers: a Lighthouse score is measured against an idle server, and a
load test reports backend latency while saying nothing about what a user sees.

The economics are what make it possible. One Chromium session costs ~0.5–1 GB and a real fraction of
a core; a protocol virtual user costs about a hundredth of that. So the browsers are the
**measuring instrument** (5, up to 20) and JMeter is the **load source** (hundreds to thousands),
both addressing the same front door. The server cannot tell them apart — it sees one request stream.

---

## 4. The studio shell

Ten screens. The left column is the k6 Studio / Gatling Studio decomposition; the right is what makes
it this platform's.

| Screen | What it does | New? |
|---|---|---|
| **1. Start** | Pick a test type. Names the question, not the engine. | new |
| **2. Sources** | Record · Upload HAR · OpenAPI/Postman/cURL · Playwright repo · Type it | half built |
| **3. Refine** | Every captured request in a table. Keep/drop, group into journeys, see what was filtered and **why**. | filtering logic built, UI new |
| **4. Rules** | Correlation · Verification · Parameterization · Think time | new (§7) |
| **5. Data** | Datasets, variables, credentials-as-references | upload built, UI new |
| **6. Load** | Concurrent users **or** requests/second · stages · journey mix | half built |
| **7. Objectives** | Latency, error rate, throughput, **Core Web Vitals** | half built |
| **8. Validate** | One iteration against the real target, every request and response inspectable | new (§8) |
| **9. Status** | State, timeline, elapsed, current stage, progress bar, cancel — plus **S7's live throughput, latency, errors, per-journey breakdown, per-generator health and live objectives** | §4.1, **built** |
| **10. Report** | Verdicts in plain language, per-operation latency, web vitals, **comparison to baseline** | §4.2, half built |

**Screens 3, 4 and 8 are what make it a studio.** Without them it is a form over a YAML file.
**Screens 9 and 10 are what make it usable by a novice.** Without them it is a studio that produces a
number nobody can act on.

---

## 4.1 Live monitoring

Today a run shows a state — `Queued → Starting → Running → Analyzing → Passed` — and nothing else
until it finishes. For someone experienced that is tolerable. For someone on their first test it is
twenty minutes of a spinner followed by a verdict they have no context for, and no way to tell a
climbing error rate from a healthy run until it is over.

### What the runner has to do differently

Results are uploaded **at the end** today: the JMeter runner writes `results.jtl` and uploads it when
the process exits. Live monitoring needs numbers *during*.

**The runner writes rolling progress snapshots to object storage; the control plane reads them.**

Not a callback to the control plane, and this is the constraint worth preserving rather than
working around: a runner has **no database access and no control-plane credentials, by design** — a
provider that could write run state could mark its own failures as successes (`HANDOFF.md` §3e). It
*does* already hold object-storage credentials. So every N seconds the runner reduces what it has
produced so far into a small `progress.json` — counts, error count, latency percentiles, per-operation
breakdown, current stage — and PUTs it at a well-known key under its own shard prefix. The reconciler
reads and merges those snapshots across shards on each pass.

Consequences, all good:

- **Engine-neutral.** The Playwright runner does exactly the same thing with journey durations and
  web vitals. Nothing in the contract is JMeter-specific.
- **No new JMeter plugin.** A `BackendListener` would work, but it would mean an InfluxDB or Graphite
  endpoint reachable from every runner cell, and CLAUDE.md §24 already says backend listeners must not
  surface in self-service screens. Tailing the JTL the runner is already writing costs nothing new.
- **The invariant holds.** Runners still cannot write run state.
- **Snapshots are evidence too.** They persist as artifacts, so a run that dies mid-flight still has
  a record of what it was doing — which is exactly when someone most wants to know.

Cadence: 5 seconds from the runner, merged on the existing reconciliation tick. The console receives
merged snapshots over the SSE channel that already exists for status (CLAUDE.md §19), falling back to
polling.

### What the page shows

- **Where we are** — elapsed, remaining, current stage, active virtual users against planned. A
  progress bar over the stage profile, so "we are 6 minutes into a 20-minute steady state" is visible
  at a glance rather than inferred.
- **Throughput** over time, requests per second.
- **Response time** over time — p50, p95, p99 as bands, not a single line. A novice reading one line
  learns nothing about the tail, and the tail is the point.
- **Error rate** over time, with the **actual recent errors listed**: status, path, message. "4.2%
  failing" is a number; "503 from `/api/orders`" is something to act on.
- **Per-journey breakdown** — which journey is slow, not just that something is.
- **Objectives, tracked live.** Each objective shows its current measured value against its threshold,
  and whether it is currently met. This is the single most useful thing on the page: someone watching
  their p95 cross 750 ms at minute four does not need to wait sixteen more minutes to learn the test
  failed.
- **Browser cohort**, for test types 2 and 3 — current LCP/INP/CLS, journey pass rate, last
  screenshot.
- **Shard health** — heartbeat age per shard, so a dead generator is visible rather than showing as a
  quiet drop in load.
- **Cancel**, prominent. Someone who realises they pointed a thousand users at the wrong environment
  must be one click from stopping it, not hunting for it.

### The interpretation layer — the part that serves a novice

Charts assume you know what a bad one looks like. So alongside them, in plain language:

> **Error rate is climbing.** 4.2% of requests are failing, up from 0.1% five minutes ago. Most are
> `503` from `/api/orders`. This usually means the service is refusing work because it is saturated.

> **Response times are stable** as load increased from 200 to 400 users. p95 is 210 ms, well inside
> your 750 ms objective.

These are **rules over observed data, not a model's opinion** — a small, auditable set of patterns
(error rate rising, latency knee, throughput plateau despite rising users, shard heartbeat lost,
objective crossed). Each names what it observed, so it can be checked. A tool that tells a novice
something confident and wrong is worse than one that shows only the chart.

---

## 4.2 The report

The existing report has verdicts, a per-operation latency table, a stage profile, journey mix and
artifact downloads (`HANDOFF.md` §3b). It is a good expert report. Three things make it a novice's.

**1. The answer first, in a sentence.** Not a verdict chip above a table — a sentence:

> **This test did not pass.** At 400 concurrent users, 95% of requests completed within 1.9 seconds,
> against your objective of 750 ms. Errors stayed low (0.3%). The slowest journey was *place-order*,
> where p95 was 3.4 seconds.

**2. What each number means, on demand.** Every metric carries a plain explanation available inline —
what p95 is and why it is used instead of an average, what error rate counts, why throughput can fall
while load rises. Written once, shown where the number is, never in a separate glossary nobody opens.

**3. Comparison, because a single number means nothing.** "p95 was 640 ms" is not actionable. "p95
was 640 ms, up from 410 ms last week, and the regression is concentrated in *search-client*" is. A
stored baseline per definition, and every report diffed against it — the largest single upgrade for a
non-expert, and it is already on `ROADMAP.md` §2. It matters more for web vitals than for anything
else, because vitals are noisy and only a trend is trustworthy.

Also: **what was not measured must be as visible as what was.** The platform is already unusually
disciplined here — indeterminate objectives, synthetic-run marking, missing-shard refusals — and that
discipline is precisely what protects a novice, who has no instinct for when a result is too good.

---

## 4.3 Design rules for a first-time user

Applied to every screen, and checkable in review:

1. **Consumer language, never engine language.** "How many people at once?", not thread groups. "How
   long should the load hold?", not scheduler duration. CLAUDE.md §24 already requires this; the
   studio makes it load-bearing.
2. **Sensible defaults for everything**, so a working test needs a URL and one number. Every default
   is visible and changeable — a hidden default is a surprise later.
3. **Explain the ceiling, do not just enforce it.** "Maximum 20 browsers, because each one is a real
   Chrome using about a gigabyte" teaches something. A greyed-out field teaches nothing.
4. **Refuse early and say why.** The Validator (§8) exists for this. A studio that lets someone
   discover at minute nineteen that a path was wrong has failed them.
5. **Never present a number that cannot be trusted as if it can.** Already true in the engine; the
   studio must not undo it in the presentation.
6. **Every error message names the fix.** The importers already do this — findings carry remediation.
   Extend it everywhere.

---

## 4.4 Design tokens

**The studio must be re-brandable without touching a component.** A deploying team supplies their own
colours and the whole UI follows.

Today `platform-ui/src/v2/console.css` holds roughly thirty hardcoded hex values in a dark-only
palette. Re-branding that means finding and editing every one, and missing some.

### Two layers, and the distinction is the point

```css
:root {
  /* Primitives — the brand supplies these, and only these. */
  --pf-brand-600: #4f46e5;
  --pf-neutral-900: #0f172a;
  …

  /* Semantics — components reference only these, never a primitive. */
  --pf-color-action: var(--pf-brand-600);
  --pf-color-surface: var(--pf-neutral-900);
  --pf-space-3: 0.75rem;
  --pf-radius-md: 10px;
  --pf-font-size-sm: 0.85rem;
}
```

A component that reaches past the semantic layer to a primitive is the bug this structure exists to
prevent — it is how a re-brand ends up 90% complete. Enforceable: a lint rule, or a test asserting no
literal colour appears outside the token file.

Tokens cover colour, spacing, radius, typography, border and elevation. Light and dark both defined
from the start; retrofitting a second theme costs far more than declaring one.

### Verdict colours are not the brand's to break

`Passed`, `Breached` and `Indeterminate` carry meaning. A brand palette that renders a breach in a
reassuring green would make the studio lie in its own house style, and this is a tool whose entire
output is a verdict.

So verdict tokens are **semantic and separately overridable**, with a **contrast floor** the theme
cannot go below — and, per WCAG, **never conveyed by colour alone**: every verdict carries an icon
and a word. Someone with colour-vision deficiency reads the same result as everyone else, which is
the point of the whole plan.

### Delivery

One `tokens.css`, imported before everything. A deployment overrides by supplying a file of primitive
values — no rebuild of components, no fork. `platform-ui/src/App.css` is imported by nothing
(`ROADMAP.md` §6); that dead file gets removed rather than becoming a second place to look.

---

## 5. Where journeys come from

**A HAR cannot produce a Playwright script, and this is the point to be precise about.** A HAR
records HTTP traffic. It knows `POST /api/search` happened; it does not know that a person typed
"Smith" into `#search` and clicked a button. That mapping is not recoverable, and a tool claiming to
derive a browser script from a capture is guessing at selectors.

So the two halves of the studio need different sources, and release 1 ships wizards rather than a
recorder for both.

### Protocol journeys — guided HAR capture

The console walks someone through the DevTools steps with screenshots: open the app, F12, Network,
tick *Preserve log*, do the journey, right-click → *Save all as HAR*, drop it here. The importer
already filters static assets, analytics, third-party hosts and failed requests, strips credentials
and derives weights from observed frequency (`HANDOFF.md` §3i). Nothing to install, nothing to
approve.

### Browser journeys — four sources, in order of how little the user must know

| | Source | Needs | Covers |
|---|---|---|---|
| **1** | **A list of URLs** | nothing but the URLs | Navigate, wait, capture LCP/CLS/TTFB. Most of what Lighthouse is used for, and the true novice path. No INP — nothing interacts. |
| **2** | **Guided `npx playwright codegen`** | Node.js on their machine | Playwright's own recorder. It opens a browser, they do the journey, it writes the script, they paste it in. |
| **3** | **Upload or paste an existing script** | an existing `.spec.ts` | The teams that are already ahead. |
| **4** | **Authored steps in the studio** | knowing CSS selectors | The fallback for interactive journeys, not the front door. |

**Option 2 is the recorder, for free** — it just runs on the user's machine instead of in a pod. It is
the symmetric partner to the HAR wizard: *run this, do your journey, paste the result*. Release 1
ships 1, 2 and 3; option 4 waits for demand.

**Upload the script rather than clone the repository.** Immutable by content digest, no git
credentials, and therefore no §31 pause. `SourceSpec.GitSource` and the validator rules that already
refuse a mutable ref stay in place for release 2's git-pinned import.

### What this honestly leaves undone

**A non-expert authoring an interactive browser journey without leaving the console.** Options 1 and 3
need no expertise, 2 needs Node installed, 4 needs selectors. Only a server-side recording browser
closes that gap — which is why it stays on the roadmap (S10) rather than being cut.

---

## 5.1 The recorder (S10, deferred)

The single largest new capability, and the one design decision worth arguing.

### Why the desktop answer does not transfer

Gatling Studio and k6 Studio launch a browser on your machine. A server-side console cannot. The
options:

| Approach | Verdict |
|---|---|
| **DevTools HAR export** — open DevTools, Network, Export HAR, upload | **Available today.** Zero build; the importer already handles it. Ship as the fallback, always. |
| **Browser extension** | Needs corporate extension approval and per-machine install; a moving target across Chrome/Edge versions. |
| **Proxy with a CA certificate** | Certificate installs on every machine — the friction both reference tools advertise *not* having. |
| **Server-side recording browser** ★ | The right answer. Recommended. |

### The recommendation: a recording browser in a pod, streamed to the user

The platform starts a short-lived Playwright browser in a pod and streams it to the console over CDP
screencast. The user drives it — clicks, types, navigates — and the platform observes everything from
inside the browser.

Five things fall out of this that no other option gives:

1. **Nothing to install.** No extension, no certificate, no proxy, no desktop app. It is a URL.
2. **The capture is complete.** CDP sees requests, responses, timings, DOM events and navigation —
   more than a HAR export, and correlation candidates can be detected from response *bodies* the
   moment they arrive.
3. **One session yields both artefacts.** The same recording produces the protocol journey (for
   JMeter) *and* a Playwright script (via codegen). Record once, get both halves of a Type 3 test.
   This is the unification that makes the flagship test type cheap to author.
4. **It solves authoring through login.** The person signs in **interactively, themselves**, inside
   the recording browser. No credential is typed into the platform and no IdP is scripted. The
   resulting session is authoring evidence only; material runs still use `run://session` or another
   approved test-account/session provider (§9).
5. **It runs where the runners run.** Same network position, so it reaches internal applications a
   laptop may not.

### The control that must exist

A browser inside the platform's network that a user drives is **an open proxy into the internal
network** unless it is constrained. Non-negotiable:

- The recording browser is **locked to registered targets** and their declared asset domains.
  Navigation elsewhere is blocked and reported, not silently followed.
- Session-lifetime capped, idle-timeout enforced, pod destroyed after.
- Recorded content is sanitized on capture by the existing `SensitiveFieldDetector` and
  `SecretValueDetector` before anything is stored — the same treatment a HAR upload gets.
- Non-root, all capabilities dropped, egress-restricted `NetworkPolicy`.

This needed a security review before it was built (§12, R2). That review is now recorded in
[`docs/architecture/recorder-security-review.md`](docs/architecture/recorder-security-review.md).
The core decision from it: the recorder is an authoring tool, not a runtime credential source. A
human SSO session captured during recording must not be promoted to material test credentials; real
runs still use `run://session` or another approved test-account/session provider.

---

## 6. Web performance: the metrics, and one honest caveat

The 2026 standard is **Core Web Vitals**, measured at the 75th percentile:

| Metric | Threshold | Measures |
|---|---|---|
| **LCP** — Largest Contentful Paint | < 2.5 s | how fast the main content appears |
| **INP** — Interaction to Next Paint | < 200 ms | responsiveness to real interaction |
| **CLS** — Cumulative Layout Shift | < 0.1 | visual stability |

Supporting, and worth reporting: TTFB, FCP, **Total Blocking Time**, and page weight.

### The caveat that has to be in the product, not just the docs

**[INP is a field metric and cannot be measured by a lab tool](https://www.corewebvitals.io/core-web-vitals)**
— it needs real user input. Lighthouse uses **Total Blocking Time** as its lab proxy, and it is a good
correlate but it is not INP.

Here, this platform is unusual: its browsers *do* interact — a recorded journey clicks and types.
So it can measure INP **for the interactions in the journey**, which is a genuine measurement and not
a proxy. What it cannot claim is the CrUX field figure that Google ranks on. The report must say
which it is showing. A studio that quietly relabels TBT as INP teaches people something false about
their own application.

### Measurement discipline

Browser measurements are noisy, and the accepted practice is
[consistent hardware plus the median of 3–5 runs](https://testdino.com/blog/playwright-performance-testing).
So:

- **Median of N iterations**, N configurable, default 5. Reported with the spread, not as a lone
  number.
- **A pinned runner pod size** for browser workloads. A vitals figure from a throttled pod measures
  the pod. `docs/operations/runner-calibration.md` already makes this argument for load generators;
  it applies twice as hard here.
- **Chromium only** for vitals. LCP/CLS via CDP are Chromium-only. Firefox and WebKit remain available
  for functional journeys, with vitals reported as unavailable rather than approximated.

---

## 7. The rule engine

Screen 4, and the heart of the studio. The k6 Studio rule model is the right one; this adapts it to a
declarative specification rather than to generated JavaScript.

### Correlation — the gap that blocks everything

`ScenarioSpec` gains ordered `steps`, and a step gains extractions:

```yaml
scenarios:
  - name: place-order
    weight: 60
    steps:
      - name: sign-in
        method: POST
        path: /auth/login
        extract:
          - name: session
            from: SET_COOKIE        # JSON_BODY | REGEX_BODY | HEADER | SET_COOKIE | STATUS
            expression: JSESSIONID
      - name: create-order
        method: POST
        path: /api/orders
        headers: { Cookie: "${session}" }     # a reference, not a value
        expect:
          - status: 201
```

Three extractor kinds, matching the field's vocabulary: **JSON** (dot notation), **Regex** (capturing
group) and **Boundary** (between delimiters — k6 Studio's "Begin-End"). All three map to JMeter
post-processors that already exist.

**The studio proposes correlations; it never applies them silently.** After a recording or a HAR
import, the platform scans for values appearing in a response and then in a later request — session
ids, CSRF tokens, order ids — and presents each as a **suggestion with the evidence**: which response
it came from, which requests use it, what the value looked like. The user accepts or rejects. That is
the feature Gatling Studio does not yet have, and doing it as *proposals* rather than magic is what
keeps it trustworthy.

### The failure this must never produce

When an extraction does not match, JMeter substitutes its default and sends the request anyway. The
target returns 4xx in two milliseconds, and **the run reports its best latency ever for a system that
did no work.** This codebase already guards that exact shape in five places — synthetic runs, unbound
datasets, unfilled path parameters, conditional headers, mismatched dataset columns.

So every extraction renders with a **sentinel default** and an assertion that fails the sample when
the sentinel survives. Not optional and not configurable. A broken correlation appears as an error
rate, never as good latency.

### Verification, parameterization, think time

- **Verification** — on by default, as in k6 Studio: assert the status observed during recording.
  Extends to body-contains and header assertions. Without it a load test of a broken deployment
  reports beautiful numbers for a wall of 500s.
- **Parameterization** — mostly built. `${column}` binding, CSV upload, per-shard partitioning and
  header verification all exist (`HANDOFF.md` §3l, §3m). What is missing is the *UI*: select a value
  in the request table, bind it to a column.
- **Think time** — per step or per journey, fixed or random. Absent today, and its absence makes every
  test unrealistically aggressive: real users pause.

**No "custom code" rule.** k6 Studio has one because it generates JavaScript. This platform compiles a
declarative specification, which is what makes a test reviewable, diffable, digest-identified and
safe to run at 1,000 users. An arbitrary snippet would be a hole through that, and CLAUDE.md §23 is
explicit that the control plane does not execute code that arrived in a document.

---

## 8. The Validator

k6 Studio's third component, `ROADMAP.md` §2's "dry-run preflight", and the thing that makes a studio
trustworthy rather than merely pretty.

**One iteration of every journey against the real target, before a load slot is booked.** Every
request and response inspectable: status, headers, body, timing, which extractions resolved and to
what, which assertions passed.

It is the answer to the studio's central risk. A journey assembled through a UI *looks* correct — the
paths are right, the headers are there, the correlations are wired. It is only wrong at run time, and
by then it has been wrong for twenty minutes at 500 users, and the numbers it produced are worse than
useless because they look plausible.

Cheap to build on 2h's foundations: one iteration, no load, no admission slot, results shown and not
persisted as a run. Governance: it makes real requests to a real target, so it obeys the target
allowlist and is rate-limited per team.

**This should ship early — it is S4, inside release 1.** Every screen after it is more trustworthy for existing.

---

## 9. Authentication

Two paths, and the recorder makes the hard one easy.

### The value never enters the document

`Cookie`, `Authorization`, `X-Api-Key` and their family are refused **by name, whatever the value**
(`HANDOFF.md` §3g). That stays. The security reason is obvious; the practical reason is stronger:
**a session is ephemeral and a specification version is immutable.** Store one in the other and the
test works today, returns 401s next month, and reports them as excellent latency.

So a credential is a **reference**, and its value arrives at trigger time:

```yaml
authentication:
  - name: app-session
    type: session-header
    injectAs: Cookie
    secretRef: run://session      # acquired for this run. Never stored in the definition.
```

```
POST /api/v2/test-runs  { "definitionVersionId": "..." }
```

In production, S5 acquires the lease through the configured external session broker, then delivers
the runner's partition as a mounted Kubernetes Secret file. The local runtime-credential fallback can
still seed `run://session` for development, but production profiles refuse that as the primary
provider. Never persisted, never digested, never logged, never in a manifest, label or argument.

The studio surfaces this on screen 5 as "Session — acquired at run time", with local-only seed entry
where explicitly configured.

### Sessions at scale: acquire once, never per user

For a load test of an authenticated application, the naive design — each virtual user signs in — is
wrong three ways: it load-tests the identity provider, it will lock accounts or trip rate limits
(500 sign-ins in a two-minute ramp from one source is indistinguishable from credential stuffing),
and login latency contaminates every journey.

So the platform acquires a **pool** of sessions before load starts and distributes it. S5 delivers
the pool as a **run-scoped mounted Secret file** named `sessions.csv`; JMeter reads the
`session_value` column and injects it through the workload's `authRef`. It is deliberately not a
downloadable dataset artifact.

Because the contents are live credentials: download refused, redacted in every listing, deleted at
completion, cancellation or explicit TTL cleanup.

Sizing: one session per 25 virtual users, minimum 5, maximum 50 — configurable, explained in the UI.

**Expiry detection is mandatory.** A session expiring twelve minutes into a forty-minute run means
every request 401s in two milliseconds and the run reports its best numbers ever. So a `renewOn`
response never counts as a success, triggers re-acquisition, and fails the run with a session-expiry
code if re-acquisition is not possible.

**The recorder is not a material session-acquisition path.** The person may sign in interactively
during recording so the platform can observe and author the journey through the authenticated area,
but any captured cookies, tokens or `storageState` are sensitive authoring evidence, not reusable
runtime credentials. Real runs still acquire sessions through `run://session` or another approved
test-account/session provider.

---

## 10. Direct URLs and multiple targets

The studio must let someone point a test at an API without a ticket, and a web test at Apache and its
backend in one run. Today a run resolves exactly one target, from a registry.

**A specification still may not name its own host** — that is the single control stopping this
platform being a usable denial-of-service tool, and it is not being relaxed. What changes is the
friction: target registration becomes **self-service** (`POST /api/v2/targets`, plus a studio form),
checked by the existing `TargetPolicy` — which already refuses metadata endpoints, link-local,
multicast, wildcard and unresolvable hosts against the **resolved** address. Environment
classification required; production-class requires approval.

Register a host once, address every path under it. A specification then names several:

```yaml
environment:
  targets:
    - { name: web, application: proposal-ui }    # Apache
    - { name: api, application: proposal-api }
workloads:
  - { name: api-load, target: api }
```

The recording browser's allowlist is derived from the same registry, which is what keeps §5's control
honest.

---

## 11. Sequence

Each increment ships a backend capability **and** its studio screen, keeps the build green, and ends
with the CLAUDE.md §30 completion report plus updated `HANDOFF.md`, `ROADMAP.md` and
`NEXT-SESSION.md`.

### Release 1 — decided

Scope is **API load testing and web performance testing**, both usable by a non-expert. Everything
else waits. Decisions taken, with the reasoning, so they are not relitigated:

| Decision | Chosen | Why |
|---|---|---|
| **vitest** | **Yes — approved, added in S1** | `platform-ui` had no test runner at all. §31 pause cleared. 158 UI tests now. |
| **Recorder** | **Deferred.** Guided capture wizards instead (§5) | A server-side recording browser is large and needs a security review. The wizards get most of the value for almost nothing. |
| **Live monitoring** | **Deferred.** Existing status, plus elapsed/stage/progress bar | The cost is a runner-image change and re-verification on a cluster. The progress bar is derivable from data the control plane already has — no runner change. |
| **The novice report** | **Kept** | Pure UI, cheap, and most of what "usable without experience" actually means. |
| **Arrival-rate sizing** | **Deferred** | Concurrent users is the more common framing. Rate follows in release 2. |
| **Hybrid (under-load web perf)** | **Deferred** | Needs the multi-plan refactor. A browser-**only** test does not — see below. |
| **Playwright source** | **Upload or paste the script**, not a git clone | No git credentials, therefore no §31 pause, and immutable by content digest. Git-pinned import in release 2. |

**The structural saving that makes this affordable:** a browser-only web performance test is **one
workload, one engine, one plan**. It does not need S9's multi-plan refactor at all — only the hybrid
does. So web performance costs S6 alone, not S7 + S8.

| # | Increment | Studio surface | Size | Rel. 1 | State |
|---|---|---|---|---|---|
| **S1** | Studio shell, and the API path end to end | 1 Start, 2 Sources, 3 Refine, 6 Load, 7 Objectives, 9 Status, 10 Report | Large | ✅ | **done** — `HANDOFF.md` §3n |
| **S2** | The report a novice can read | 10 Report | Medium | ✅ | **done** — §3o |
| **S3** | Journeys and rules: correlation, verification, think time | 4 Rules | Large | ✅ | **done** — `HANDOFF.md` §3p, §3q, §3r |
| **S4** | The Validator | 8 Validate | Medium | ✅ | **done** — `HANDOFF.md` §3s |
| **S5** | Targets, credentials, sessions | 5 Data/Auth; self-service targets | Large | ✅ | **done** — `HANDOFF.md` §3t |
| **S6a** | Broker path production-readiness validation | Operations | Small | ✅ | **done** — `HANDOFF.md` §3u |
| **S6** | The browser engine and Core Web Vitals | Test type 2 | Large | ✅ | **done** — `HANDOFF.md` §3v |
| **S7** | Live monitoring | 9 Monitor | Large | — | **done** — `HANDOFF.md` §3w |
| **S8** | Load profiles: rate as well as users | 6 Load | Medium | — | **done** — `HANDOFF.md` §3x |
| **S9** | One run, many workloads | structural | Large | — | **done** — `HANDOFF.md` §3y |
| **S10** | The recorder, and git-pinned Playwright sources | 2 Sources → Record | Large | — | **built** — S10a–S10j. Bridge, draft, defect fixes, long-poll, the Validator proving a recording's correlations, git-pinned import with a repository allowlist, and **S10j: browser journeys that actually execute**, via per-engine execution routing plus a local Playwright adapter — which also fixed `CanonicalJson` emitting invalid JSON. **Two gaps remain**: the container runner is still a placeholder (no production browser execution), and §3af's browser-authoring gap leaves all of this with no studio surface |
| **S11** | Web performance under load | Test type 3 ★ | Medium | — | **done** — `HANDOFF.md` §3ad |
| **S12** | Scale, baselines, comparison | 10 Report | Medium | — | |

**Usable milestones:**

- **After S4** — a complete, trustworthy **API load studio**: import, refine, correlate, validate,
  run, read a report you can act on. That is Gatling Studio's whole value proposition, plus
  governance, plus a Validator it does not have.
- **After S6** — **release 1**: both capabilities, both usable without performance testing experience.
- **After S11** — the flagship: **web experience under load**.

### S1 — studio shell, API path end to end

**Add vitest first** — `platform-ui` has no test runner at all (`HANDOFF.md` §3k) and this roughly
triples the UI. CLAUDE.md §31 pause condition: **ask before starting.**

**Design tokens first** (§4.4), before any screen is written. Retrofitting them across a tripled UI
is the expensive order.

The shell, the test-type picker, and screens 1/2/3/6/7/9/10 wired to what already exists. Screen 3
(Refine) is the new one: the request table with keep/drop, journey grouping, and — importantly — a
panel showing **what the importer removed and why**. That information already exists as findings; the
current panel shows it as a list, and it belongs against the requests it concerns.

*Acceptance:* someone imports an OpenAPI document or a HAR, refines it, sets load and objectives, runs
it and reads a report, without touching YAML. The generated specification is byte-identical to the
same test authored in YAML — CLAUDE.md §32's "one specification, three channels", proven. No literal
colour appears outside `tokens.css`, pinned by a test. V1 renders unchanged.

### S2 — the report a novice can read

§4.2, and deliberately **no runner-image change** — everything here is derivable from data the
control plane already holds, which is what keeps it inside release 1.

1. **The answer first, in a sentence**, not a verdict chip above a table.
2. **Every metric explained where it appears** — what p95 is and why it is used instead of an
   average, what the error rate counts, why throughput can fall while load rises.
3. **Progress on the existing status page** — elapsed, remaining, current stage, and a bar over the
   stage profile. The plan's stage arithmetic and the shard's `startedAt` are already persisted, so
   this is presentation, not new measurement.
4. **The structure S12's baseline comparison plugs into**, so adding it later is a diff rather than a
   redesign.

*Acceptance:* someone who has never run a performance test reads a report and correctly states what
happened — and correctly identifies a run whose result means nothing (synthetic, missing shards,
indeterminate objectives), because those are already marked and the report must not soften them.

### S3 — journeys and rules

`ScenarioSpec.steps`, extractions, assertions, think time (§7). Extracted values share the `${...}`
namespace with dataset columns; a collision is an ERROR. Steps are canonical, therefore in the digest.
JMeter rendering: ordered samplers in the existing weighted controller, `JSONPostProcessor` /
`RegexExtractor` / `BoundaryExtractor`, `UniformRandomTimer`, `ResponseAssertion`.

The HAR importer's `import.har.journeys.notOrdered` warning is **retired** — inverted in place, per
`NEXT-SESSION.md` §3, never deleted.

Correlation **suggestions** with evidence, accepted or rejected by the user. The importer proposes
candidate extract/replacement pairs only as review items; the studio applies none until accepted.

*Acceptance:* a journey signs in, extracts a session and calls an authenticated endpoint. A
non-matching extraction produces a **failed** sample, not a fast one — pinned by a test that names the
failure.

### S4 — the Validator

§8. One iteration, every request and response inspectable, no load slot consumed, subject to the
target allowlist and per-team rate limiting.

Built in S4 for protocol workloads. Browser dry-run validation remains future work after the S6
browser execution foundation.

*Acceptance:* a journey with a wrong path, a broken correlation or a missing credential is caught in
seconds, with the offending request named.

### S5 — targets, credentials, sessions

§9 and §10 together: self-service target registration, `SecretResolver` (`run://`, `env://`, and a
**§31 pause** at `vault://`), `authRef` on workloads, the session pool, expiry detection.

Built in S5 through the production session-delivery slice: runtime credential references,
`SecretResolver`, broker-backed `run://session`, per-shard mounted `sessions.csv`, lifecycle cleanup,
Helm RBAC and run-facing broker docs. `HANDOFF.md` §3t carries the exact scope, verification and
remaining caveats.

*Acceptance:* an authenticated API load test at 200 VUs with exactly `poolSize` sign-ins — pinned by a
test that counts them. No credential value in any manifest, plan, artifact, event or log, verified by
grep as `RunnerJobManifestTest` already does.

### S6 — the browser engine and Core Web Vitals

Built in S6 for browser-only workloads: Playwright is registered behind the engine SPI, uploaded or
pasted scripts are represented by immutable artifact digests, browser profiles are canonicalized,
Core Web Vitals evaluate through the normal objective model, and the later S9/S11 work made
mixed-engine hybrid specifications executable.

`PlaywrightEngineProvider` behind the existing SPI. Pinned `mcr.microsoft.com/playwright` runner
image, non-root, all capabilities dropped, joining the `runnerImages` allowlist —
`RunnerCellSettings` needs no change, it was built for this.

Sources, per §5: **a list of URLs**, **a script pasted or uploaded** (from `npx playwright codegen`
or from a team that already has one), and — later — authored steps. A script is stored as an
immutable artifact keyed by its **content digest**, so it is as reproducible as a git SHA without any
git credential, and the runner fetches it by key exactly as it fetches JMX.

Git-pinned repository import waits for S10. `SourceSpec.GitSource` and the validator rules that
already refuse a mutable ref and path traversal stay in place, unused, until then.

New metrics in `ObjectiveEvaluator`, which today knows three and returns `INDETERMINATE` for anything
else — so the shipped `browser-only.yaml`'s `browser.journey.duration` objective **evaluates to
nothing today**. Add `web.lcp`, `web.inp`, `web.cls`, `web.ttfb`, `web.tbt`, `web.lighthouse.score`,
`browser.journey.duration`, `browser.step.duration`, `browser.error.rate`.

**Per page, not per run.** A five-page journey has five LCPs, and the slow one is the answer. Vitals
are reported per navigation and objectives may scope to a page, exactly as protocol objectives scope
to `workload/scenario` today.

Median of N iterations with spread (§6). Traces **off by default, on failure only** — a trace contains
request bodies, cookies, session values and whatever was on screen, and this is the first time the
platform *produces* the sensitive artefact rather than ingesting it (§12, R3).

#### Device and network profile — a first-class setting, not a footnote

```yaml
browserProfile:
  device: mobile            # mobile | desktop | a named Playwright device
  network: slow-4g          # slow-4g | fast-4g | wifi | none
  cpuThrottle: 4            # multiplier
```

[Lighthouse defaults to mobile, Slow 4G and a MotoG4-class CPU
throttle](https://github.com/abhinaba-ghosh/playwright-lighthouse) deliberately: that is where
problems appear. A platform that measured only desktop-on-corporate-LAN would report green for a site
that is unusable on a phone — the same class of quietly-wrong answer this codebase guards against
everywhere else. The studio therefore **defaults to mobile + Slow 4G**, states that it has, and makes
changing it one control.

Throttling is applied through CDP `Network.emulateNetworkConditions`, so it is Chromium-only, and the
profile is recorded on the run: a vitals number is meaningless without the conditions it was measured
under, and two runs under different profiles must never be compared as though they were the same test.

#### Lighthouse diagnostics — the *why*, not just the *what*

Playwright measures the journey; **Lighthouse diagnoses the page**. It runs against the same Chromium
over CDP on the remote debugging port, in the same pod, in the same run.

For someone with no performance testing experience this is the highest-value thing in the web half.
"Your LCP is 4.1 s" is a number. "Your LCP is 4.1 s because a 2.3 MB hero image loads before anything
else and three render-blocking scripts run first" is something to act on. The opportunities list —
unused JavaScript, render-blocking resources, unsized images, unoptimised formats — is the closest
this platform gets to telling somebody what to do next, and it comes from a tool that has been the
industry reference for a decade.

Stored as an artifact (the full HTML report) plus the category scores and top opportunities extracted
into the run's own report, so the summary is readable without downloading anything.

**Runner-image decision, worth flagging:** this adds the `lighthouse` npm package to the Playwright
runner image. Not a Maven dependency and not a §31 pause on that basis, but it is a pinned addition to
a production runner image and belongs in the completion report as one. Playwright 1.50 requires
**Node 22**, which constrains the base image.

### S7 — live monitoring — **built**

`HANDOFF.md` §3w and `docs/operations/live-monitoring.md`. Everything below was delivered as
described, with one caveat carried forward: the runner-image half has **not been run on a cluster**,
so `docs/operations/local-kubernetes-run.md` §7 remains outstanding.

**Progress snapshots.** Every 5 seconds each runner reduces what it has produced so far and PUTs a
`progress.json` under its own shard prefix; the reconciler merges across shards on each pass. Not a
callback: a runner holds **no control-plane credential by design** — a provider able to write run
state could mark its own failures as successes (`HANDOFF.md` §3e) — and it already holds
object-storage credentials. Engine-neutral, so the browser runner does the same with journey
durations and vitals, and no JMeter `BackendListener` or Graphite endpoint is involved.

**The Monitor screen.** Throughput, latency bands, error rate with the actual failing requests,
per-journey breakdown, **objectives tracked live against their thresholds**, shard heartbeat age, and
a prominent cancel.

**The interpretation layer.** A small, auditable rule set over observed data — error rate rising,
latency knee, throughput plateau, heartbeat lost, objective crossed — each message naming the
observation behind it. Rules, never a model's opinion: a tool that tells a novice something confident
and wrong is worse than a bare chart.

*Acceptance, and what was measured:* **11–14 s stale** against real load through the local provider,
inside the ~15 s target. A lost generator surfaces as `RUNNER_SILENT` with its own table row rather
than as a quiet drop in load — verified by unit and reconciler tests; the two-shard kill on a cluster
is still outstanding. A run that dies leaves its last snapshot as a durable series point, taken on
the way out of `RUNNING` and again on cancellation. **Every interpretation message is pinned by a
test naming its observation** (`ProgressInterpreterTest`).

### S8 — load profiles: rate as well as users — **built**

`HANDOFF.md` §3x and `docs/operations/load-models.md`. Delivered as described, with two corrections
the work itself forced:

- The plugin group is the **Free-Form Arrivals Thread Group** for the open model and the **Ultimate
  Thread Group** for the closed one, not `ConcurrencyThreadGroup`. That group ramps from zero and has
  no delay, so a multi-stage profile built from a sequence of them would overlap; the two chosen
  groups express a staged profile exactly. Established by running candidate plans through a real
  JMeter rather than from documentation.
- A **throughput objective was unwritable** — `Threshold` had no per-second unit, so the studio's own
  `380/s` was refused. Fixed here because in an open model, failing to achieve the rate is the
  finding.

Verified with real load: 2,239 samples against ~2,250 specified for the open model, and a closed-model
run whose active users climbed 1→9 where the old rendering jumped 5→10.

### S9 — one run, many workloads — **built**

`HANDOFF.md` §3y. Delivered as described, in Flyway `V9` rather than `V7` (taken by registered
targets), with one item moved:

**The browser-session budget moved to S11 and is now built.** S9 deliberately left it out because a
real browser budget needed the run's browser demand at trigger time; S11 added that trigger-time
enforcement when hybrid coordination made the budget meaningful.

*Acceptance — met:* a workload naming an engine this deployment does not have is refused by the new
engine-capability validation layer, while somebody is still editing the document.

**Consequence worth knowing:** two protocol workloads on one engine now compile to two plans and
therefore two runners, where before they shared one. Separately provisioned workloads are exactly
what S11's start conditions need, but it is a real change in pod count.

### S10 — the recorder, and git-pinned Playwright sources

§5. Recording pod, CDP screencast to the console, target-locked navigation, enforced egress,
capped lifetime and sanitization on capture. Produces a draft protocol journey **and** a draft
Playwright script from one session.

Security gate is complete and binding:
[`docs/architecture/recorder-security-review.md`](docs/architecture/recorder-security-review.md).
Build order is now recorder security foundation first, capture sanitizer second, CDP bridge/UI
third, and git-pinned Playwright import fourth. Do not add a clickable recorder entry point before
target-locking, network egress enforcement and sanitization are implemented and tested.

S10a implemented the domain-level part of the security foundation: `RecorderSessionPolicy`,
`RecorderRouteGuard`, `RecorderAddressPolicy`, `CidrBlock` and recorder-specific scopes. It can
decide whether a browser destination is allowed for a frozen session.

S10b implemented the Kubernetes half of that foundation: a recorder-specific Job contract, a
per-session `NetworkPolicy` renderer based on the frozen destination IPs, Helm RBAC for generated
policies and a local-only administrator bypass
(`platform.orchestration.recorder.network-enforcement.enabled=false`) that production profiles
refuse.

S10c implemented durable recorder audit events, a capture sanitizer and a safe console entry point.
The entry point is deliberately pending: it creates a target-bound audited session and returns
`PENDING_BROWSER_BRIDGE` rather than exposing CDP before the guarded runtime bridge exists. The
sanitizer removes credential headers, token-shaped header values, URL userinfo, browser storage
state and suspicious bodies before captured material can be rendered or converted. Lower-environment
credentials are still stripped from captures because the issue is propagation into versioned
authoring material, API responses and audit surfaces, not only production blast radius.

**S10h — proving what a recording's correlations actually do.** Accepting a suggestion used to be
an unchecked claim: the Validator saw the extraction resolve, saw every assertion pass, and reported
success — which it would have done just as readily had the rule been applied to nothing, because the
literal it was meant to replace is usually still valid minutes after capture. The Validator now runs
against the recording, recovers those literals, and reports `proven` / `unproven` / `used` /
`unused` / `not-extracted` per rule. A dead rule is an ERROR. `HANDOFF.md` §3ak.

**S10i — git-pinned Playwright sources.** The domain modelled them already; the engine dropped them.
A git source compiled into a plan with two empty strings and the runner got a workload with no
script. Coordinates now reach the plan (and therefore the plan digest), a source with no material is
refused at compile rather than emitted empty, and `GitSourceAllowlist` stops a specification naming
a repository nobody declared — fail-closed, because an empty allowlist means "not decided", not "no
restriction". Credentials in a clone URL are refused outright. `HANDOFF.md` §3al.

**S10j — a browser journey actually runs.** S6 through S10i built everything up to the moment of
execution and stopped there: compile, digest, admit, reserve capacity, render, store — and then
nothing could run a Playwright workload anywhere. The local adapter rejected every non-JMeter engine
and was the only provider bean; the Kubernetes path its error message recommended was a placeholder
entrypoint that never invoked `npx playwright test`. Execution now routes **per engine** rather than
per deployment, and `LocalPlaywrightExecutionProvider` drives a real browser cohort from a
`workspace://` script for development.

It also fixed `CanonicalJson`, which wrote no separator between object fields, making **every plan
this platform ever rendered invalid JSON** and every plan digest a hash of a malformed document. It
went unseen because JMeter executes a JMX file and never parsed a plan — the browser runner is the
first consumer that does, and it failed on the first field. The class had no test. `HANDOFF.md` §3am.

**S10 is built.** Two things remain, and neither is small:

- **The container runner is still a placeholder**, so browser workloads execute on a developer's
  machine and nowhere else. The platform can now produce a browser report that no production
  deployment could. Porting `perf-runner.mjs`/`perf-reporter.mjs` into the pinned image is the fix.
- **The studio cannot author a browser test at all** (`HANDOFF.md` §3af), so S10h's correlation
  panel, S10i's git-source form and S10j's runnable browser workload have no screen between them.

### S11 — web performance under load — **built**

Built in S11. `LoadStage` now has an optional `name`, so
`startWhen: { workload, stage }` resolves and `hybrid.yaml`'s `stage: steady-state` names a real
stage. S11 also carries S9's deferred browser-session budget, since this is the increment where a
browser cohort first shares a run with a protocol workload and the two budgets stop being
interchangeable.

The browser cohort starts when **all three** hold: protocol shards `RUNNING`, wall-clock past the
stage boundary computed from the shard's `startedAt`, **and** a minimum sample count observed. Time
alone is not enough — a slow ramp or an image pull means the clock says steady state while load is
still climbing, and the cohort measures the wrong moment and reports a number that looks fine.

One report, one timeline: *"p95 API latency 640 ms, LCP 3.1 s, INP 240 ms — at 500 concurrent users."*

### S12 — scale, baselines, comparison

Baseline comparison per definition (`ROADMAP.md` §2) — a single p95 means little; "worse than last
week?" is the actual question, and it applies to web vitals more than to anything else. Browser
sharding across pods (5 × 10 = 50) for cohorts above the policy ceiling. Protocol sharding above 1,
gated on `docs/operations/runner-calibration.md`. A distinct Karpenter provisioner for
memory-heavy browser pods. BrowserStack behind the same execution contract — seam only, real
credentials are a §31 pause. Re-run the kind procedure (`ROADMAP.md` §0's outstanding caveat).

---

## 12. Risks and decisions

| | | |
|---|---|---|
| **R1** | ~~vitest~~ | **Resolved: approved.** Added in S1. |
| **R2** | **The recording browser is a user-driven browser inside the network.** Target-locking and egress policy are the controls | **Reviewed:** [`docs/architecture/recorder-security-review.md`](docs/architecture/recorder-security-review.md). S10 may proceed only through the documented security-foundation gate. |
| **R3** | **Traces and screenshots may contain customer data**, and the platform serves them for download | Governance answer needed. Proposal: on-failure only, short retention, sanitized. |
| **R4** | **Secret manager** beyond mounted `k8s://` and local development references | §31 pause. S5 added the `SecretResolver` port and mounted Kubernetes Secret resolver; Vault/AWS/Azure providers remain explicit decisions. |
| **R5** | **Git credentials** for Playwright source fetch | **Not needed in release 1** — scripts are uploaded, not cloned (§5). S10 builds the seam. |
| **R6** | ~~**Who provisions the test-user pool?**~~ | **Resolved for the platform seam:** S5 delegates acquisition to the approved external session broker contract. The owning broker/team still supplies real accounts outside this repo. |
| **R7** | ~~**Session pool artefacts hold live credentials** for a run's duration~~ | **Resolved:** S5 delivers per-shard `sessions.csv` through run-scoped Kubernetes Secret volumes, never downloadable artifacts, with terminal and TTL cleanup. |
| **R8** | **Browser cost.** 50 sessions ≈ 40 GB and 25 cores of ephemeral capacity | Budget decision, not an architectural one. |
| **R9** | **INP cannot be claimed as the field metric.** The platform measures interactions in *its* journey | Must be labelled in the report, not just documented (§6). |
| **R10** | **Playwright may not run on a read-only root filesystem** | **Still open.** S7 did not exercise the Playwright image on a cluster; it added a progress loop to that image without building or running it. Resolve when the image is first run on kind. |

---

## 13. Deliberately not doing

- **k6.** Not approved (CLAUDE.md §3). Its studio is a UX reference; no code, dependency or engine.
- **A "custom code" rule.** §7 argues why: it would be a hole through the reviewability that makes a
  declarative specification safe at 1,000 users.
- **Generating a script from plain-language intent.** CLAUDE.md §21. Recording a person's actions is
  not this, and §21's own later-phase list names visual recording as legitimate.
- **A desktop application.** The scale, the governance and the durable history are the reason to be
  server-side.
- **Jenkins shared library.** CI triggering is not current practice.
- **Letting a specification name its own host, or hold a credential value.** §9 and §10 remove the
  friction instead.
- **Heap and thread dumps in the run path.** CLAUDE.md §22.

---

## 14. What release 1 looks like

A person who has never run a performance test opens the studio and picks one of two things.

### "Test an API"

1. **Import** — OpenAPI, Postman, cURL, or a HAR captured through the guided DevTools wizard.
2. **Refine** — noise already filtered and *explained*, requests grouped into journeys.
3. **Correlate** — accept the suggestions the platform proposes with evidence, so the journey can
   sign in and carry the session forward.
4. **Parameterize** — bind inputs to an uploaded CSV.
5. **Size** — how many people at once, ramp, steady state.
6. **Objectives** — response time, error rate, throughput.
7. **Validate** — one iteration against the real target, every request and response inspected, in
   seconds, before a load slot is booked.
8. **Run and read** — a report that opens with the answer in a sentence and explains every number
   where it appears.

### "Test web performance"

1. **Journey** — a list of URLs, a script from `npx playwright codegen`, or an existing `.spec.ts`
   pasted or uploaded.
2. **Profile** — mobile or desktop, network and CPU throttling. Defaults to mobile + Slow 4G, and
   says so.
3. **Objectives** — LCP, INP, CLS, and the Lighthouse score.
4. **Run and read** — per-page Core Web Vitals as a median with spread, plus **Lighthouse
   diagnostics** naming what to fix.

Both without writing a line of JMX, JavaScript or YAML — and with every run authorized,
quota-bounded, digest-identified, durable and reproducible, which is the part no desktop studio has.

### Deferred to release 2, deliberately

Arrival-rate sizing (S8), the multi-plan refactor (S9), the server-side
recorder and git-pinned Playwright import (S10), **web experience under load** (S11), and baselines
and sharding (S12).

### The test of whether this succeeded

Not that the screens exist. That someone who has never run a performance test can look at the result
and correctly say what it means — and, just as important, can tell when it means nothing.

---

**Sources:** [Gatling Studio](https://gatling.io/blog/gatling-studio) ·
[Grafana k6 Studio](https://grafana.com/docs/k6-studio/) ·
[k6 Studio Test Generator](https://grafana.com/docs/k6-studio/components/test-generator/) ·
[Core Web Vitals 2026](https://www.corewebvitals.io/core-web-vitals) ·
[Playwright + Lighthouse](https://github.com/abhinaba-ghosh/playwright-lighthouse) ·
[Playwright performance testing](https://testdino.com/blog/playwright-performance-testing)

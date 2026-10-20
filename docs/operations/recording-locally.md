# Recording a journey on localhost

How to open a recording on your own machine, what the flags mean, and what to do when it does not
start. The security reasoning behind all of it is
[`docs/architecture/recorder-security-review.md`](../architecture/recorder-security-review.md);
this file is the procedure.

---

## 1. What you need

- **Chrome or Chromium installed.** The bridge starts one as a child process. It looks in the usual
  places on macOS and Linux; set `RECORDER_LOCAL_BROWSER_BINARY` if yours is somewhere else.
- **The platform running**, which is one command.

That is all. No extension, no proxy, no certificate, no `sudo`.

---

## 2. Start it

```bash
./scripts/start-platform.sh
```

The script enables the recorder for you. It is the only place that does, and it prints the API key
and the URLs when everything is up. It starts four things: the demo service (:8081), the control
plane (:8080), the console (:5173) and the **student app** (:5174).

**Record the student app, not the demo service.** The demo service is API-only, so recording it
means recording a JSON page. The student app is a real browser client for it — clicking through
produces exactly the API calls a journey is made of. Point your target's `baseUrl` at
`http://localhost:5174` in `config/teams/local-dev.yml`.

Then:

1. Open <http://localhost:5173> and paste the key it printed (`local-dev-key-not-a-secret`).
2. Stay on the **Performance tests** tab. The **Recorder** strip is above *Your tests*.
3. Choose the target whose `baseUrl` is the student app.
4. Press **Start guarded recording**.

The panel opens with a live browser surface. Click it once to give it focus, then use the page as
you normally would. Typing goes to the recorded page, not to the console.

---

## 3. Drive the browser

Click the surface once to give it focus. After that:

| To | Do |
|---|---|
| Click, type | Just click and type — input goes to the recorded page, not the console |
| Scroll | Scroll over the surface, as you would on any page |
| Go back, reload | The **Back** and **Reload** buttons above the surface |

**The surface only updates when the page repaints.** Chrome sends a frame on repaint and at no other
time, so a page you are simply reading looks frozen. It is not — scroll it or click something and it
updates immediately. This is also why an idle recording makes about one network request every
fifteen seconds rather than a continuous stream.

---

## 4. Turn the recording into a test

Press **Turn this into a test** once you have done the journey you care about. You do not have to
stop first — the button works on a live session.

**It creates nothing.** It opens the studio, scrolled into view, on **Review journeys** with the
recorded journeys loaded. From there it is the ordinary flow: review the journeys, set the load, set
the objectives, and press **Save test** on the last step. That is the step that creates something.

### Then how do you run it?

After saving, the test appears under **Your tests** in the console. Select it and press **Run this**.
The run then shows live progress and, when it finishes, a report.

If you never press **Save test**, nothing is created — no definition, no version, no run. That is
deliberate: a recording is authoring material, and CLAUDE.md §23 requires a person to review it
before it can execute.

**What will not be in the draft, by design:**

| Left out | Why |
|---|---|
| Images, fonts, stylesheets, scripts | A journey is requests, not page furniture |
| Anything that failed | Replaying a 404 measures the error path and reports it as latency |
| Cookies, `Authorization`, tokens, passwords | They belong in `spec.authentication` as a secret reference |
| The host you recorded | Where traffic goes is resolved from the team's registered target |

So a recording of a page that only loaded assets produces **no journeys**, and the console says so
rather than saving an empty test. Interact with the application — sign in, search, submit
something — and the API calls behind those actions become the steps.

Multi-step journeys usually need one more thing: a **correlation**, where a value from one response
is used by a later request. The studio's **Rules** screen proposes them with evidence, and a person
accepts or rejects each one. Nothing is correlated silently — a wrongly correlated journey looks
wired up and sends the wrong value.

### Then the Validator checks the rules you accepted

Accepting a rule is a claim, and the **Validate** step is where the platform checks it. On a draft
that came from a recording the Validator runs against **the recording**, not against the
specification alone, and reports a verdict for every correlation:

| Verdict | What it means | What to do |
|---|---|---|
| **Proven** | The value this iteration extracted was *different* from the one the recording captured, and a later step sent it. The rule works. | Nothing |
| **Unproven** | The extracted value was *identical* to the recorded one. | Fine for a value that never changes. If it should differ each time — a session, a token, a created id — record again and re-validate |
| **Not used** | The value was extracted and then sent by nothing. | **Fails validation.** Go back to Rules and re-accept the suggestion, or remove it |
| **Not found** | Nothing matched the extraction. | The step already failed; fix the expression |
| **Used** | Extracted and sent onwards, with no recording to compare against. | Nothing. This is what a hand-written definition reports |

**Why the comparison needs the recording.** Once a rule is accepted the literal it replaced is gone
from the draft — replacing it is the whole point. Only the recording still knows what the journey
used to send, and without that the Validator cannot distinguish a rule that carried a fresh value
from one that was never applied at all. The second case passes every step, because a token captured
five minutes ago is usually still valid; it fails at load, as an error rate, in a report somebody
then has to interpret.

---

## 5. What the start script switched on, and why production refuses it

Three flags, all passed by `start-platform.sh`:

```bash
--platform.orchestration.recorder.local-browser.enabled=true
--platform.orchestration.recorder.local-browser.allow-loopback-target=true
--platform.orchestration.recorder.network-enforcement.enabled=false
```

| Flag | What it does | Why production refuses it |
|---|---|---|
| `local-browser.enabled` | Runs Chrome as a child of the control-plane process | CLAUDE.md §4: the control plane must not be where material browser work happens. A cell runs the browser in its own Pod |
| `allow-loopback-target` | Lets a recorded target resolve to loopback | A browser that may address loopback can reach whatever else listens on that pod. Needed here only because the demo service *is* `http://localhost:8081` |
| `network-enforcement.enabled=false` | Skips the generated NetworkPolicy | There is no NetworkPolicy controller on a laptop. In a cluster this is the control that holds if the browser-layer guard has a bug |

Startup logs a warning for each. Setting any of them under the `production`, `prod` or
`shared-runner` profile makes the application **refuse to start**, with a message naming the setting
to change — a guard, not a convention (`ProductionSafetyGuardTest`).

**The loopback allowance relaxes loopback and nothing else.** Metadata endpoints, link-local,
wildcard, multicast and every configured prohibited range stay denied.

---

## 6. Where a recording can and cannot go

A session is locked to **one registered target**. You cannot type a URL into it and browse: the
address comes from the target registry, and the platform pins DNS to the addresses resolved when the
session started.

Anything outside that allowlist is refused and appears under **Refused destinations** with a reason.
That list is a feature — it is how you find out that the application pulls a script from a CDN, or
that sign-in redirects to an identity provider nobody declared.

To widen it, an operator declares the asset or login origin **on the target**. There is no way to
widen it from inside a recording, and redirects do not expand it.

---

## 7. When it does not work

### The session says `PENDING_BROWSER_BRIDGE`

The deployment has no browser. The panel prints which bridge it has, and it will say
`none (the local recorder browser is not enabled on this deployment)` or
`local-chrome (no Chrome or Chromium found on this machine)`.

- Started the control plane yourself rather than through the script? It defaults to **off**. Pass the
  flags in §5.
- No Chrome on this machine? Install Chrome or Chromium, or point at one:

  ```bash
  export RECORDER_LOCAL_BROWSER_BINARY=/path/to/chromium
  ```

The panel does not spin waiting for a browser that will never arrive — a pending session is a
settled outcome, and the reason is on screen.

### The session goes straight to `FAILED`

The bridge started and could not be driven. `failureReason` is in the panel and the control-plane log
has the detail:

```bash
grep -i recorder .local/logs/control-plane.log
```

### `422` with `recorder.target.refused` when starting

The target resolves somewhere the address floor refuses. If it is loopback, you are missing
`allow-loopback-target`. If it is link-local or a metadata address, that is the floor doing its job
and no flag changes it.

### `ERR_CONNECTION_REFUSED`, but the service answers `curl`

Almost always an address-family mismatch. Vite binds `[::1]` only; `curl localhost` will happily use
IPv6 while a pinned browser uses IPv4.

The recorder handles the loopback case: under the relaxed floor a loopback host is excluded from DNS
pinning so Chrome tries both families. If you see this against a **non-loopback** host, that is the
documented single-address limitation — `--host-resolver-rules MAP` takes one replacement, so a host
resolving to several addresses is pinned to the first. Check which address the service is actually
listening on:

```bash
lsof -nP -iTCP:5174 -sTCP:LISTEN
```

### The surface is not updating

Expected, if nothing on the page is changing — see §3. Scroll or click and it updates. If it stays
static while you are clearly interacting, check the control-plane log.

### The session expires while you are reading this

Five minutes without interaction and it closes itself; thirty minutes total regardless. Both are
deliberate — an unattended recording browser holds whatever you signed into. Start another one.

### Chrome is still running after you stop the platform

It should not be. An orderly shutdown closes every open browser, and the browser arms its own
deadline so even `kill -9` bounds it to the session deadline. If you killed the JVM and do not want
to wait:

```bash
pkill -f perf-recorder-
```

The temporary profile directory (`perf-recorder-*` under your temp directory) holds cookies from
whatever you recorded and is removed on close.

---

## 8. What a recording is not

**It is not a way to capture a credential for a run.** If you sign in as yourself during a recording,
that session belongs to that recording and stops with it. Material execution uses `run://session` or
a scoped test identity — recorder security review §5.

**It is not approval to execute.** A draft is a draft. It becomes runnable only when someone saves it
as a definition version, having looked at it.

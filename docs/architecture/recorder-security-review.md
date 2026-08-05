# S10 Recorder Security Review

**Status:** Required gate for S10 implementation  
**Date:** 2026-08-05  
**Scope:** Server-side recording browser, CDP/screencast bridge, capture-to-draft pipeline, and
git-pinned Playwright source import planned in `PLAN.md` S10.

## 1. Review Result

S10 can be designed, but it must not be built as "just another Playwright runner". A recording
browser is an interactive, user-driven network client inside the runner network. Without hard
runtime controls it is an internal open proxy.

The recorder is approved only as a gated implementation with all controls in this document. Until
those controls are implemented and tested, the product must continue to offer the release-1 fallbacks:

- DevTools HAR upload for protocol journeys.
- Uploaded or pasted reviewed Playwright `.spec.ts` artifacts for browser journeys.
- `run://session` or another approved session provider for material authenticated runs.

The security gate is not approval to persist or reuse a human's corporate SSO session for material
test execution. A recording session may help a person author a draft, but material runs must use
approved test identities and the existing session-acquisition contract.

## 2. Assets and Trust Boundaries

Protected assets:

- Internal applications reachable from the runner network.
- Corporate SSO sessions, cookies, local/session storage and bearer tokens created during recording.
- Captured request/response bodies, screenshots, traces and generated Playwright scripts.
- Control-plane credentials, runner service accounts and object-storage prefixes.
- Target registry and allowlist policy.

Trust boundaries:

- Browser user to control plane.
- Control plane to recorder pod.
- Recorder pod to target network.
- Recorder pod to object storage.
- Capture pipeline to immutable test-definition version.
- Git source fetcher to repository hosts and runner images.

The recorder pod is less trusted than an ordinary runner pod because it accepts live input from a
human through the control plane.

## 3. Principal Threats

| Threat | Risk | Required control |
|---|---|---|
| Open proxy into internal network | User navigates the browser to arbitrary internal hosts | Target-locked browser routing plus enforced egress policy |
| SSRF and metadata access | Redirects, iframes, script fetches or WebSockets reach metadata/link-local/Kubernetes APIs | Deny prohibited CIDRs and schemes at both browser and network layers |
| DNS rebinding | Approved hostname resolves to a prohibited IP after session start | Resolve and freeze destination IPs for the session; revalidate before start |
| Asset-domain escape | A target page loads or redirects to unregistered domains | Registered target plus declared asset-domain allowlist; block and audit everything else |
| Credential capture | Cookies, auth headers, storageState or response bodies persist secrets | Sanitize before storage; never log raw CDP events; do not promote human sessions to runtime credentials |
| CDP command abuse | Raw DevTools Protocol gives filesystem/browser/process-level capabilities | Never expose raw CDP to the user; broker a narrow input/screencast API |
| Artifact data leakage | Captured traces/screenshots contain customer or credential material | Short retention, restricted downloads, digesting, masking, and disabled-by-default raw evidence |
| Duplicate traffic | Pod retry or reconnect repeats a recorded workflow unexpectedly | `backoffLimit: 0`, `restartPolicy: Never`, active deadline, explicit user action for replay |
| Cross-team access | User records another team's target or reads another team's capture | Team/application authorization on create, stream, save and artifact access |
| Git import supply chain | Repository fetch or dependency installation executes untrusted code | Fetch immutable refs only; no shell hooks in importer; execution stays in restricted runner |

## 4. Mandatory Runtime Controls

### 4.1 Target Lock

The recorder must start only from a registered target resolved through the existing target registry.
The user must not provide a raw base URL that bypasses team/application/environment ownership.

The effective allowlist for a session is:

- The registered target origin.
- Explicit asset domains declared on that target by an operator or team administrator.
- Optional corporate identity-provider origins needed for login, declared as a separate login
  allowlist rather than inferred from redirects.

The browser layer must block every navigation, subresource request, popup, iframe, worker, fetch,
WebSocket and EventSource whose destination is outside this allowlist. Blocks are visible in the UI
and recorded as audit events.

### 4.2 Network Enforcement

Browser routing is not enough. S10 must add one of these runtime network controls before any recorder
pod is created:

- A Kubernetes `NetworkPolicy` or CNI-specific policy generated from session-approved destination
  CIDRs.
- An egress gateway/proxy that enforces the same allowlist by hostname and resolved IP.

Plain Kubernetes `NetworkPolicy` cannot express hostnames. If target and asset controls are
hostname-based, the implementation must resolve them to IPs before session start, reject prohibited
ranges, freeze the result for the session, and revalidate DNS on reconnect. If that is not acceptable
for the chosen CNI, use an egress proxy instead.

Always deny by default:

- Link-local and metadata ranges.
- Kubernetes API server and cluster service CIDRs unless an explicit platform exception exists.
- Loopback from the pod.
- Raw IP navigation unless the exact IP is registered as the target.
- `file:`, `data:`, `blob:` top-level navigation, browser downloads to durable storage, and
  user-provided file uploads from the recorder container.

### 4.3 Pod and Identity

Recorder pods require a separate profile from load runners:

- Separate namespace or a clearly isolated recorder sub-namespace.
- Dedicated recorder service account with no Kubernetes API permissions.
- `automountServiceAccountToken: false` unless a narrowly justified implementation requires it.
- Non-root user, dropped capabilities, no privilege escalation, runtime-default seccomp.
- Read-only root filesystem and size-limited writable `emptyDir`.
- CPU and memory requests/limits sized for one browser.
- `restartPolicy: Never`, `backoffLimit: 0`, `activeDeadlineSeconds`, idle timeout and finished
  object TTL.
- No secret values in labels, annotations, environment variables, command arguments or logs.

Do not reuse the legacy Helm JMeter Job template as a base. S10 should follow the tested
`RunnerJobManifest` safety contract and then add the recorder-specific restrictions above.

### 4.4 CDP Bridge

The user must interact with a platform session endpoint, not with a raw DevTools endpoint.

Required bridge controls:

- Bind every stream to the authenticated caller, team, application and recorder session ID.
- Validate WebSocket origin and CSRF protections for any browser-control channel.
- Expose only input events, viewport changes and screencast frames needed for recording.
- Keep browser-internal debugging and arbitrary CDP commands unavailable to the user.
- Rate limit input and session creation.
- Close the browser and pod on logout, idle timeout, deadline, cancel or authorization loss.

## 5. Capture and Storage Rules

The capture pipeline must sanitize before persistence. Raw CDP event logs, raw HAR bodies, raw
storageState and raw traces must not be durable artifacts unless an explicit privileged diagnostic
workflow approves them.

Required handling:

- Drop or mask credential headers using `SecretValueDetector.credentialHeaders()`.
- Scan structured bodies with `SensitiveFieldDetector`; refuse unsafe bodies rather than partially
  redacting unknown structures.
- Limit captured body size and binary/media capture.
- Do not store authorization headers, cookies, set-cookie headers, localStorage/sessionStorage
  tokens or SSO artifacts in generated scripts.
- Treat screenshots, traces and HTML reports as sensitive evidence: team-authorized, short-retention
  and never publicly downloadable.
- Store generated protocol journeys and Playwright scripts as draft artifacts with SHA-256 digests.
- Require human review and normal definition-version approval before a captured draft can execute.

Interactive login is allowed for authoring only. If the user signs in as themselves, the resulting
session state must not be used as runtime credential material. Material execution must use
`run://session`, a scoped service identity, or another approved test-account/session provider.

## 6. Authorization and Governance

The recorder requires explicit scopes separate from running tests:

- `recordings:create`
- `recordings:read`
- `recordings:cancel`
- `definitions:write` to save the resulting draft

Every operation must also authorize team and application ownership. A user who can run a test for
one team must not be able to start a recording browser against another team's target.

Initial S10 implementation should be non-production only. Production recording needs a separate
policy decision because it combines an interactive browser, real credentials and sensitive evidence.

Audit every material event:

- Session requested, started, connected, disconnected, cancelled and expired.
- Caller, team, application, environment and target ID.
- Effective allowlist digest.
- Blocked destinations and reason.
- Generated draft artifact IDs and digests.
- Promotion of a draft into a test-definition version.

Audit details must not contain credential values, cookies, full request bodies or raw screenshots.

## 7. Git-Pinned Playwright Import

S10 also plans git-pinned Playwright sources. The existing domain already validates immutable commit
refs and blocks path traversal. The implementation must keep the importer narrower than the runner:

- Fetch only from configured repository hosts.
- Use secret references for repository credentials, never inline credentials.
- Require commit SHA, not branch or tag.
- Keep path resolution inside the checkout root.
- Do not execute repository hooks or arbitrary shell commands during import.
- Produce a content-addressed artifact and digest before review.
- Run the script only through the approved Playwright runner image and normal execution provider.

Dependency installation for a Playwright repository is an execution concern, not an importer concern,
and must be lockfile-based inside the restricted runner.

## 8. Required Tests Before S10 Is Considered Built

Implementation is not complete without these tests:

- Route guard tests for normal navigation, redirects, iframes, popups, workers, fetch, WebSocket and
  EventSource destinations.
- DNS and address-policy tests for metadata, link-local, loopback, Kubernetes service ranges,
  prohibited private ranges and approved registered private targets.
- Manifest tests proving recorder pods are non-root, non-privileged, deadline-bound, non-retrying,
  resource-limited, token-free where possible, and carry no secrets.
- Helm rendering tests for deny-by-default recorder egress policy or egress-proxy configuration.
- API authorization tests for cross-team denial on create, stream, cancel, save and artifact access.
- Capture sanitization tests for headers, cookies, set-cookie, JSON bodies, form bodies, binary
  bodies, storageState and generated scripts.
- Audit tests proving blocked navigation is recorded without sensitive values.
- Git import tests for immutable refs, path traversal, allowed repository hosts and no hook/script
  execution.
- A kind smoke that starts a recorder pod, proves an approved target is reachable, proves a blocked
  target is unreachable from inside the pod, cancels the session and verifies pod cleanup.

## 9. S10 Implementation Gate

Build S10 in this order:

1. Recorder security foundation: target allowlist model, address policy, route guard, egress policy
   rendering, recorder pod manifest contract and audit events.
2. Capture sanitizer: request/response capture, draft artifact storage, generated Playwright script
   stripping, and non-promotion of human sessions.
3. CDP bridge and UI: screencast/input channel bound to the caller and session.
4. Git-pinned Playwright import: fetcher, artifact digesting and review flow.

Do not add the UI entry point until steps 1 and 2 are test-covered. A clickable recorder button
without the network and sanitization controls is the unsafe state this review is meant to prevent.

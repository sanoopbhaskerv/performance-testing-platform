import { useCallback, useEffect, useRef, useState } from 'react';
import { AlertTriangle, MonitorPlay, RotateCcw, ShieldAlert, Square, Undo2, Wand2 } from 'lucide-react';
import type {
  ImportResponse,
  V2Client,
  RecorderBlockedDestination,
  RecorderCapturedExchange,
  RecorderInput,
  RecordingSession,
} from './api';

/**
 * The live recording browser.
 *
 * The browser runs on the server. What arrives here is a stream of JPEG frames and what leaves is a
 * fixed set of interactions — move, click, wheel, key, text, navigate, reload, back. That asymmetry
 * is the security model, not an implementation detail: there is no way to express a DevTools
 * command from this component, so no request originating here can ask the browser to read a file,
 * evaluate script or attach to another page.
 *
 * ## Three things this screen must not get wrong
 *
 * **Pointer coordinates are in the browser's space, not the image's.** The frame is scaled to fit
 * whatever width the panel has; sending the click position in rendered pixels would land it
 * somewhere else on the page. Every pointer event is converted back through the frame's own
 * dimensions.
 *
 * **A refused destination is shown, not swallowed.** A recorder that silently drops requests looks
 * broken and teaches nobody anything. Blocks appear with their reason, so widening the allowlist is
 * a deliberate act by an operator rather than a mystery to debug.
 *
 * **A session with no bridge says so.** `PENDING_BROWSER_BRIDGE` is a real state — the deployment
 * has no browser configured — and this renders the reason rather than a spinner that never resolves.
 */

/**
 * Frames are polled, not streamed: EventSource cannot send the API key header.
 *
 * The poll is a *long* poll — the server holds the request until there is something to say. So this
 * is the pause between one answer and the next request, not the rate: a quiet recording makes one
 * request every {@link LIVE_WAIT_MS}, not eight a second. The first version had no wait and polled
 * on this interval regardless, which turned a five-minute recording into two thousand requests that
 * almost all returned nothing.
 */
const POLL_INTERVAL_MS = 30;

/** How long the server may hold a poll open waiting for a frame, a block or a capture. */
const LIVE_WAIT_MS = 15_000;

/** Consecutive poll failures before the panel stops trying and says it lost contact. */
const MAX_CONSECUTIVE_FAILURES = 5;

/** Chrome's modifier bitmask. */
const MODIFIER_ALT = 1;
const MODIFIER_CTRL = 2;
const MODIFIER_META = 4;
const MODIFIER_SHIFT = 8;

const TERMINAL_STATES = new Set(['CLOSED', 'FAILED', 'CANCELLED', 'EXPIRED']);

/** Approximate pixels for a line and a page of scrolling, for wheels that report in those units. */
const PIXELS_PER_LINE = 16;
const PIXELS_PER_PAGE = 800;

/** Converts a wheel delta to the pixels Chrome's input domain expects. */
function scaleDelta(delta: number, deltaMode: number): number {
  if (deltaMode === 1) {
    return delta * PIXELS_PER_LINE;
  }
  if (deltaMode === 2) {
    return delta * PIXELS_PER_PAGE;
  }
  return delta;
}

/**
 * Whether the server has forgotten this session.
 *
 * A 404 here does not mean a bad request — the session existed when the panel opened. It means the
 * session ended and was released, so there is nothing to poll for and retrying is pure noise.
 */
function isGone(error: unknown): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    (error as { response?: { status?: number } }).response?.status === 404
  );
}

interface Props {
  client: V2Client;
  session: RecordingSession;
  onSessionChange: (session: RecordingSession) => void;
  onClose: () => void;
  onError: (message: string) => void;
  /** Hands the drafted journeys to the studio. Absent when there is nowhere to take them. */
  onDraft?: (draft: ImportResponse) => void;
}

export function RecorderPanel({
  client,
  session,
  onSessionChange,
  onClose,
  onError,
  onDraft,
}: Props) {
  const [status, setStatus] = useState(session.status);
  const [frame, setFrame] = useState<string | null>(null);
  const [frameSize, setFrameSize] = useState({
    width: session.viewportWidth,
    height: session.viewportHeight,
  });
  const [blocked, setBlocked] = useState<RecorderBlockedDestination[]>([]);
  const [captures, setCaptures] = useState<RecorderCapturedExchange[]>([]);
  const [failure, setFailure] = useState<string | null>(session.failureReason);

  const surfaceRef = useRef<HTMLDivElement | null>(null);
  const cursors = useRef({ frame: 0, blocked: 0, capture: 0 });
  const pointerDown = useRef(false);

  const live = status === 'LIVE';
  const terminal = TERMINAL_STATES.has(status);
  /**
   * A session that came back pending has no browser and never will.
   *
   * `start` attaches the bridge before it answers, so pending is a settled outcome rather than a
   * step on the way to LIVE. Polling it would be a request every 120ms for frames that cannot exist.
   */
  const unbridged =
    session.status === 'PENDING_BROWSER_BRIDGE' && status === 'PENDING_BROWSER_BRIDGE';

  const send = useCallback(
    async (input: RecorderInput) => {
      if (!live) {
        return;
      }
      try {
        await client.sendRecorderInput(session.id, input);
      } catch {
        // Input is high-frequency and individually unimportant: a dropped mouse move is invisible,
        // and surfacing every refusal would bury the screen in transient errors. The session's own
        // state, polled below, is what tells the user if something is actually wrong.
      }
    },
    [client, session.id, live],
  );

  // ── Polling ──────────────────────────────────────────────────────────
  useEffect(() => {
    if (terminal || unbridged) {
      return undefined;
    }
    let cancelled = false;
    let timer: number | undefined;
    let failures = 0;

    const poll = async () => {
      try {
        const update = await client.pollRecording(session.id, cursors.current, LIVE_WAIT_MS);
        if (cancelled) {
          return;
        }
        failures = 0;
        setStatus(update.status);
        if (update.frame && update.frame.sequence > cursors.current.frame) {
          cursors.current.frame = update.frame.sequence;
          setFrame(update.frame.imageBase64);
          setFrameSize({ width: update.frame.width, height: update.frame.height });
        }
        // A long poll can replay evidence after a retry or reconnect. Sequence identity keeps
        // the visible lists and cursors monotonic rather than duplicating the same refusal.
        const freshBlocked = update.blocked.filter(entry => entry.sequence > cursors.current.blocked);
        if (freshBlocked.length > 0) {
          cursors.current.blocked = Math.max(...freshBlocked.map(entry => entry.sequence));
          setBlocked((current) => [...freshBlocked, ...current].slice(0, 25));
        }
        const freshCaptures = update.captures.filter(entry => entry.sequence > cursors.current.capture);
        if (freshCaptures.length > 0) {
          cursors.current.capture = Math.max(...freshCaptures.map(entry => entry.sequence));
          setCaptures((current) => [...freshCaptures, ...current].slice(0, 50));
        }
        if (update.failureReason) {
          setFailure(update.failureReason);
        }
      } catch (err) {
        if (cancelled) {
          return;
        }
        failures += 1;

        // A session the server no longer knows about is over, not failing. Retrying cannot bring it
        // back, so stop immediately and say so — the alternative, seen in practice, is eight
        // requests a second forever against an endpoint that will keep answering 404.
        if (isGone(err)) {
          setStatus('CLOSED');
          setFailure('This recording has ended and is no longer available on the server.');
          return;
        }

        if (failures >= MAX_CONSECUTIVE_FAILURES) {
          setStatus('FAILED');
          setFailure('Lost contact with the recording session.');
          onError(
            err instanceof Error
              ? err.message
              : 'The recorder session stopped responding.',
          );
          return;
        }
      }
      if (!cancelled) {
        // Backs off while failing, so a control plane that is restarting is given room rather than
        // hammered. Reset to the fast path on the first success.
        timer = window.setTimeout(poll, POLL_INTERVAL_MS * 2 ** failures);
      }
    };

    void poll();
    return () => {
      cancelled = true;
      if (timer !== undefined) {
        window.clearTimeout(timer);
      }
    };
  }, [client, session.id, terminal, unbridged, onError]);

  // ── Scrolling ────────────────────────────────────────────────────────

  /**
   * Forwards the wheel to the recorded page.
   *
   * Attached natively rather than with React's `onWheel` because the listener has to be
   * **non-passive**. React registers wheel handlers passively, where `preventDefault` is ignored, so
   * every scroll would move the console behind the surface instead of the page inside it — the
   * recorded page would simply never scroll.
   */
  useEffect(() => {
    const surface = surfaceRef.current;
    if (!surface || !live) {
      return undefined;
    }
    const onWheel = (event: WheelEvent) => {
      event.preventDefault();
      const bounds = surface.getBoundingClientRect();
      if (bounds.width === 0 || bounds.height === 0) {
        return;
      }
      void send({
        kind: 'mouse-wheel',
        x: ((event.clientX - bounds.left) / bounds.width) * frameSize.width,
        y: ((event.clientY - bounds.top) / bounds.height) * frameSize.height,
        // Chrome expects pixels. A trackpad reports lines or pages on some platforms, so the
        // deltaMode is converted rather than passed through as if it were always pixels.
        deltaX: scaleDelta(event.deltaX, event.deltaMode),
        deltaY: scaleDelta(event.deltaY, event.deltaMode),
        modifiers: modifiersOf(event),
      });
    };

    surface.addEventListener('wheel', onWheel, { passive: false });
    return () => surface.removeEventListener('wheel', onWheel);
  }, [live, send, frameSize.width, frameSize.height]);

  // ── Coordinates ──────────────────────────────────────────────────────

  /**
   * Converts a pointer position on the rendered image back into browser coordinates.
   *
   * The frame is displayed at whatever size fits the panel, so the ratio between the two is what
   * makes a click land where the user aimed it.
   */
  const toBrowserPoint = (event: React.PointerEvent<HTMLDivElement>) => {
    const surface = surfaceRef.current;
    if (!surface) {
      return null;
    }
    const bounds = surface.getBoundingClientRect();
    if (bounds.width === 0 || bounds.height === 0) {
      return null;
    }
    return {
      x: ((event.clientX - bounds.left) / bounds.width) * frameSize.width,
      y: ((event.clientY - bounds.top) / bounds.height) * frameSize.height,
    };
  };

  const modifiersOf = (event: { altKey: boolean; ctrlKey: boolean; metaKey: boolean; shiftKey: boolean }) =>
    (event.altKey ? MODIFIER_ALT : 0) +
    (event.ctrlKey ? MODIFIER_CTRL : 0) +
    (event.metaKey ? MODIFIER_META : 0) +
    (event.shiftKey ? MODIFIER_SHIFT : 0);

  const buttonOf = (button: number): RecorderInput['button'] =>
    button === 2 ? 'right' : button === 1 ? 'middle' : 'left';

  // ── Handlers ─────────────────────────────────────────────────────────

  const handlePointerMove = (event: React.PointerEvent<HTMLDivElement>) => {
    const point = toBrowserPoint(event);
    if (point) {
      void send({ kind: 'mouse-moved', ...point, modifiers: modifiersOf(event) });
    }
  };

  const handlePointerDown = (event: React.PointerEvent<HTMLDivElement>) => {
    const point = toBrowserPoint(event);
    if (!point) {
      return;
    }
    pointerDown.current = true;
    surfaceRef.current?.focus();
    void send({
      kind: 'mouse-pressed',
      ...point,
      button: buttonOf(event.button),
      clickCount: event.detail || 1,
      modifiers: modifiersOf(event),
    });
  };

  const handlePointerUp = (event: React.PointerEvent<HTMLDivElement>) => {
    const point = toBrowserPoint(event);
    if (!point || !pointerDown.current) {
      return;
    }
    pointerDown.current = false;
    void send({
      kind: 'mouse-released',
      ...point,
      button: buttonOf(event.button),
      clickCount: event.detail || 1,
      modifiers: modifiersOf(event),
    });
  };

  const handleKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    // Tab would move focus out of the surface and Enter/Space would activate the element behind it,
    // so the recorded page would never see either.
    event.preventDefault();
    void send({
      kind: 'key-down',
      key: event.key,
      code: event.code,
      modifiers: modifiersOf(event),
    });
  };

  const handleKeyUp = (event: React.KeyboardEvent<HTMLDivElement>) => {
    event.preventDefault();
    void send({
      kind: 'key-up',
      key: event.key,
      code: event.code,
      modifiers: modifiersOf(event),
    });
  };

  const [drafting, setDrafting] = useState(false);

  /**
   * Turns what has been recorded into a draft test.
   *
   * Available while the session is still live: someone who has finished the journey they cared about
   * should not have to stop the browser to find out what the platform made of it. Nothing is
   * created — the draft opens in the studio, where a person reviews it and decides.
   */
  const draft = async () => {
    if (!onDraft) {
      return;
    }
    setDrafting(true);
    try {
      onDraft(await client.recordingDraft(session.id));
    } catch (err) {
      onError(err instanceof Error ? err.message : 'The recording could not be turned into a test.');
    } finally {
      setDrafting(false);
    }
  };

  const stop = async () => {
    try {
      const stopped = await client.stopRecording(session.id);
      setStatus(stopped.status);
      onSessionChange(stopped);
    } catch (err) {
      onError(err instanceof Error ? err.message : 'The recording session could not be stopped.');
    }
  };

  // ── Render ───────────────────────────────────────────────────────────

  if (unbridged) {
    return (
      <div className="recorder-panel" role="region" aria-label="Recording session">
        <div className="recorder-pending">
          <AlertTriangle size={16} aria-hidden />
          <div>
            <strong>No recording browser is available on this deployment.</strong>
            <p className="muted">
              The session was created and audited, but nothing will stream until a browser bridge is
              configured. This deployment reports its bridge as <code>{session.bridge}</code>.
            </p>
          </div>
        </div>
        <button className="btn btn-quiet" onClick={onClose}>
          Close
        </button>
      </div>
    );
  }

  return (
    <div className="recorder-panel" role="region" aria-label="Recording session">
      <header className="recorder-panel-header">
        <div>
          <h3>
            <MonitorPlay size={15} aria-hidden /> Recording {session.application} /{' '}
            {session.environment}
          </h3>
          <p className="muted">
            {session.target} · <code>{session.id.slice(0, 8)}</code> · {session.bridge}
          </p>
        </div>
        <div className="recorder-panel-actions">
          <span className={`recorder-state recorder-state-${status.toLowerCase()}`} role="status">
            {status}
          </span>
          <button
            className="btn btn-quiet"
            onClick={() => void send({ kind: 'navigate-back' })}
            disabled={!live}
            title="Back"
          >
            <Undo2 size={14} aria-hidden /> Back
          </button>
          <button
            className="btn btn-quiet"
            onClick={() => void send({ kind: 'reload' })}
            disabled={!live}
            title="Reload"
          >
            <RotateCcw size={14} aria-hidden /> Reload
          </button>
          <button className="btn btn-quiet" onClick={() => void stop()} disabled={terminal}>
            <Square size={14} aria-hidden /> Stop
          </button>
          {onDraft && (
            <button
              className="btn"
              onClick={() => void draft()}
              disabled={drafting || captures.length === 0}
              title={
                captures.length === 0
                  ? 'Nothing has been captured yet. Interact with the application first.'
                  : undefined
              }
            >
              <Wand2 size={14} aria-hidden />{' '}
              {drafting ? 'Building the draft…' : 'Turn this into a test'}
            </button>
          )}
          {terminal && (
            <button className="btn btn-quiet" onClick={onClose}>
              Close
            </button>
          )}
        </div>
      </header>

      {failure && (
        <p className="recorder-failure" role="alert">
          {failure}
        </p>
      )}

      <div
        ref={surfaceRef}
        className="recorder-surface"
        style={{ aspectRatio: `${frameSize.width} / ${frameSize.height}` }}
        tabIndex={0}
        role="application"
        aria-label="Recorded browser surface. Click to interact, then type."
        onPointerMove={handlePointerMove}
        onPointerDown={handlePointerDown}
        onPointerUp={handlePointerUp}
        onKeyDown={handleKeyDown}
        onKeyUp={handleKeyUp}
        onContextMenu={(event) => event.preventDefault()}
      >
        {frame ? (
          <img src={`data:image/jpeg;base64,${frame}`} alt="" draggable={false} />
        ) : (
          <p className="recorder-waiting muted">Waiting for the first frame…</p>
        )}
      </div>

      <div className="recorder-evidence">
        <section>
          <h4>
            <ShieldAlert size={14} aria-hidden /> Refused destinations ({blocked.length})
          </h4>
          {blocked.length === 0 ? (
            <p className="muted">Nothing has been refused. Every request stayed inside the allowlist.</p>
          ) : (
            <ul>
              {blocked.map((entry) => (
                <li key={entry.sequence}>
                  <code>{entry.destination}</code>
                  <span className="muted">
                    {entry.kind.toLowerCase().replace(/_/g, ' ')} — {entry.reason}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section>
          <h4>Captured requests ({captures.length})</h4>
          {captures.length === 0 ? (
            <p className="muted">
              Nothing captured yet. Static assets are deliberately skipped — only documents and API
              calls become journey steps.
            </p>
          ) : (
            <ul>
              {captures.map((entry) => (
                <li key={entry.sequence}>
                  <code>
                    {entry.method} {entry.url}
                  </code>
                  {entry.findingCount > 0 && (
                    <span className="muted">
                      {entry.findingCount} item{entry.findingCount === 1 ? '' : 's'} removed by the
                      sanitizer
                    </span>
                  )}
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </div>
  );
}

import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RecorderPanel } from './RecorderPanel';
import type {
  V2Client,
  RecorderInput,
  RecorderLiveUpdate,
  RecordingSession,
} from './api';

const LIVE_SESSION: RecordingSession = {
  id: '11111111-2222-3333-4444-555555555555',
  teamId: 'team-1',
  application: 'proposal',
  environment: 'performance',
  target: 'https://proposal.perf.internal',
  status: 'LIVE',
  browserUrl: 'https://proposal.perf.internal',
  viewportWidth: 1280,
  viewportHeight: 800,
  bridge: 'local-chrome (Google Chrome)',
  failureCode: null,
  failureReason: null,
  capturedCount: 0,
  blockedCount: 0,
  events: [],
};

function emptyUpdate(overrides: Partial<RecorderLiveUpdate> = {}): RecorderLiveUpdate {
  return {
    status: 'LIVE',
    viewportWidth: 1280,
    viewportHeight: 800,
    frame: null,
    blocked: [],
    captures: [],
    failureCode: null,
    failureReason: null,
    ...overrides,
  };
}

function stubClient(overrides: Partial<V2Client> = {}) {
  return {
    pollRecording: vi.fn().mockResolvedValue(emptyUpdate()),
    sendRecorderInput: vi.fn().mockResolvedValue(undefined),
    stopRecording: vi.fn().mockResolvedValue({ ...LIVE_SESSION, status: 'CANCELLED' }),
    recordingDraft: vi.fn().mockResolvedValue({
      sourceType: 'har',
      scenarios: [],
      findings: [],
      correlationSuggestions: [],
    }),
    ...overrides,
  } as unknown as V2Client;
}

function renderPanel(client: V2Client, session = LIVE_SESSION) {
  const onError = vi.fn();
  const onClose = vi.fn();
  const onSessionChange = vi.fn();
  const onDraft = vi.fn();
  render(
    <RecorderPanel
      client={client}
      session={session}
      onSessionChange={onSessionChange}
      onClose={onClose}
      onError={onError}
      onDraft={onDraft}
    />,
  );
  return { onError, onClose, onSessionChange, onDraft };
}

const CAPTURE = {
  sequence: 1,
  method: 'GET',
  url: 'https://app.internal/api/orders',
  requestContentType: null,
  responseContentType: 'application/json',
  findingCount: 0,
  capturedAt: '2026-08-06T10:00:00Z',
};

describe('RecorderPanel', () => {
  it('renders the streamed frame', async () => {
    const client = stubClient({
      pollRecording: vi.fn().mockResolvedValue(
        emptyUpdate({
          frame: {
            sequence: 1,
            imageBase64: 'QUJD',
            width: 1280,
            height: 800,
            capturedAt: '2026-08-05T10:00:00Z',
          },
        }),
      ) as V2Client['pollRecording'],
    });

    renderPanel(client);

    await waitFor(() => {
      const image = document.querySelector('.recorder-surface img');
      expect(image?.getAttribute('src')).toBe('data:image/jpeg;base64,QUJD');
    });
  });

  it('shows refused destinations and captured requests once when polls replay evidence', async () => {
    const client = stubClient({
      pollRecording: vi.fn().mockResolvedValue(
        emptyUpdate({
          blocked: [
            {
              sequence: 1,
              destination: 'https://ads.example/pixel',
              kind: 'SUBRESOURCE',
              code: 'recorder.destination.outsideAllowlist',
              reason: 'Destination origin https://ads.example is not in this allowlist.',
              blockedAt: '2026-08-05T10:00:00Z',
            },
          ],
          captures: [CAPTURE],
        }),
      ) as V2Client['pollRecording'],
    });

    renderPanel(client);

    await waitFor(() => expect(vi.mocked(client.pollRecording).mock.calls.length).toBeGreaterThanOrEqual(3), { timeout: 2000 });
    expect(await screen.findByText('https://ads.example/pixel')).toBeInTheDocument();
    expect(await screen.findByText(/is not in this allowlist/)).toBeInTheDocument();
    expect(screen.getAllByText(`${CAPTURE.method} ${CAPTURE.url}`)).toHaveLength(1);
  });

  it('scales pointer coordinates into the browser viewport, not the rendered image', async () => {
    const send = vi.fn().mockResolvedValue(undefined);
    const client = stubClient({ sendRecorderInput: send as V2Client['sendRecorderInput'] });
    renderPanel(client);

    const surface = screen.getByRole('application');
    // jsdom gives every element a zero-size box, so the ratio is stated explicitly here. The
    // property under test is that the click is *converted*, not sent in rendered pixels.
    surface.getBoundingClientRect = () =>
      ({ left: 0, top: 0, width: 640, height: 400, right: 640, bottom: 400, x: 0, y: 0 }) as DOMRect;

    await userEvent.pointer({ target: surface, coords: { clientX: 320, clientY: 200 }, keys: '[MouseLeft]' });

    await waitFor(() => expect(send).toHaveBeenCalled());
    const pressed = send.mock.calls
      .map((call) => call[1] as RecorderInput)
      .find((input) => input.kind === 'mouse-pressed');
    expect(pressed).toBeDefined();
    // Half of a 640x400 rendering is the middle of the 1280x800 browser surface.
    expect(pressed?.x).toBeCloseTo(640);
    expect(pressed?.y).toBeCloseTo(400);
  });

  it('sends keystrokes to the page rather than letting the browser handle them', async () => {
    const send = vi.fn().mockResolvedValue(undefined);
    const client = stubClient({ sendRecorderInput: send as V2Client['sendRecorderInput'] });
    renderPanel(client);

    const surface = screen.getByRole('application');
    surface.focus();
    await userEvent.keyboard('{Enter}');

    await waitFor(() => {
      const keys = send.mock.calls.map((call) => call[1] as RecorderInput);
      expect(keys.some((input) => input.kind === 'key-down' && input.key === 'Enter')).toBe(true);
    });
  });

  it('explains a session that has no browser instead of waiting forever', () => {
    const client = stubClient();
    renderPanel(client, {
      ...LIVE_SESSION,
      status: 'PENDING_BROWSER_BRIDGE',
      browserUrl: null,
      bridge: 'none (the local recorder browser is not enabled on this deployment)',
    });

    expect(screen.getByText(/No recording browser is available/)).toBeInTheDocument();
    expect(screen.getByText(/not enabled on this deployment/)).toBeInTheDocument();
    expect(client.pollRecording).not.toHaveBeenCalled();
  });

  it('stops the session and reports the terminal state', async () => {
    const client = stubClient();
    const { onSessionChange } = renderPanel(client);

    await userEvent.click(screen.getByRole('button', { name: /stop/i }));

    await waitFor(() => expect(client.stopRecording).toHaveBeenCalledWith(LIVE_SESSION.id));
    await waitFor(() =>
      expect(onSessionChange).toHaveBeenCalledWith(
        expect.objectContaining({ status: 'CANCELLED' }),
      ),
    );
  });

  it('does not send input once the session is no longer live', async () => {
    const send = vi.fn().mockResolvedValue(undefined);
    const client = stubClient({
      sendRecorderInput: send as V2Client['sendRecorderInput'],
      pollRecording: vi.fn().mockResolvedValue(
        emptyUpdate({ status: 'EXPIRED', failureReason: 'The session was idle.' }),
      ) as V2Client['pollRecording'],
    });
    renderPanel(client);

    expect(await screen.findByText('The session was idle.')).toBeInTheDocument();

    const surface = screen.getByRole('application');
    surface.focus();
    await userEvent.keyboard('a');

    expect(send).not.toHaveBeenCalled();
  });

  it('cannot be turned into a test before anything has been captured', async () => {
    const client = stubClient();
    renderPanel(client);

    const button = await screen.findByRole('button', { name: /turn this into a test/i });
    expect(button).toBeDisabled();
    expect(client.recordingDraft).not.toHaveBeenCalled();
  });

  it('hands the drafted journeys up once something has been captured', async () => {
    const client = stubClient({
      pollRecording: vi.fn().mockResolvedValue(
        emptyUpdate({ captures: [CAPTURE] }),
      ) as V2Client['pollRecording'],
      recordingDraft: vi.fn().mockResolvedValue({
        sourceType: 'har',
        scenarios: [{ name: 'orders', weight: 100, steps: [] }],
        findings: [],
        correlationSuggestions: [],
      }) as unknown as V2Client['recordingDraft'],
    });
    const { onDraft } = renderPanel(client);

    const button = await screen.findByRole('button', { name: /turn this into a test/i });
    await waitFor(() => expect(button).toBeEnabled());
    await userEvent.click(button);

    await waitFor(() => expect(client.recordingDraft).toHaveBeenCalledWith(LIVE_SESSION.id));
    await waitFor(() =>
      expect(onDraft).toHaveBeenCalledWith(
        expect.objectContaining({ scenarios: [expect.objectContaining({ name: 'orders' })] }),
      ),
    );
  });

  it('a recording can still be drafted after it has stopped', async () => {
    const client = stubClient({
      pollRecording: vi.fn().mockResolvedValue(
        emptyUpdate({ status: 'CANCELLED', captures: [CAPTURE] }),
      ) as V2Client['pollRecording'],
    });
    renderPanel(client);

    const button = await screen.findByRole('button', { name: /turn this into a test/i });
    await waitFor(() => expect(button).toBeEnabled());
  });

  /**
   * The bug this exists for: on failure the loop rescheduled at 120ms unconditionally, so a session
   * the server had released produced roughly eight requests a second, forever, each one raising the
   * error banner again.
   */
  it('stops polling once the server has released the session', async () => {
    const gone = Object.assign(new Error('Request failed with status code 404'), {
      response: { status: 404 },
    });
    const poll = vi.fn().mockRejectedValue(gone);
    const client = stubClient({ pollRecording: poll as V2Client['pollRecording'] });
    const { onError } = renderPanel(client);

    await waitFor(() => expect(poll).toHaveBeenCalled());
    expect(await screen.findByText(/no longer available on the server/)).toBeInTheDocument();

    const callsAfterGiveUp = poll.mock.calls.length;
    await new Promise((resolve) => setTimeout(resolve, 400));

    expect(poll.mock.calls.length).toBe(callsAfterGiveUp);
    // A session that simply ended is not an error worth a banner.
    expect(onError).not.toHaveBeenCalled();
  });

  it('gives up after repeated failures rather than retrying forever', async () => {
    const poll = vi.fn().mockRejectedValue(new Error('network down'));
    const client = stubClient({ pollRecording: poll as V2Client['pollRecording'] });
    const { onError } = renderPanel(client);

    await waitFor(() => expect(onError).toHaveBeenCalled(), { timeout: 5000 });
    expect(await screen.findByText(/Lost contact with the recording session/)).toBeInTheDocument();

    const callsAfterGiveUp = poll.mock.calls.length;
    await new Promise((resolve) => setTimeout(resolve, 500));

    expect(poll.mock.calls.length).toBe(callsAfterGiveUp);
    // Bounded: five attempts, not five hundred.
    expect(callsAfterGiveUp).toBeLessThanOrEqual(5);
  });

  /**
   * The vocabulary and the CDP mapping for wheel events both existed and were tested; the panel
   * simply never sent one, so the recorded page could not be scrolled at all.
   */
  it('forwards the wheel to the recorded page, in browser coordinates', async () => {
    const send = vi.fn().mockResolvedValue(undefined);
    const client = stubClient({ sendRecorderInput: send as V2Client['sendRecorderInput'] });
    renderPanel(client);

    const surface = screen.getByRole('application');
    surface.getBoundingClientRect = () =>
      ({ left: 0, top: 0, width: 640, height: 400, right: 640, bottom: 400, x: 0, y: 0 }) as DOMRect;

    surface.dispatchEvent(
      new WheelEvent('wheel', {
        clientX: 320,
        clientY: 200,
        deltaY: 120,
        deltaMode: 0,
        bubbles: true,
        cancelable: true,
      }),
    );

    await waitFor(() => {
      const wheel = send.mock.calls
        .map((call) => call[1] as RecorderInput)
        .find((input) => input.kind === 'mouse-wheel');
      expect(wheel).toBeDefined();
      expect(wheel?.deltaY).toBe(120);
      // Half of a 640x400 rendering is the middle of the 1280x800 browser surface.
      expect(wheel?.x).toBeCloseTo(640);
      expect(wheel?.y).toBeCloseTo(400);
    });
  });

  it('scrolls the recorded page rather than the console behind it', async () => {
    const client = stubClient();
    renderPanel(client);

    const surface = screen.getByRole('application');
    surface.getBoundingClientRect = () =>
      ({ left: 0, top: 0, width: 640, height: 400, right: 640, bottom: 400, x: 0, y: 0 }) as DOMRect;

    const event = new WheelEvent('wheel', { deltaY: 120, bubbles: true, cancelable: true });
    surface.dispatchEvent(event);

    // Not merely "a handler ran": an unprevented wheel scrolls the page the surface sits on, and
    // React's own onWheel is passive, where preventDefault silently does nothing.
    await waitFor(() => expect(event.defaultPrevented).toBe(true));
  });

  it('converts a wheel that reports lines rather than pixels', async () => {
    const send = vi.fn().mockResolvedValue(undefined);
    const client = stubClient({ sendRecorderInput: send as V2Client['sendRecorderInput'] });
    renderPanel(client);

    const surface = screen.getByRole('application');
    surface.getBoundingClientRect = () =>
      ({ left: 0, top: 0, width: 640, height: 400, right: 640, bottom: 400, x: 0, y: 0 }) as DOMRect;

    surface.dispatchEvent(
      new WheelEvent('wheel', { deltaY: 3, deltaMode: 1, bubbles: true, cancelable: true }),
    );

    await waitFor(() => {
      const wheel = send.mock.calls
        .map((call) => call[1] as RecorderInput)
        .find((input) => input.kind === 'mouse-wheel');
      expect(wheel?.deltaY).toBe(48);
    });
  });
});

package com.perfplatform.runner.chrome;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * A minimal DevTools Protocol client: JSON-RPC over one WebSocket.
 *
 * <p>The WebSocket is {@link java.net.http.WebSocket} from the JDK, so the bridge adds no
 * dependency. CDP is a small, stable request/response-plus-events protocol and the subset this
 * adapter needs is a few dozen lines; a full CDP library would bring a large generated surface for
 * no benefit, and a smaller surface here is a security property rather than an aesthetic one.
 *
 * <p><b>Not user-reachable.</b> Nothing above this class can name a method — see
 * {@link com.perfplatform.domain.recorder.RecorderInputKind}. The only callers are this package's
 * own translation of the fixed input vocabulary.
 *
 * <p>Frames can arrive split across several WebSocket messages, so text is accumulated until the
 * {@code last} flag; screencast frames are large enough that this is the normal case, not an edge
 * one.
 */
final class ChromeDevToolsClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChromeDevToolsClient.class);

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<CdpEvent>> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<String>> closeListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Duration commandTimeout;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "recorder-cdp-sender");
        thread.setDaemon(true);
        return thread;
    });

    private volatile WebSocket socket;

    /** One protocol event, already split into the parts this adapter cares about. */
    record CdpEvent(String method, JsonNode params, String sessionId) {
    }

    static final class CdpException extends RuntimeException {
        CdpException(String message) {
            super(message);
        }

        CdpException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private ChromeDevToolsClient(Duration commandTimeout) {
        this.commandTimeout = Objects.requireNonNull(commandTimeout, "commandTimeout");
    }

    static ChromeDevToolsClient connect(URI endpoint, Duration connectTimeout,
            Duration commandTimeout) {

        ChromeDevToolsClient client = new ChromeDevToolsClient(commandTimeout);
        try {
            client.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .connectTimeout(connectTimeout)
                    .buildAsync(endpoint, client.new Adapter())
                    .get(connectTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CdpException("Interrupted while connecting to the recording browser", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new CdpException("Could not connect to the recording browser at " + endpoint, e);
        }
        return client;
    }

    void onEvent(Consumer<CdpEvent> listener) {
        listeners.add(listener);
    }

    void onClose(Consumer<String> listener) {
        closeListeners.add(listener);
    }

    JsonNode send(String method, Map<String, ?> params) {
        return send(method, params, null);
    }

    /**
     * Sends a command and waits for its reply.
     *
     * <p><b>Never call this from a protocol event handler.</b> The reply is delivered by the
     * WebSocket listener thread, so waiting for it on that same thread waits forever. Handlers that
     * need a result hand off to an executor; handlers that only need to issue a command use
     * {@link #fire}.
     *
     * @param sessionId the flattened target session, or null for a browser-level command
     * @throws CdpException if the browser reports an error, times out or has gone away
     */
    JsonNode send(String method, Map<String, ?> params, String sessionId) {
        long id = nextId.getAndIncrement();
        String payload = message(id, method, params, sessionId);

        CompletableFuture<JsonNode> reply = new CompletableFuture<>();
        pending.put(id, reply);
        try {
            enqueue(payload);
            return reply.get(commandTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CdpException("Interrupted waiting for " + method, e);
        } catch (TimeoutException e) {
            throw new CdpException("The recording browser did not answer " + method + " in time", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw cause instanceof CdpException cdp ? cdp
                    : new CdpException(method + " failed: " + cause.getMessage(), cause);
        } finally {
            pending.remove(id);
        }
    }

    /**
     * Sends a command without waiting for its reply.
     *
     * <p>Safe to call from an event handler, which is the point: routing decisions and screencast
     * acknowledgements are issued from the listener thread and must not block it. Their replies
     * carry nothing this adapter acts on.
     */
    void fire(String method, Map<String, ?> params, String sessionId) {
        try {
            enqueue(message(nextId.getAndIncrement(), method, params, sessionId));
        } catch (RuntimeException e) {
            log.debug("Recorder browser did not accept {}: {}", method, e.getMessage());
        }
    }

    private String message(long id, String method, Map<String, ?> params, String sessionId) {
        ObjectNode message = json.createObjectNode();
        message.put("id", id);
        message.put("method", method);
        message.set("params", json.valueToTree(params == null ? Map.of() : params));
        if (sessionId != null) {
            message.put("sessionId", sessionId);
        }
        return message.toString();
    }

    /**
     * Queues one outbound message.
     *
     * <p>Serialized through a single thread because {@link WebSocket#sendText} may not be called
     * again until the previous send has completed. Two threads issuing commands concurrently —
     * a user's input and a page's routing decision, which is the normal case — would otherwise
     * fail with {@code IllegalStateException} rather than merely interleaving.
     */
    private void enqueue(String payload) {
        WebSocket active = socket;
        if (closed.get() || active == null) {
            throw new CdpException("The recording browser connection is closed");
        }
        sender.execute(() -> {
            WebSocket current = socket;
            if (current == null) {
                return;
            }
            try {
                current.sendText(payload, true).get(commandTimeout.toMillis(),
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                log.debug("Recorder browser send failed: {}", e.getMessage());
            }
        });
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        WebSocket active = socket;
        socket = null;
        sender.shutdownNow();
        failPending("The recording browser connection was closed");
        if (active != null) {
            try {
                active.sendClose(WebSocket.NORMAL_CLOSURE, "recorder session ended");
            } catch (RuntimeException e) {
                log.debug("Recorder browser socket did not close cleanly: {}", e.getMessage());
            }
            active.abort();
        }
    }

    boolean isClosed() {
        return closed.get();
    }

    private void failPending(String reason) {
        pending.values().forEach(future -> future.completeExceptionally(new CdpException(reason)));
        pending.clear();
    }

    private void dispatch(String payload) {
        JsonNode node;
        try {
            node = json.readTree(payload);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.debug("Unreadable message from the recording browser");
            return;
        }

        if (node.hasNonNull("id")) {
            CompletableFuture<JsonNode> reply = pending.get(node.get("id").asLong());
            if (reply == null) {
                return;
            }
            if (node.hasNonNull("error")) {
                reply.completeExceptionally(new CdpException(
                        node.get("error").path("message").asText("unknown protocol error")));
            } else {
                reply.complete(node.path("result"));
            }
            return;
        }

        String method = node.path("method").asText(null);
        if (method == null) {
            return;
        }
        CdpEvent event = new CdpEvent(method, node.path("params"),
                node.hasNonNull("sessionId") ? node.get("sessionId").asText() : null);
        for (Consumer<CdpEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                // One misbehaving handler must not stop the others, and must not kill the socket.
                log.warn("Recorder event handler failed for {}", method, e);
            }
        }
    }

    private void notifyClosed(String reason) {
        if (closed.get()) {
            return;
        }
        failPending(reason);
        for (Consumer<String> listener : closeListeners) {
            try {
                listener.accept(reason);
            } catch (RuntimeException e) {
                log.warn("Recorder close handler failed", e);
            }
        }
    }

    /** Reassembles fragmented text frames before handing whole messages to {@link #dispatch}. */
    private final class Adapter implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String payload = buffer.toString();
                buffer.setLength(0);
                dispatch(payload);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            notifyClosed(reason == null || reason.isBlank()
                    ? "The recording browser closed the connection" : reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            notifyClosed("The recording browser connection failed: " + error.getMessage());
        }
    }
}

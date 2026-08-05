package com.perfplatform.domain.recorder;

import java.util.Objects;

/**
 * One brokered interaction with the recorded browser.
 *
 * <p>Everything here is untrusted: it arrives from a browser the platform does not control. The
 * record validates shape only — that a mouse event has usable coordinates, that a key name is a
 * plausible length, that a navigation names an absolute URL. Whether the navigation is <em>allowed</em>
 * is a route-guard decision made against the session's frozen policy
 * ({@link RecorderRouteGuard}), not something this type can answer.
 *
 * @param kind        which of the ten permitted interactions this is
 * @param x           viewport X in CSS pixels, for mouse events
 * @param y           viewport Y in CSS pixels, for mouse events
 * @param button      pressed button, for mouse events
 * @param clickCount  1 for a click, 2 for a double click
 * @param deltaX      horizontal wheel delta
 * @param deltaY      vertical wheel delta
 * @param key         the {@code KeyboardEvent.key} value, for key events
 * @param code        the {@code KeyboardEvent.code} value, for key events
 * @param text        literal text to insert, for {@link RecorderInputKind#TEXT}
 * @param modifiers   Alt=1, Ctrl=2, Meta=4, Shift=8, combined
 * @param url         destination, for {@link RecorderInputKind#NAVIGATE}
 */
public record RecorderInputEvent(
        RecorderInputKind kind,
        double x,
        double y,
        RecorderMouseButton button,
        int clickCount,
        double deltaX,
        double deltaY,
        String key,
        String code,
        String text,
        int modifiers,
        String url) {

    /** Long enough for a pasted field value, short enough not to be a body upload channel. */
    public static final int MAX_TEXT_LENGTH = 512;
    private static final int MAX_KEY_LENGTH = 32;
    private static final int MAX_MODIFIERS = 15;
    private static final int MAX_CLICK_COUNT = 3;
    private static final double MAX_WHEEL_DELTA = 10_000;

    public RecorderInputEvent {
        Objects.requireNonNull(kind, "kind");
        button = button == null ? RecorderMouseButton.NONE : button;

        if (modifiers < 0 || modifiers > MAX_MODIFIERS) {
            throw new IllegalArgumentException(
                    "Recorder input modifiers must be a 0-" + MAX_MODIFIERS + " bitmask.");
        }

        if (kind.isMouse()) {
            requireFinite(x, "x");
            requireFinite(y, "y");
            if (clickCount < 0 || clickCount > MAX_CLICK_COUNT) {
                throw new IllegalArgumentException(
                        "Recorder click count must be 0-" + MAX_CLICK_COUNT + ".");
            }
            if (kind == RecorderInputKind.MOUSE_WHEEL) {
                requireDelta(deltaX, "deltaX");
                requireDelta(deltaY, "deltaY");
            }
        }

        if (kind == RecorderInputKind.KEY_DOWN || kind == RecorderInputKind.KEY_UP) {
            key = bounded(key, "key", MAX_KEY_LENGTH, true);
            code = bounded(code, "code", MAX_KEY_LENGTH, false);
        } else {
            key = bounded(key, "key", MAX_KEY_LENGTH, false);
            code = bounded(code, "code", MAX_KEY_LENGTH, false);
        }

        if (kind == RecorderInputKind.TEXT) {
            if (text == null || text.isEmpty()) {
                throw new IllegalArgumentException("A TEXT recorder input must carry text.");
            }
            if (text.length() > MAX_TEXT_LENGTH) {
                throw new IllegalArgumentException("Recorder input text exceeds "
                        + MAX_TEXT_LENGTH + " characters.");
            }
        }

        if (kind == RecorderInputKind.NAVIGATE) {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("A NAVIGATE recorder input must carry a URL.");
            }
            url = url.trim();
            // Parses and rejects relative URLs; allowlist membership is the route guard's decision.
            RecorderOrigin.parseUri(url);
        }
    }

    public static RecorderInputEvent mouse(RecorderInputKind kind, double x, double y,
            RecorderMouseButton button, int clickCount, int modifiers) {
        return new RecorderInputEvent(kind, x, y, button, clickCount, 0, 0,
                null, null, null, modifiers, null);
    }

    public static RecorderInputEvent wheel(double x, double y, double deltaX, double deltaY,
            int modifiers) {
        return new RecorderInputEvent(RecorderInputKind.MOUSE_WHEEL, x, y,
                RecorderMouseButton.NONE, 0, deltaX, deltaY, null, null, null, modifiers, null);
    }

    public static RecorderInputEvent key(RecorderInputKind kind, String key, String code,
            int modifiers) {
        return new RecorderInputEvent(kind, 0, 0, RecorderMouseButton.NONE, 0, 0, 0,
                key, code, null, modifiers, null);
    }

    public static RecorderInputEvent text(String text) {
        return new RecorderInputEvent(RecorderInputKind.TEXT, 0, 0, RecorderMouseButton.NONE,
                0, 0, 0, null, null, text, 0, null);
    }

    public static RecorderInputEvent navigate(String url) {
        return new RecorderInputEvent(RecorderInputKind.NAVIGATE, 0, 0, RecorderMouseButton.NONE,
                0, 0, 0, null, null, null, 0, url);
    }

    public static RecorderInputEvent of(RecorderInputKind kind) {
        return new RecorderInputEvent(kind, 0, 0, RecorderMouseButton.NONE, 0, 0, 0,
                null, null, null, 0, null);
    }

    /**
     * Rejects a pointer outside the surface the client was shown.
     *
     * @throws IllegalArgumentException if this is a mouse event outside {@code viewport}
     */
    public RecorderInputEvent requireWithin(RecorderViewport viewport) {
        Objects.requireNonNull(viewport, "viewport");
        if (kind.isMouse() && !viewport.contains(x, y)) {
            throw new IllegalArgumentException("Recorder pointer (" + x + "," + y
                    + ") is outside the " + viewport.width() + "x" + viewport.height()
                    + " recorded viewport.");
        }
        return this;
    }

    /**
     * A description safe to place in an audit event.
     *
     * <p>Typed text and navigation targets are deliberately excluded: a recording session is where a
     * person signs in, so the literal keystrokes are credential material (§5).
     */
    public String auditSummary() {
        if (kind.isMouse()) {
            return kind + " button=" + button;
        }
        if (kind == RecorderInputKind.TEXT) {
            return "TEXT length=" + text.length();
        }
        if (kind == RecorderInputKind.KEY_DOWN || kind == RecorderInputKind.KEY_UP) {
            return kind + " code=" + (code == null ? "" : code);
        }
        return kind.toString();
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Recorder input " + field + " must be a finite number.");
        }
    }

    private static void requireDelta(double value, String field) {
        requireFinite(value, field);
        if (Math.abs(value) > MAX_WHEEL_DELTA) {
            throw new IllegalArgumentException(
                    "Recorder wheel " + field + " exceeds " + MAX_WHEEL_DELTA + ".");
        }
    }

    private static String bounded(String value, String field, int max, boolean required) {
        if (value == null || value.isEmpty()) {
            if (required) {
                throw new IllegalArgumentException("Recorder input " + field + " must not be blank.");
            }
            return null;
        }
        if (value.length() > max) {
            throw new IllegalArgumentException(
                    "Recorder input " + field + " exceeds " + max + " characters.");
        }
        return value;
    }
}

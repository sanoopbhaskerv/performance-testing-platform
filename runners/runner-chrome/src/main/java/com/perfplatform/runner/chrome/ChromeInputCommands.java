package com.perfplatform.runner.chrome;

import com.perfplatform.domain.recorder.RecorderInputEvent;
import com.perfplatform.domain.recorder.RecorderInputKind;
import com.perfplatform.domain.recorder.RecorderMouseButton;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Translates the brokered input vocabulary into DevTools commands.
 *
 * <p>This is the narrow waist of the bridge, and the reason it is a pure function with no socket
 * and no session: everything a user can cause the browser to do is visible in one file, and a test
 * can read the exact command that a click becomes. There is no branch here that takes a
 * caller-supplied method name.
 */
final class ChromeInputCommands {

    /**
     * Virtual key codes for the keys that do nothing in Chrome without one.
     *
     * <p>Printable characters work from {@code text} alone. These do not: an Enter delivered
     * without a key code is swallowed, which shows up as a recording where forms never submit.
     */
    private static final Map<String, Integer> VIRTUAL_KEY_CODES = Map.ofEntries(
            Map.entry("Backspace", 8),
            Map.entry("Tab", 9),
            Map.entry("Enter", 13),
            Map.entry("Shift", 16),
            Map.entry("Control", 17),
            Map.entry("Alt", 18),
            Map.entry("Escape", 27),
            Map.entry(" ", 32),
            Map.entry("PageUp", 33),
            Map.entry("PageDown", 34),
            Map.entry("End", 35),
            Map.entry("Home", 36),
            Map.entry("ArrowLeft", 37),
            Map.entry("ArrowUp", 38),
            Map.entry("ArrowRight", 39),
            Map.entry("ArrowDown", 40),
            Map.entry("Delete", 46));

    private ChromeInputCommands() {
    }

    /** One protocol call: a method and its parameters. */
    record CdpCall(String method, Map<String, Object> params) {
    }

    /**
     * @return the call this event becomes, or empty for {@link RecorderInputKind#NAVIGATE_BACK},
     *         which needs the target's navigation history and is resolved by the caller
     */
    static Optional<CdpCall> translate(RecorderInputEvent event) {
        return switch (event.kind()) {
            case MOUSE_MOVED, MOUSE_PRESSED, MOUSE_RELEASED, MOUSE_WHEEL -> Optional.of(mouse(event));
            case KEY_DOWN, KEY_UP -> Optional.of(key(event));
            case TEXT -> Optional.of(new CdpCall("Input.insertText",
                    Map.of("text", event.text())));
            case NAVIGATE -> Optional.of(new CdpCall("Page.navigate",
                    Map.of("url", event.url())));
            case RELOAD -> Optional.of(new CdpCall("Page.reload",
                    Map.of("ignoreCache", Boolean.FALSE)));
            case NAVIGATE_BACK -> Optional.empty();
        };
    }

    private static CdpCall mouse(RecorderInputEvent event) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", switch (event.kind()) {
            case MOUSE_PRESSED -> "mousePressed";
            case MOUSE_RELEASED -> "mouseReleased";
            case MOUSE_WHEEL -> "mouseWheel";
            default -> "mouseMoved";
        });
        params.put("x", event.x());
        params.put("y", event.y());
        params.put("modifiers", event.modifiers());
        params.put("button", button(event.button()));

        if (event.kind() == RecorderInputKind.MOUSE_WHEEL) {
            params.put("deltaX", event.deltaX());
            params.put("deltaY", event.deltaY());
        }
        if (event.kind() == RecorderInputKind.MOUSE_PRESSED
                || event.kind() == RecorderInputKind.MOUSE_RELEASED) {
            // Chrome ignores a press whose clickCount is zero, so a click with no count supplied
            // would look delivered and do nothing.
            params.put("clickCount", Math.max(1, event.clickCount()));
        }
        return new CdpCall("Input.dispatchMouseEvent", params);
    }

    private static CdpCall key(RecorderInputEvent event) {
        Map<String, Object> params = new LinkedHashMap<>();
        boolean down = event.kind() == RecorderInputKind.KEY_DOWN;
        String key = event.key();

        params.put("type", down && isPrintable(key) ? "keyDown" : down ? "rawKeyDown" : "keyUp");
        params.put("key", key);
        params.put("modifiers", event.modifiers());
        if (event.code() != null) {
            params.put("code", event.code());
        }
        if (down && isPrintable(key)) {
            params.put("text", key);
        }
        Integer virtualKey = VIRTUAL_KEY_CODES.get(key);
        if (virtualKey != null) {
            params.put("windowsVirtualKeyCode", virtualKey);
            params.put("nativeVirtualKeyCode", virtualKey);
        }
        return new CdpCall("Input.dispatchKeyEvent", params);
    }

    /**
     * A printable key is one whose {@code key} value <em>is</em> the character it produces.
     *
     * <p>{@code "a"} and {@code " "} are printable; {@code "Enter"} and {@code "ArrowLeft"} name a
     * key rather than a character. Counting code points rather than chars keeps a single
     * astral-plane character on the printable side.
     */
    private static boolean isPrintable(String key) {
        return key != null && !key.isEmpty() && key.codePointCount(0, key.length()) == 1;
    }

    private static String button(RecorderMouseButton button) {
        return button == null ? "none" : button.name().toLowerCase(Locale.ROOT);
    }
}

package com.perfplatform.domain.recorder;

import com.perfplatform.domain.spec.support.SpecEnums;

/**
 * The complete vocabulary a recorder client may send.
 *
 * <p>This enum is the whole reason the bridge exists. The DevTools Protocol can read and write
 * files, evaluate arbitrary JavaScript in any origin, drive other targets and inspect browser
 * internals; the recorder security review (§4.4) requires that none of that reaches the user. A
 * client cannot name a CDP method — it can only name one of these, and the adapter decides which
 * commands that becomes.
 */
public enum RecorderInputKind {
    MOUSE_MOVED,
    MOUSE_PRESSED,
    MOUSE_RELEASED,
    MOUSE_WHEEL,
    KEY_DOWN,
    KEY_UP,
    TEXT,
    NAVIGATE,
    RELOAD,
    NAVIGATE_BACK;

    public static RecorderInputKind from(String raw) {
        return SpecEnums.parse(RecorderInputKind.class, raw, "recorder input kind");
    }

    public boolean isMouse() {
        return this == MOUSE_MOVED || this == MOUSE_PRESSED || this == MOUSE_RELEASED
                || this == MOUSE_WHEEL;
    }

    public boolean isKeyboard() {
        return this == KEY_DOWN || this == KEY_UP || this == TEXT;
    }
}

package com.perfplatform.domain.recorder;

import com.perfplatform.domain.spec.support.SpecEnums;

/** The mouse buttons a recorder client may report. */
public enum RecorderMouseButton {
    NONE,
    LEFT,
    MIDDLE,
    RIGHT;

    public static RecorderMouseButton from(String raw) {
        return raw == null || raw.isBlank()
                ? NONE
                : SpecEnums.parse(RecorderMouseButton.class, raw, "recorder mouse button");
    }
}

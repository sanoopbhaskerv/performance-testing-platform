package com.perfplatform.api.v2.dto;

import com.perfplatform.domain.recorder.RecorderInputEvent;
import com.perfplatform.domain.recorder.RecorderInputKind;
import com.perfplatform.domain.recorder.RecorderMouseButton;
import jakarta.validation.constraints.NotBlank;

/**
 * One interaction with the recorded browser.
 *
 * <p>{@code kind} is parsed into {@link RecorderInputKind}, which is a closed enum. A caller that
 * sends anything else gets a validation failure naming the permitted values — there is no path from
 * this field to a DevTools method name, which is the whole point of the bridge
 * (recorder security review §4.4).
 */
public record RecorderInputRequest(
        @NotBlank String kind,
        Double x,
        Double y,
        String button,
        Integer clickCount,
        Double deltaX,
        Double deltaY,
        String key,
        String code,
        String text,
        Integer modifiers,
        String url) {

    /**
     * @throws IllegalArgumentException if the kind is unknown or the event is malformed
     */
    public RecorderInputEvent toDomain() {
        return new RecorderInputEvent(
                RecorderInputKind.from(kind),
                x == null ? 0 : x,
                y == null ? 0 : y,
                RecorderMouseButton.from(button),
                clickCount == null ? 0 : clickCount,
                deltaX == null ? 0 : deltaX,
                deltaY == null ? 0 : deltaY,
                key,
                code,
                text,
                modifiers == null ? 0 : modifiers,
                url);
    }
}

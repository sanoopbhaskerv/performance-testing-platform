package com.perfplatform.domain.recorder;

/**
 * The size of the recorded browser surface, in CSS pixels.
 *
 * <p>Bounded because the viewport is user-supplied and decides how much work the browser does per
 * screencast frame. An unbounded viewport is a cheap way to make a recorder pod allocate far more
 * memory than its limit allows.
 */
public record RecorderViewport(int width, int height) {

    public static final int MIN_DIMENSION = 320;
    public static final int MAX_DIMENSION = 2_560;

    public static final RecorderViewport DEFAULT = new RecorderViewport(1_280, 800);

    public RecorderViewport {
        width = require(width, "width");
        height = require(height, "height");
    }

    /** Clamps rather than rejects, for callers echoing a browser window size they do not control. */
    public static RecorderViewport clamped(int width, int height) {
        return new RecorderViewport(clamp(width), clamp(height));
    }

    public boolean contains(double x, double y) {
        return x >= 0 && y >= 0 && x <= width && y <= height;
    }

    private static int require(int value, String field) {
        if (value < MIN_DIMENSION || value > MAX_DIMENSION) {
            throw new IllegalArgumentException("Recorder viewport " + field + " " + value
                    + " is outside " + MIN_DIMENSION + "-" + MAX_DIMENSION + ".");
        }
        return value;
    }

    private static int clamp(int value) {
        return Math.max(MIN_DIMENSION, Math.min(MAX_DIMENSION, value));
    }
}

package com.perfplatform.engine.jmeter;

import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadStage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Turns a staged user profile into the layers JMeter's Ultimate Thread Group executes.
 *
 * <h2>Why this exists at all</h2>
 * A staged model is a piecewise-linear concurrency curve: 0 → 50 over a minute, hold 50 for five,
 * 50 → 0 over thirty seconds. Vanilla JMeter cannot express that. Its thread group has one ramp and
 * one duration, so the platform previously ran one thread group per stage, each starting from zero —
 * which meant concurrency <em>stepped</em> at every boundary: the previous stage's threads stopped
 * and the next stage's started. That is a different test from a smooth ramp, because connection
 * pools, autoscalers and circuit breakers respond to the transition and not only to the level.
 *
 * <p>The Ultimate Thread Group takes a set of independent thread groups, each with its own delay,
 * ramp, hold and shutdown, and its <b>concurrency is their sum</b>. Any piecewise-linear curve can
 * therefore be decomposed into layers, and this class does the decomposition.
 *
 * <h2>The decomposition</h2>
 * Walking the stages while carrying a stack of active layers:
 * <ul>
 *   <li><b>A rise</b> pushes a layer of the difference, ramping across that stage's whole duration —
 *       which is the glide.</li>
 *   <li><b>A fall</b> pops layers, newest first, until enough threads have been removed, splitting
 *       the top layer when it holds more than the fall needs. Newest-first is what keeps a
 *       spike-then-recover profile honest: the threads that came for the spike are the ones that
 *       leave.</li>
 *   <li><b>Whatever is still standing at the end</b> shuts down immediately. The run is over; a
 *       ramp-down after the last stage would be load the specification never asked for.</li>
 * </ul>
 *
 * <p>Verified against a real JMeter with the plugin installed rather than reasoned about: the
 * observed active-thread count over a run follows the intended curve. {@code ConcurrencyProfileTest}
 * pins the arithmetic by reconstructing the curve from the layers.
 */
final class ConcurrencyProfile {

    private ConcurrencyProfile() {
    }

    /**
     * One Ultimate Thread Group row.
     *
     * <p>Its threads start after {@code delaySeconds}, arrive linearly over {@code rampSeconds},
     * stay for {@code holdSeconds}, then leave linearly over {@code shutdownSeconds}.
     */
    record Layer(int threads, long delaySeconds, long rampSeconds, long holdSeconds,
            long shutdownSeconds) {

        Layer {
            if (threads <= 0) {
                throw new IllegalArgumentException("A layer must carry at least one thread");
            }
        }

        /** The same layer with fewer threads, for a fall that removes only part of it. */
        Layer withThreads(int fewer) {
            return new Layer(fewer, delaySeconds, rampSeconds, holdSeconds, shutdownSeconds);
        }
    }

    /** A layer that has been pushed but whose end is not yet known. */
    private record Pending(int threads, long delaySeconds, long rampSeconds) {
    }

    /**
     * Decomposes a staged profile into layers.
     *
     * <p>Stages with no duration are skipped rather than propagated: validation reports those as
     * their own finding, and this must not throw before that finding reaches the consumer.
     *
     * @return layers in the order they start, or empty when the profile never leaves zero
     */
    static List<Layer> layersFor(List<LoadStage> stages) {
        List<Layer> layers = new ArrayList<>();
        Deque<Pending> active = new ArrayDeque<>();

        long elapsed = 0;
        int level = 0;

        for (LoadStage stage : stages) {
            if (stage == null || stage.duration() == null || stage.duration().isNegative()) {
                continue;
            }
            long duration = stage.duration().toSeconds();
            int target = Math.max(0, stage.targetUsers());

            if (target > level) {
                active.push(new Pending(target - level, elapsed, duration));
            } else if (target < level) {
                close(active, layers, level - target, elapsed, duration);
            }
            // A stage that holds its level changes nothing: the layers already standing carry it,
            // and their hold time is computed from when they are eventually closed.

            level = target;
            elapsed += duration;
        }

        // Anything still standing ends with the run. Shutdown is immediate rather than ramped,
        // because a ramp-down here would extend the test past the shape the consumer described.
        close(active, layers, level, elapsed, 0);

        layers.sort((left, right) -> Long.compare(left.delaySeconds(), right.delaySeconds()));
        return List.copyOf(layers);
    }

    /**
     * Removes {@code threads} from the newest layers, closing each with its hold and shutdown.
     *
     * <p>Newest-first, and the top layer is split when it holds more than this fall removes. The
     * alternative — closing whole layers oldest-first — would produce the right total at every
     * instant and the wrong story: a spike's threads would outlive the spike.
     */
    private static void close(Deque<Pending> active, List<Layer> layers, int threads,
            long fallStart, long fallDuration) {

        int remaining = threads;
        while (remaining > 0 && !active.isEmpty()) {
            Pending pending = active.pop();
            int taken = Math.min(remaining, pending.threads());

            // Clamped: a fall cannot begin before the rise that produced these threads finished,
            // but a malformed profile must not produce a negative hold that JMeter would read as
            // something else entirely.
            long hold = Math.max(0, fallStart - (pending.delaySeconds() + pending.rampSeconds()));

            layers.add(new Layer(taken, pending.delaySeconds(), pending.rampSeconds(),
                    hold, fallDuration));

            if (taken < pending.threads()) {
                active.push(new Pending(pending.threads() - taken, pending.delaySeconds(),
                        pending.rampSeconds()));
            }
            remaining -= taken;
        }
    }

    /**
     * The concurrency these layers produce at a given second.
     *
     * <p>Here rather than in the test because it is the definition of what the layers mean, and a
     * test that carried its own definition would be checking itself. Used to pin the decomposition
     * against the profile it came from.
     */
    static int concurrencyAt(List<Layer> layers, long second) {
        int total = 0;
        for (Layer layer : layers) {
            long rampEnds = layer.delaySeconds() + layer.rampSeconds();
            long holdEnds = rampEnds + layer.holdSeconds();
            long shutdownEnds = holdEnds + layer.shutdownSeconds();

            if (second <= layer.delaySeconds()) {
                continue;
            }
            if (second >= shutdownEnds) {
                continue;
            }
            if (second < rampEnds) {
                total += (int) Math.round(
                        layer.threads() * (second - layer.delaySeconds())
                                / (double) Math.max(1, layer.rampSeconds()));
            } else if (second <= holdEnds || layer.shutdownSeconds() == 0) {
                total += layer.threads();
            } else {
                total += (int) Math.round(
                        layer.threads() * (shutdownEnds - second)
                                / (double) Math.max(1, layer.shutdownSeconds()));
            }
        }
        return total;
    }
}

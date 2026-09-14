package com.paddleshock.replay;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Ring buffer of {@link ReplaySample}s, recorded once per simulation tick during real gameplay
 * and capped to roughly the last {@value #REPLAY_WINDOW_SECONDS} seconds of wall-clock time -
 * not a fixed sample count, since tick/frame duration varies (single-player vs. AI, a host's own
 * tick loop, or a joiner's network-snapshot cadence all call {@link #record} at different rates).
 * Old samples are dropped as new ones arrive, so memory stays bounded across an arbitrarily long
 * match rather than growing forever.
 *
 * <p>Pure logic, no jME/rendering dependency - {@link com.paddleshock.app.GameplayAppState} feeds
 * it real per-tick state and, once a match ends, plays the buffered samples back over the same
 * scene objects before handing off to the normal match-end screen.
 */
public final class ReplayRecorder {

    public static final float REPLAY_WINDOW_SECONDS = 8f;

    private final Deque<ReplaySample> samples = new ArrayDeque<>();
    private float totalSeconds = 0f;

    /** Appends a new sample, then drops the oldest ones until the buffer holds no more than
     *  {@link #REPLAY_WINDOW_SECONDS} of recorded time (always keeping at least one sample). */
    public void record(ReplaySample sample) {
        samples.addLast(sample);
        totalSeconds += sample.tpf();
        while (totalSeconds > REPLAY_WINDOW_SECONDS && samples.size() > 1) {
            ReplaySample dropped = samples.removeFirst();
            totalSeconds -= dropped.tpf();
        }
    }

    /** Clears the buffer - called when a fresh match (or rematch) starts, so a replay never
     *  bleeds in samples from a previous match. */
    public void reset() {
        samples.clear();
        totalSeconds = 0f;
    }

    /** An oldest-to-newest snapshot of the currently buffered samples, safe for the caller to
     *  iterate over while more samples keep being recorded (e.g. a joiner's next network tick). */
    public List<ReplaySample> snapshot() {
        return new ArrayList<>(samples);
    }

    public int size() {
        return samples.size();
    }

    public float totalRecordedSeconds() {
        return totalSeconds;
    }
}

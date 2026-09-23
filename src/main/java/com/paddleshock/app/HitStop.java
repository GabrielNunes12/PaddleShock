package com.paddleshock.app;

/**
 * A tiny freeze on big impacts: while active, the simulation's time step is 0, then time resumes
 * normally. Overlapping triggers keep the longer freeze rather than stacking. Pure logic.
 */
public final class HitStop {

    private float remaining;

    public void trigger(float seconds) {
        remaining = Math.max(remaining, seconds);
    }

    /** The time step the simulation should use this frame: 0 while frozen, else {@code tpf}. */
    public float apply(float tpf) {
        if (remaining > 0f) {
            remaining -= tpf;
            return 0f;
        }
        return tpf;
    }

    public boolean isActive() {
        return remaining > 0f;
    }
}

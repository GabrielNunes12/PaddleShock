package com.paddleshock.ui;

/**
 * Coalesces rapid setting changes (a slider being dragged fires a change most frames) into a
 * single save once the value has been stable for {@link #DELAY_SECONDS} - writing the settings
 * file on every drag tick would otherwise thrash disk I/O for no benefit. Pure logic (no framework
 * dependency), so {@link OptionsState} just calls {@link #markChanged()} whenever a slider's model
 * version changes and {@link #update(float)} once a frame; a caller that's about to discard this
 * (the screen closing) calls {@link #flush()} instead, so a change still "in flight" isn't lost.
 */
public final class SaveDebounce {

    public static final float DELAY_SECONDS = 0.5f;

    private boolean dirty;
    private float sinceChange;

    /** Marks a change as having just happened, (re)starting the quiet-period timer. */
    public void markChanged() {
        dirty = true;
        sinceChange = 0f;
    }

    /**
     * Advances the quiet-period timer by {@code tpf} seconds. Returns {@code true} exactly once
     * per pending change, the first time {@link #update} is called after it has been quiet for
     * {@link #DELAY_SECONDS} - that's the caller's cue to actually persist.
     */
    public boolean update(float tpf) {
        if (!dirty) {
            return false;
        }
        sinceChange += tpf;
        if (sinceChange >= DELAY_SECONDS) {
            dirty = false;
            return true;
        }
        return false;
    }

    /** True while a change hasn't been saved (or flushed) yet. */
    public boolean isDirty() {
        return dirty;
    }

    /**
     * Forces the pending change (if any) to be considered handled right now, regardless of the
     * timer - call this when leaving the screen so the last tweak before closing isn't dropped.
     * Returns whether there actually was a pending change to save.
     */
    public boolean flush() {
        if (!dirty) {
            return false;
        }
        dirty = false;
        return true;
    }
}

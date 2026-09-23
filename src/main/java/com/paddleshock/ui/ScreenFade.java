package com.paddleshock.ui;

/**
 * The timing rule behind {@link FadeState}: whenever the set of visible screens changes, an overlay
 * starts at {@link #START_ALPHA} and eases out to transparent over {@link #DURATION} seconds. Pure
 * logic, so it's unit-testable.
 */
public final class ScreenFade {

    public static final float START_ALPHA = 0.85f;
    public static final float DURATION = 0.22f;

    private String lastSignature;
    private float elapsed = DURATION;

    /** Advances by {@code tpf} given the current screen signature; returns the overlay alpha. */
    public float update(String signature, float tpf) {
        if (lastSignature != null && !lastSignature.equals(signature)) {
            elapsed = 0f;
        } else {
            elapsed = Math.min(DURATION, elapsed + tpf);
        }
        lastSignature = signature;
        float t = elapsed / DURATION;
        float remaining = 1f - t;
        return START_ALPHA * remaining * remaining;
    }
}

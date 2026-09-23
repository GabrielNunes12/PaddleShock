package com.paddleshock.app;

/**
 * "Trauma" camera shake: events add trauma (0..1, capped), it decays linearly, and the shake
 * amplitude is trauma squared - so small bumps barely register while big moments really kick.
 * Pure math; {@link #offset} gives the camera-space {x, y} displacement for the current time.
 */
public final class CameraShake {

    /** World units at full trauma. The camera sits ~13 units from the table, so anything much
     *  smaller is sub-pixel and can't be felt (measured: 0.22 moved it under a pixel on a hit). */
    public static final float MAX_OFFSET = 0.5f;
    public static final float DECAY_PER_SECOND = 1.6f;

    private float trauma;
    private float time;
    private boolean enabled = true;

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            trauma = 0f;
        }
    }

    public void addTrauma(float amount) {
        if (enabled) {
            trauma = Math.min(1f, trauma + Math.max(0f, amount));
        }
    }

    public void update(float tpf) {
        time += tpf;
        trauma = Math.max(0f, trauma - DECAY_PER_SECOND * tpf);
    }

    public float getTrauma() {
        return trauma;
    }

    /** Current {x, y} offset: two unrelated sine mixes stand in for noise, scaled by trauma^2. */
    public float[] offset() {
        float amount = MAX_OFFSET * trauma * trauma;
        float x = (float) (Math.sin(time * 47.0) * 0.6 + Math.sin(time * 83.0 + 1.3) * 0.4);
        float y = (float) (Math.sin(time * 59.0 + 0.7) * 0.6 + Math.sin(time * 97.0 + 2.1) * 0.4);
        return new float[] {x * amount, y * amount};
    }
}

package com.paddleshock.audio;

import com.paddleshock.GameConstants;

/**
 * How hard a hit sounds: faster balls hit higher and louder, so a rally audibly heats up.
 * {@code jitter} in [-1, 1] adds a little per-hit variety so repeats don't sound identical.
 */
public final class HitSoundMapping {

    public static final float MIN_PITCH = 0.85f;
    public static final float MAX_PITCH = 1.3f;
    public static final float MIN_VOLUME = 0.55f;
    public static final float MAX_VOLUME = 1f;
    private static final float JITTER = 0.04f;

    private HitSoundMapping() {
    }

    /** 0 at the serve speed, 1 at the maximum ball speed. */
    static float intensity(float ballSpeed) {
        float t = (ballSpeed - GameConstants.BALL_BASE_SPEED) / (GameConstants.BALL_MAX_SPEED - GameConstants.BALL_BASE_SPEED);
        return Math.max(0f, Math.min(1f, t));
    }

    public static float pitch(float ballSpeed, float jitter) {
        float pitch = MIN_PITCH + (MAX_PITCH - MIN_PITCH) * intensity(ballSpeed) + JITTER * jitter;
        return Math.max(0.5f, Math.min(2f, pitch));
    }

    public static float volume(float ballSpeed) {
        return MIN_VOLUME + (MAX_VOLUME - MIN_VOLUME) * intensity(ballSpeed);
    }
}

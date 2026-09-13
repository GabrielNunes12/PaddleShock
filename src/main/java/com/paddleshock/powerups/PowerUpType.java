package com.paddleshock.powerups;

import com.jme3.math.ColorRGBA;

public enum PowerUpType {
    PADDLE_GROW("Paddle Grow", new ColorRGBA(0.3f, 1f, 0.3f, 1f), 8f, false, PaddleModifier.radius(1.6f)),
    SPEED_BOOST("Speed Boost", new ColorRGBA(1f, 0.9f, 0.2f, 1f), 8f, false, PaddleModifier.speed(1.8f)),
    SLOW_OPPONENT("Slow Opponent", new ColorRGBA(1f, 0.3f, 0.3f, 1f), 8f, true, PaddleModifier.speed(0.5f)),
    SHRINK_OPPONENT("Tiny Paddle", new ColorRGBA(1f, 0.5f, 0.15f, 1f), 8f, true, PaddleModifier.radius(0.5f));

    private final String label;
    private final ColorRGBA color;
    private final float duration;
    private final boolean debuff;
    private final PaddleModifier modifier;

    PowerUpType(String label, ColorRGBA color, float duration, boolean debuff, PaddleModifier modifier) {
        this.label = label;
        this.color = color;
        this.duration = duration;
        this.debuff = debuff;
        this.modifier = modifier;
    }

    public String getLabel() {
        return label;
    }

    public ColorRGBA getColor() {
        return color;
    }

    public float getDuration() {
        return duration;
    }

    /** True for a power-up that lands on the *opponent* (e.g. slowing or shrinking them) rather than the caster. */
    public boolean isDebuff() {
        return debuff;
    }

    /** What this power-up multiplies on the paddle it lands on, while it's active. */
    public PaddleModifier getModifier() {
        return modifier;
    }
}

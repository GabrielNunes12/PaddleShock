package com.paddleshock.powerups;

import com.jme3.math.ColorRGBA;

public enum PowerUpType {
    PADDLE_GROW("Paddle Grow", new ColorRGBA(0.3f, 1f, 0.3f, 1f), 8f, false, PaddleModifier.radius(1.6f)),
    SPEED_BOOST("Speed Boost", new ColorRGBA(1f, 0.9f, 0.2f, 1f), 8f, false, PaddleModifier.speed(1.8f)),
    SLOW_OPPONENT("Slow Opponent", new ColorRGBA(1f, 0.3f, 0.3f, 1f), 8f, true, PaddleModifier.speed(0.5f)),
    SHRINK_OPPONENT("Tiny Paddle", new ColorRGBA(1f, 0.5f, 0.15f, 1f), 8f, true, PaddleModifier.radius(0.5f)),
    // Behavior power-ups (see docs/specs/04-behavior-power-ups.md): no paddle modifier - their
    // effect lives in MatchSimulation. Appended after the originals so the ordinals the network
    // snapshot sends for existing types never change.
    /** Next paddle hit gets maximum spin; consumed by that hit. Duration = how long the charge lasts. */
    CURVEBALL("Curveball", new ColorRGBA(0.7f, 0.45f, 1f, 1f), 10f, false, PaddleModifier.NONE),
    /** The next ball that would score against the owner rebounds instead; consumed by that block. */
    SHIELD("Shield", new ColorRGBA(0.3f, 0.9f, 1f, 1f), 10f, false, PaddleModifier.NONE),
    /** The ball is invisible to the target while over the middle of the table. */
    GHOST_BALL("Ghost Ball", new ColorRGBA(0.85f, 0.87f, 0.95f, 1f), 6f, true, PaddleModifier.NONE);

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

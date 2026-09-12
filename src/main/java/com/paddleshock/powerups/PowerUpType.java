package com.paddleshock.powerups;

import com.jme3.math.ColorRGBA;

public enum PowerUpType {
    PADDLE_GROW("Paddle Grow", new ColorRGBA(0.3f, 1f, 0.3f, 1f), 8f),
    SPEED_BOOST("Speed Boost", new ColorRGBA(1f, 0.9f, 0.2f, 1f), 8f),
    SLOW_OPPONENT("Slow Opponent", new ColorRGBA(1f, 0.3f, 0.3f, 1f), 8f);

    private final String label;
    private final ColorRGBA color;
    private final float duration;

    PowerUpType(String label, ColorRGBA color, float duration) {
        this.label = label;
        this.color = color;
        this.duration = duration;
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
}

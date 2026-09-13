package com.paddleshock.powerups;

/**
 * What a power-up multiplies on the paddle it lands on. Every {@link PowerUpType} carries one of
 * these instead of {@link PowerUpManager} branching on the type by name - adding a new power-up
 * that, say, scales both radius and speed just needs a new modifier value, no code changes here.
 */
public record PaddleModifier(float radiusFactor, float speedFactor) {

    public static final PaddleModifier NONE = new PaddleModifier(1f, 1f);

    public static PaddleModifier radius(float factor) {
        return new PaddleModifier(factor, 1f);
    }

    public static PaddleModifier speed(float factor) {
        return new PaddleModifier(1f, factor);
    }
}

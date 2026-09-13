package com.paddleshock.sim;

import com.paddleshock.data.PowerUpDefinition;

/**
 * One side's input for a single {@link MatchSimulation#tick} call: how far that paddle tried to
 * move this tick, and which power-up (if any) it tried to activate this tick. Carries no jME
 * types - this is the same shape a remote/server authority will eventually consume from a network
 * message instead of from local mouse/AI logic.
 */
public final class PaddleInput {

    private static final PaddleInput NONE = new PaddleInput(0f, 0f, null);

    private final float deltaX;
    private final float deltaZ;
    private final PowerUpDefinition activatedPowerUp;

    public PaddleInput(float deltaX, float deltaZ, PowerUpDefinition activatedPowerUp) {
        this.deltaX = deltaX;
        this.deltaZ = deltaZ;
        this.activatedPowerUp = activatedPowerUp;
    }

    /** No movement, no power-up activation this tick. */
    public static PaddleInput none() {
        return NONE;
    }

    public static PaddleInput move(float deltaX, float deltaZ) {
        return new PaddleInput(deltaX, deltaZ, null);
    }

    public PaddleInput withPowerUp(PowerUpDefinition powerUpDefinition) {
        return new PaddleInput(deltaX, deltaZ, powerUpDefinition);
    }

    public float getDeltaX() {
        return deltaX;
    }

    public float getDeltaZ() {
        return deltaZ;
    }

    /** The power-up slot activation requested this tick, or {@code null} if none. */
    public PowerUpDefinition getActivatedPowerUp() {
        return activatedPowerUp;
    }
}

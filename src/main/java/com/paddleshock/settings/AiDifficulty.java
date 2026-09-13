package com.paddleshock.settings;

/**
 * Vs-AI opponent difficulty: scales how fast the AI paddle can move (its "reflexes") and how
 * often it uses power-ups. Picked before a vs-AI match in {@code LoadoutState} and persisted as
 * the player's default in {@link GameSettings} so it carries over to the next match.
 */
public enum AiDifficulty {

    EASY("EASY", 4.2f, 4.5f, 8f),
    NORMAL("NORMAL", 6.5f, 3f, 6f),
    HARD("HARD", 9.5f, 1.5f, 3.5f);

    private final String displayName;
    private final float maxSpeed;
    private final float powerUpMinInterval;
    private final float powerUpMaxInterval;

    AiDifficulty(String displayName, float maxSpeed, float powerUpMinInterval, float powerUpMaxInterval) {
        this.displayName = displayName;
        this.maxSpeed = maxSpeed;
        this.powerUpMinInterval = powerUpMinInterval;
        this.powerUpMaxInterval = powerUpMaxInterval;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** How fast (world units/second) the AI paddle can move to chase the ball. */
    public float getMaxSpeed() {
        return maxSpeed;
    }

    /** Minimum seconds between the AI considering firing a ready power-up. */
    public float getPowerUpMinInterval() {
        return powerUpMinInterval;
    }

    /** Maximum seconds between the AI considering firing a ready power-up. */
    public float getPowerUpMaxInterval() {
        return powerUpMaxInterval;
    }
}

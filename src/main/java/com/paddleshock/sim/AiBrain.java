package com.paddleshock.sim;

/**
 * The AI opponent's paddle-movement decision, with no jME or scene dependencies: each tick it
 * steps its paddle toward the ball's x, capped at {@code maxSpeed * tpf}.
 *
 * <p>{@code sloppiness} adds a slow sinusoidal tracking error (in world units) so weaker World
 * Tour opponents visibly misjudge the ball instead of just being slower. With sloppiness 0 this
 * is exactly the original quick-match AI: a clamped step straight toward the ball.
 */
public final class AiBrain {

    /** How fast the tracking error drifts back and forth, in radians per second. */
    private static final float WOBBLE_RATE = 1.7f;

    private final float maxSpeed;
    private final float sloppiness;
    private float time;

    public AiBrain(float maxSpeed, float sloppiness) {
        this.maxSpeed = maxSpeed;
        this.sloppiness = sloppiness;
    }

    /** The x delta to move the AI paddle this tick. */
    public float step(float ballX, float paddleX, float tpf) {
        time += tpf;
        float target = ballX + sloppiness * (float) Math.sin(time * WOBBLE_RATE);
        float maxStep = maxSpeed * tpf;
        return Math.max(-maxStep, Math.min(maxStep, target - paddleX));
    }
}

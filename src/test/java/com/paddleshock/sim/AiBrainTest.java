package com.paddleshock.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AiBrainTest {

    private static final float EPS = 1e-5f;

    /** The pre-refactor quick-match AI, inlined from GameplayAppState, as the reference. */
    private static float originalStep(float ballX, float paddleX, float maxSpeed, float tpf) {
        float toBall = ballX - paddleX;
        float maxStep = maxSpeed * tpf;
        return Math.max(-maxStep, Math.min(maxStep, toBall));
    }

    @Test
    void zeroSloppinessMatchesTheOriginalQuickMatchAi() {
        AiBrain brain = new AiBrain(6.5f, 0f);
        float[][] cases = {{3f, 0f}, {-3f, 0f}, {0.01f, 0f}, {-0.02f, 0.5f}, {0f, 0f}, {2f, 1.99f}};
        for (float[] c : cases) {
            assertEquals(originalStep(c[0], c[1], 6.5f, 1 / 60f), brain.step(c[0], c[1], 1 / 60f), EPS);
        }
    }

    @Test
    void stepNeverExceedsMaxSpeed() {
        AiBrain brain = new AiBrain(4f, 1.5f);
        for (int i = 0; i < 600; i++) {
            float step = brain.step(100f * (i % 2 == 0 ? 1 : -1), 0f, 1 / 60f);
            assertTrue(Math.abs(step) <= 4f / 60f + EPS, "step " + step);
        }
    }

    @Test
    void sloppinessMakesTheAiSettleOffTheBallButWithinTheAmplitude() {
        AiBrain brain = new AiBrain(1000f, 1.2f); // fast enough to always reach its target
        float paddleX = 0f;
        float maxMiss = 0f;
        for (int i = 0; i < 600; i++) {
            paddleX += brain.step(0f, paddleX, 1 / 60f);
            maxMiss = Math.max(maxMiss, Math.abs(paddleX));
        }
        assertTrue(maxMiss > 0.5f, "a sloppy AI should visibly miss the ball's line: " + maxMiss);
        assertTrue(maxMiss <= 1.2f + EPS, "miss must stay within the sloppiness amplitude: " + maxMiss);
    }
}

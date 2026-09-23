package com.paddleshock.app;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.jme3.math.Vector3f;

import com.paddleshock.GameConstants;
import com.paddleshock.achievements.AchievementTracker;
import com.paddleshock.achievements.MatchOutcome;
import com.paddleshock.challenges.ChallengeTracker;
import com.paddleshock.data.PlayerProfile;
import com.paddleshock.input.KeyboardStick;

/** See docs/specs/08-local-versus.md. */
class LocalVersusTest {

    private static final float EPS = 1e-6f;

    @Test
    void arrowKeysBecomeAStickVector() {
        KeyboardStick stick = new KeyboardStick();
        assertArrayEquals(new float[] {0f, 0f}, stick.vector(), EPS);
        stick.setRight(true);
        assertArrayEquals(new float[] {1f, 0f}, stick.vector(), EPS);
        stick.setLeft(true);
        assertArrayEquals(new float[] {0f, 0f}, stick.vector(), EPS, "opposite keys cancel");
        stick.setLeft(false);
        stick.setUp(true);
        float[] diagonal = stick.vector();
        assertEquals(1f, (float) Math.hypot(diagonal[0], diagonal[1]), 1e-5f, "a diagonal isn't faster");
        assertTrue(diagonal[0] > 0f && diagonal[1] > 0f);
        stick.setUp(false);
        stick.setRight(false);
        stick.setDown(true);
        assertArrayEquals(new float[] {0f, -1f}, stick.vector(), EPS);
    }

    @Test
    void stickMovesAlongTheCameraAxesAtGamepadSpeed() {
        // The side-on versus camera: screen-right is world +z, screen-up is world +x.
        Vector3f right = new Vector3f(0f, 0f, 1f);
        Vector3f up = new Vector3f(1f, 0f, 0f);
        float tpf = 0.5f;
        float[] delta = SecondPlayerInput.toWorldDelta(new float[] {1f, 0f}, tpf, right, up);
        assertArrayEquals(new float[] {0f, GameConstants.GAMEPAD_MOVE_SPEED * tpf}, delta, EPS);
        delta = SecondPlayerInput.toWorldDelta(new float[] {0f, -1f}, tpf, right, up);
        assertArrayEquals(new float[] {-GameConstants.GAMEPAD_MOVE_SPEED * tpf, 0f}, delta, EPS);
    }

    @Test
    void localVersusNeverCountsTowardAchievementsOrChallenges() {
        PlayerProfile profile = new PlayerProfile();
        int credits = profile.getCurrency();
        MatchOutcome shutout = new MatchOutcome(MatchOutcome.Kind.LOCAL_VERSUS, true, 10, 0, "level_classic", null, 50);
        for (int i = 0; i < 30; i++) {
            assertTrue(AchievementTracker.recordMatch(profile, shutout).isEmpty());
            assertTrue(ChallengeTracker.recordMatch(profile, shutout, LocalDate.of(2026, 9, 23).plusDays(i)).isEmpty());
        }
        assertEquals(0, profile.getTotalWins());
        assertEquals(0, profile.getPowerUpsUsed());
        assertTrue(profile.getUnlockedAchievements().isEmpty());
        assertEquals(credits, profile.getCurrency());
    }
}

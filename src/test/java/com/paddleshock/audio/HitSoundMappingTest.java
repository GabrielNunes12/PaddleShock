package com.paddleshock.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.paddleshock.GameConstants;

class HitSoundMappingTest {

    @Test
    void harderHitsSoundHigherAndLouder() {
        float slowPitch = HitSoundMapping.pitch(GameConstants.BALL_BASE_SPEED, 0f);
        float fastPitch = HitSoundMapping.pitch(GameConstants.BALL_MAX_SPEED, 0f);
        assertEquals(HitSoundMapping.MIN_PITCH, slowPitch, 1e-5f);
        assertEquals(HitSoundMapping.MAX_PITCH, fastPitch, 1e-5f);
        assertTrue(HitSoundMapping.volume(GameConstants.BALL_MAX_SPEED) > HitSoundMapping.volume(GameConstants.BALL_BASE_SPEED));
    }

    @Test
    void outOfRangeSpeedsAreClamped() {
        assertEquals(HitSoundMapping.MIN_VOLUME, HitSoundMapping.volume(0f), 1e-6f);
        assertEquals(HitSoundMapping.MAX_VOLUME, HitSoundMapping.volume(999f), 1e-6f);
        assertEquals(HitSoundMapping.MAX_PITCH, HitSoundMapping.pitch(999f, 0f), 1e-5f);
    }

    @Test
    void jitterIsSmall() {
        float speed = (GameConstants.BALL_BASE_SPEED + GameConstants.BALL_MAX_SPEED) / 2f;
        float spread = HitSoundMapping.pitch(speed, 1f) - HitSoundMapping.pitch(speed, -1f);
        assertTrue(spread > 0f && spread <= 0.1f, "spread " + spread);
    }
}

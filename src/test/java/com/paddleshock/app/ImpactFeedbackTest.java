package com.paddleshock.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.paddleshock.settings.GameSettings;

/** Camera shake and hit-stop rules - see docs/specs/09-visual-polish.md. */
class ImpactFeedbackTest {

    @Test
    void traumaIsCappedAndDecaysToZero() {
        CameraShake shake = new CameraShake();
        shake.addTrauma(0.7f);
        shake.addTrauma(0.7f);
        assertEquals(1f, shake.getTrauma(), 1e-6f);
        for (int i = 0; i < 120; i++) {
            shake.update(1 / 60f);
        }
        assertEquals(0f, shake.getTrauma(), 1e-6f);
        float[] offset = shake.offset();
        assertEquals(0f, offset[0], 1e-6f);
        assertEquals(0f, offset[1], 1e-6f);
    }

    @Test
    void shakeAmplitudeGrowsWithTraumaSquaredAndStaysBounded() {
        CameraShake shake = new CameraShake();
        float maxSmall = 0f;
        float maxBig = 0f;
        for (int i = 0; i < 200; i++) {
            shake.addTrauma(1f);
            shake.update(0.001f);
            float[] o = shake.offset();
            maxBig = Math.max(maxBig, Math.max(Math.abs(o[0]), Math.abs(o[1])));
        }
        CameraShake small = new CameraShake();
        for (int i = 0; i < 200; i++) {
            small.update(0.001f);
            small.addTrauma(0.3f - small.getTrauma());
            float[] o = small.offset();
            maxSmall = Math.max(maxSmall, Math.max(Math.abs(o[0]), Math.abs(o[1])));
        }
        assertTrue(maxBig <= CameraShake.MAX_OFFSET + 1e-6f, "bounded: " + maxBig);
        assertTrue(maxSmall < maxBig * 0.2f, "trauma 0.3 shakes far less than 1.0: " + maxSmall + " vs " + maxBig);
    }

    @Test
    void disabledShakeNeverMoves() {
        CameraShake shake = new CameraShake();
        shake.addTrauma(0.9f);
        shake.setEnabled(false);
        shake.addTrauma(1f);
        shake.update(0.01f);
        assertEquals(0f, shake.getTrauma(), 1e-6f);
    }

    @Test
    void hitStopFreezesThenReleasesAndDoesNotStack() {
        HitStop stop = new HitStop();
        assertEquals(0.016f, stop.apply(0.016f), 1e-6f);
        stop.trigger(0.05f);
        stop.trigger(0.02f);
        int frozenFrames = 0;
        while (stop.apply(0.016f) == 0f) {
            frozenFrames++;
            assertTrue(frozenFrames < 10, "must release");
        }
        assertEquals(4, frozenFrames, "0.05 s at 60 fps, the shorter trigger doesn't extend it");
        assertFalse(stop.isActive());
    }

    @Test
    void oldSettingsFilesKeepScreenShakeOn() {
        GameSettings settings = new com.google.gson.Gson().fromJson("{\"brightness\":1.2}", GameSettings.class);
        assertTrue(settings.isScreenShake());
    }
}

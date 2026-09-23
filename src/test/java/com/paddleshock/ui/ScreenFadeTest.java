package com.paddleshock.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScreenFadeTest {

    @Test
    void noFadeOnTheFirstFrameOrWhileNothingChanges() {
        ScreenFade fade = new ScreenFade();
        assertEquals(0f, fade.update("100", 0.016f), 1e-6f);
        assertEquals(0f, fade.update("100", 0.016f), 1e-6f);
    }

    @Test
    void aScreenChangeStartsOpaqueAndClearsWithinTheDuration() {
        ScreenFade fade = new ScreenFade();
        fade.update("100", 0.016f);
        assertEquals(ScreenFade.START_ALPHA, fade.update("010", 0.016f), 1e-6f);
        float mid = fade.update("010", ScreenFade.DURATION / 2f);
        assertTrue(mid > 0f && mid < ScreenFade.START_ALPHA, "mid " + mid);
        assertEquals(0f, fade.update("010", ScreenFade.DURATION), 1e-6f);
    }
}

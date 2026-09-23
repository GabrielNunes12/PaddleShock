package com.paddleshock.settings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GraphicsProfileTest {

    @Test
    void lowUsesNoFiltersButKeepsTheBlobShadow() {
        GraphicsProfile low = GraphicsProfile.of(VideoQuality.LOW);
        assertFalse(low.needsPostProcessing(), "LOW keeps the old filter-free path");
        assertTrue(low.blobShadow(), "LOW still needs some depth cue under the ball");
    }

    @Test
    void neverMoreEffectsAtALowerQuality() {
        VideoQuality[] qualities = VideoQuality.values();
        for (int i = 1; i < qualities.length; i++) {
            GraphicsProfile lower = GraphicsProfile.of(qualities[i - 1]);
            GraphicsProfile higher = GraphicsProfile.of(qualities[i]);
            String pair = qualities[i - 1] + " -> " + qualities[i];
            assertTrue(higher.shadowMapSize() >= lower.shadowMapSize(), pair);
            assertTrue(!lower.ambientOcclusion() || higher.ambientOcclusion(), pair);
            assertTrue(!lower.bloom() || higher.bloom(), pair);
        }
    }

    @Test
    void everyQualityHasSomeGroundingShadow() {
        for (VideoQuality quality : VideoQuality.values()) {
            GraphicsProfile profile = GraphicsProfile.of(quality);
            assertTrue(profile.shadows() || profile.blobShadow(), quality.toString());
            assertFalse(profile.shadows() && profile.blobShadow(), quality + " would draw two ball shadows");
        }
    }
}

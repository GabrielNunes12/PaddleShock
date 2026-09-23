package com.paddleshock.entities;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.jme3.math.ColorRGBA;

class SkyGradientTest {

    private static final ColorRGBA ZENITH = new ColorRGBA(0f, 0f, 1f, 1f);
    private static final ColorRGBA HORIZON = new ColorRGBA(1f, 1f, 1f, 1f);
    private static final ColorRGBA BELOW = new ColorRGBA(0f, 1f, 0f, 1f);

    @Test
    void endpointsAndHorizonAreExact() {
        assertEquals(BELOW, SkyGradient.colorAt(0f, ZENITH, HORIZON, BELOW));
        assertEquals(HORIZON, SkyGradient.colorAt(0.5f, ZENITH, HORIZON, BELOW));
        assertEquals(ZENITH, SkyGradient.colorAt(1f, ZENITH, HORIZON, BELOW));
    }

    @Test
    void theSkyAboveTheHorizonBlendsTowardTheZenith() {
        ColorRGBA quarterUp = SkyGradient.colorAt(0.75f, ZENITH, HORIZON, BELOW);
        // Blue stays full; red/green fall as it approaches the (blue) zenith.
        assertEquals(1f, quarterUp.b, 1e-6f);
        float r = quarterUp.r;
        assertEquals(true, r > 0f && r < 1f, "partway between horizon and zenith: " + r);
    }
}

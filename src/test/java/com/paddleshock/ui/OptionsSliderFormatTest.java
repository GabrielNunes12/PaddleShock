package com.paddleshock.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OptionsSliderFormatTest {

    @Test
    void percentFormatsBoundaries() {
        assertEquals("0%", OptionsSliderFormat.percent(0.0));
        assertEquals("100%", OptionsSliderFormat.percent(1.0));
    }

    @Test
    void percentRoundsToTheNearestWholeNumber() {
        assertEquals("80%", OptionsSliderFormat.percent(0.8));
        assertEquals("85%", OptionsSliderFormat.percent(0.845));
        assertEquals("60%", OptionsSliderFormat.percent(0.6));
        // Half-up rounding at the midpoint.
        assertEquals("13%", OptionsSliderFormat.percent(0.125));
    }

    @Test
    void multiplierAlwaysShowsOneDecimalWithAnXSuffix() {
        assertEquals("1.0x", OptionsSliderFormat.multiplier(1.0));
        assertEquals("0.1x", OptionsSliderFormat.multiplier(0.1));
        assertEquals("5.0x", OptionsSliderFormat.multiplier(5.0));
        assertEquals("2.3x", OptionsSliderFormat.multiplier(2.25));
    }
}

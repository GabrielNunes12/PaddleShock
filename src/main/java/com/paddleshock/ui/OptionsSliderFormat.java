package com.paddleshock.ui;

import java.util.Locale;

/**
 * Value-to-display formatting for the Options screen's slider rows (sound/music volume, mouse
 * sensitivity) - pure and framework-free so it's directly unit-testable, unlike the surrounding
 * Lemur UI construction.
 */
public final class OptionsSliderFormat {

    private OptionsSliderFormat() {
    }

    /** Formats a 0..1 slider value (sound/music volume) as a rounded whole percentage, e.g. "80%". */
    public static String percent(double value) {
        return Math.round(value * 100.0) + "%";
    }

    /** Formats a sensitivity multiplier to one decimal place with an "x" suffix, e.g. "1.0x".
     *  {@link Locale#ROOT} keeps the decimal point even under locales (like pt-BR) that would
     *  otherwise format it with a comma. */
    public static String multiplier(double value) {
        return String.format(Locale.ROOT, "%.1fx", value);
    }
}

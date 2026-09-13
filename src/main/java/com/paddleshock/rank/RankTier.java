package com.paddleshock.rank;

import com.jme3.math.ColorRGBA;

/** Copper through Diamond, in ascending order - see {@code aws/README.md} for the full ranked
 *  ladder design (LP, divisions, promotion series, seasonal reset). */
public enum RankTier {
    COPPER("Copper", new ColorRGBA(0.72f, 0.45f, 0.20f, 1f)),
    BRONZE("Bronze", new ColorRGBA(0.80f, 0.50f, 0.20f, 1f)),
    SILVER("Silver", new ColorRGBA(0.75f, 0.75f, 0.78f, 1f)),
    GOLD("Gold", new ColorRGBA(1.00f, 0.84f, 0.20f, 1f)),
    PLATINUM("Platinum", new ColorRGBA(0.40f, 0.85f, 0.75f, 1f)),
    DIAMOND("Diamond", new ColorRGBA(0.45f, 0.75f, 1.00f, 1f));

    private final String displayName;
    private final ColorRGBA color;

    RankTier(String displayName, ColorRGBA color) {
        this.displayName = displayName;
        this.color = color;
    }

    public String getDisplayName() {
        return displayName;
    }

    public ColorRGBA getColor() {
        return color;
    }

    /** Division is stored/transmitted as 4 (lowest) down to 1 (highest), matching the wire
     *  format's numbering - this converts it to the traditional IV..I roman-numeral label. */
    public static String divisionToRoman(int division) {
        return switch (division) {
            case 4 -> "IV";
            case 3 -> "III";
            case 2 -> "II";
            case 1 -> "I";
            default -> String.valueOf(division);
        };
    }
}

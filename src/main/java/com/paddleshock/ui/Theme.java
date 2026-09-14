package com.paddleshock.ui;

import com.jme3.math.ColorRGBA;

/** Shared "bold sports broadcast" palette, matching the store design mockup. */
public final class Theme {

    private Theme() {
    }

    public static final ColorRGBA BACKGROUND = new ColorRGBA(0.102f, 0.114f, 0.141f, 1f);
    /** A touch darker than PANEL - used for small tags/pills sitting on top of a PANEL card so
     *  they read as a distinct layer instead of blending into the card background. */
    public static final ColorRGBA BACKGROUND_2 = new ColorRGBA(0.118f, 0.129f, 0.157f, 1f);
    public static final ColorRGBA PANEL = new ColorRGBA(0.137f, 0.149f, 0.180f, 1f);
    public static final ColorRGBA PANEL_HOVER = new ColorRGBA(0.180f, 0.196f, 0.235f, 1f);
    /** Subtle hairline border color for card outlines. */
    public static final ColorRGBA PANEL_LINE = new ColorRGBA(0.235f, 0.251f, 0.294f, 1f);

    public static final ColorRGBA TEXT = new ColorRGBA(0.961f, 0.965f, 0.973f, 1f);
    public static final ColorRGBA TEXT_DIM = new ColorRGBA(0.545f, 0.565f, 0.612f, 1f);
    /** Even dimmer than TEXT_DIM - used for small captions above a value (e.g. "PADDLE" above the
     *  equipped item's name). */
    public static final ColorRGBA TEXT_DIM2 = new ColorRGBA(0.400f, 0.420f, 0.470f, 1f);

    public static final ColorRGBA ORANGE = new ColorRGBA(0.910f, 0.510f, 0.227f, 1f);
    public static final ColorRGBA BLUE = new ColorRGBA(0.310f, 0.514f, 0.788f, 1f);
    public static final ColorRGBA GREEN = new ColorRGBA(0.310f, 0.749f, 0.561f, 1f);

    /** Muted/dark tints of the accent colors, paired with the matching bright accent as text for
     *  low-emphasis badges and pills (credits pill, equipped/secondary buttons). */
    public static final ColorRGBA ORANGE_DIM = new ColorRGBA(0.302f, 0.196f, 0.114f, 1f);
    public static final ColorRGBA BLUE_DIM = new ColorRGBA(0.145f, 0.184f, 0.251f, 1f);
    public static final ColorRGBA GREEN_DIM = new ColorRGBA(0.129f, 0.235f, 0.196f, 1f);

    /** Text color used on top of the bright accent buttons (orange/blue/green). */
    public static final ColorRGBA ON_ACCENT = BACKGROUND;

    /** Semi-transparent backdrop for overlays (pause) shown on top of a frozen 3D scene. */
    public static final ColorRGBA OVERLAY = new ColorRGBA(0.102f, 0.114f, 0.141f, 0.82f);
}

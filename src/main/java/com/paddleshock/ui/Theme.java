package com.paddleshock.ui;

import com.jme3.math.ColorRGBA;

/** Shared "bold sports broadcast" palette, matching the store design mockup. */
public final class Theme {

    private Theme() {
    }

    public static final ColorRGBA BACKGROUND = new ColorRGBA(0.102f, 0.114f, 0.141f, 1f);
    /** Darker than {@link #BACKGROUND} - side panels / hero sections that sit a level "behind"
     *  the main background (see the redesigned MainMenuState's split layout). */
    public static final ColorRGBA BACKGROUND_2 = new ColorRGBA(0.082f, 0.090f, 0.114f, 1f);
    public static final ColorRGBA PANEL = new ColorRGBA(0.137f, 0.149f, 0.180f, 1f);
    public static final ColorRGBA PANEL_HOVER = new ColorRGBA(0.180f, 0.196f, 0.235f, 1f);
    /** Hairline border color for card-style containers. */
    public static final ColorRGBA PANEL_LINE = new ColorRGBA(0.192f, 0.208f, 0.247f, 1f);

    public static final ColorRGBA TEXT = new ColorRGBA(0.961f, 0.965f, 0.973f, 1f);
    public static final ColorRGBA TEXT_DIM = new ColorRGBA(0.545f, 0.565f, 0.612f, 1f);
    /** Even dimmer than {@link #TEXT_DIM} - meta text (timestamps, helper captions). */
    public static final ColorRGBA TEXT_DIM2 = new ColorRGBA(0.361f, 0.384f, 0.439f, 1f);

    public static final ColorRGBA ORANGE = new ColorRGBA(0.910f, 0.510f, 0.227f, 1f);
    public static final ColorRGBA BLUE = new ColorRGBA(0.310f, 0.514f, 0.788f, 1f);
    public static final ColorRGBA GREEN = new ColorRGBA(0.310f, 0.749f, 0.561f, 1f);

    /** Low-saturation tinted backgrounds for icon chips / soft badges - each a dark, muted tint
     *  of its matching accent color, not the accent itself (see the redesigned nav cards / match
     *  history icon chips). */
    public static final ColorRGBA ORANGE_DIM = new ColorRGBA(0.227f, 0.173f, 0.122f, 1f);
    public static final ColorRGBA BLUE_DIM = new ColorRGBA(0.122f, 0.165f, 0.227f, 1f);
    public static final ColorRGBA GREEN_DIM = new ColorRGBA(0.106f, 0.196f, 0.161f, 1f);

    /** Text color used on top of the bright accent buttons (orange/blue/green). */
    public static final ColorRGBA ON_ACCENT = BACKGROUND;

    /** Semi-transparent backdrop for overlays (pause) shown on top of a frozen 3D scene. */
    public static final ColorRGBA OVERLAY = new ColorRGBA(0.102f, 0.114f, 0.141f, 0.82f);
}

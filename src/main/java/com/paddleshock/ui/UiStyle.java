package com.paddleshock.ui;

import com.jme3.math.ColorRGBA;
import com.simsilica.lemur.HAlignment;
import com.simsilica.lemur.VAlignment;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.style.Attributes;
import com.simsilica.lemur.style.Styles;

/**
 * Game-wide overrides of Lemur's "glass" base style (applied once at startup, after
 * {@code BaseStyles.loadGlassStyle()}), so anything a screen doesn't style explicitly still looks
 * like the rest of the game instead of Lemur's defaults: no translucent teal gradient behind bare
 * containers, flat buttons with centered text, no drop shadows on text, and a warm hover color.
 * Screens that set their own backgrounds/colors/alignment keep them.
 */
public final class UiStyle {

    public static final String STYLE = "glass";
    /** Text color while the pointer is over a button. */
    public static final ColorRGBA HOVER_TEXT = new ColorRGBA(1f, 0.86f, 0.62f, 1f);
    private static final ColorRGBA NO_SHADOW = new ColorRGBA(0f, 0f, 0f, 0f);

    private UiStyle() {
    }

    public static void apply(Styles styles) {
        Attributes container = styles.getSelector("container", STYLE);
        container.set("background", null, true);

        Attributes button = styles.getSelector("button", STYLE);
        button.set("background", new QuadBackgroundComponent(Theme.PANEL_HOVER), true);
        button.set("color", Theme.TEXT, true);
        button.set("highlightColor", HOVER_TEXT, true);
        button.set("shadowColor", NO_SHADOW, true);
        button.set("textHAlignment", HAlignment.Center, true);
        button.set("textVAlignment", VAlignment.Center, true);

        Attributes label = styles.getSelector("label", STYLE);
        label.set("color", Theme.TEXT, true);
        label.set("shadowColor", NO_SHADOW, true);

        // Lemur's glass base style paints the slider's own background AND its range/track with
        // the same translucent teal gradient as everything else (see glass-styles.groovy) - the
        // exact look the rest of this class exists to remove. Style every part of Slider
        // (element ids per com.simsilica.lemur.Slider: "slider" root, "range", "thumb.button",
        // "left.button", "right.button") explicitly instead.
        Attributes slider = styles.getSelector("slider", STYLE);
        slider.set("background", null, true);

        Attributes sliderRange = styles.getSelector("slider.range", STYLE);
        sliderRange.set("background", new QuadBackgroundComponent(Theme.PANEL), true);

        Attributes sliderThumb = styles.getSelector("slider.thumb.button", STYLE);
        sliderThumb.set("background", new QuadBackgroundComponent(Theme.ORANGE), true);
        sliderThumb.set("text", "", true);
        sliderThumb.set("shadowColor", NO_SHADOW, true);

        Attributes sliderLeft = styles.getSelector("slider.left.button", STYLE);
        sliderLeft.set("background", new QuadBackgroundComponent(Theme.PANEL_HOVER), true);
        sliderLeft.set("color", Theme.TEXT, true);
        sliderLeft.set("highlightColor", HOVER_TEXT, true);
        sliderLeft.set("shadowColor", NO_SHADOW, true);
        sliderLeft.set("text", "<", true);

        Attributes sliderRight = styles.getSelector("slider.right.button", STYLE);
        sliderRight.set("background", new QuadBackgroundComponent(Theme.PANEL_HOVER), true);
        sliderRight.set("color", Theme.TEXT, true);
        sliderRight.set("highlightColor", HOVER_TEXT, true);
        sliderRight.set("shadowColor", NO_SHADOW, true);
        sliderRight.set("text", ">", true);
    }
}

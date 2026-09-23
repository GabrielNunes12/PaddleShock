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
    }
}

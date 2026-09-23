package com.paddleshock.data;

import com.jme3.math.ColorRGBA;

import com.paddleshock.entities.TextureSet;

/**
 * A look-only item: a paddle skin, ball trail or score celebration. Never affects gameplay - see
 * docs/specs/06-cosmetics-and-loss-rewards.md. {@code color} is the skin tint / trail color /
 * burst color; {@code rainbow} cycles hues instead (trails and celebrations only); a skin also
 * carries its {@code textureSet}. Each category's first entry in {@link Catalog} is the free
 * "none" default, which means "use the normal look".
 */
public class CosmeticDefinition extends ItemDefinition {

    private final String category;
    private final ColorRGBA color;
    private final TextureSet textureSet;
    private final boolean rainbow;

    public CosmeticDefinition(String id, String displayName, int price, String category, ColorRGBA color,
            TextureSet textureSet, boolean rainbow) {
        super(id, displayName, price);
        this.category = category;
        this.color = color;
        this.textureSet = textureSet;
        this.rainbow = rainbow;
    }

    /** "skin", "trail" or "celebration" - the {@code PlayerProfile} category it's owned/equipped under. */
    public String getCategory() {
        return category;
    }

    public ColorRGBA getColor() {
        return color;
    }

    public TextureSet getTextureSet() {
        return textureSet;
    }

    public boolean isRainbow() {
        return rainbow;
    }

    /** True for the free default that means "no cosmetic" in its category. */
    public boolean isNone() {
        return getId().endsWith("_none");
    }
}

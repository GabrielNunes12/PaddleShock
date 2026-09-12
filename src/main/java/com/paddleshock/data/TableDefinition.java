package com.paddleshock.data;

import com.jme3.math.ColorRGBA;

import com.paddleshock.entities.TextureSet;

/** A purchasable table: changes ball restitution (bounciness) and looks. */
public class TableDefinition extends ItemDefinition {

    private final float restitutionMultiplier;
    private final ColorRGBA surfaceColor;
    private final TextureSet textureSet;

    public TableDefinition(String id, String displayName, int price,
            float restitutionMultiplier, ColorRGBA surfaceColor, TextureSet textureSet) {
        super(id, displayName, price);
        this.restitutionMultiplier = restitutionMultiplier;
        this.surfaceColor = surfaceColor;
        this.textureSet = textureSet;
    }

    public float getRestitutionMultiplier() {
        return restitutionMultiplier;
    }

    public ColorRGBA getSurfaceColor() {
        return surfaceColor;
    }

    public TextureSet getTextureSet() {
        return textureSet;
    }
}

package com.paddleshock.data;

import com.jme3.math.ColorRGBA;

/** A purchasable table: changes ball restitution (bounciness) and looks. */
public class TableDefinition extends ItemDefinition {

    private final float restitutionMultiplier;
    private final ColorRGBA surfaceColor;

    public TableDefinition(String id, String displayName, int price,
            float restitutionMultiplier, ColorRGBA surfaceColor) {
        super(id, displayName, price);
        this.restitutionMultiplier = restitutionMultiplier;
        this.surfaceColor = surfaceColor;
    }

    public float getRestitutionMultiplier() {
        return restitutionMultiplier;
    }

    public ColorRGBA getSurfaceColor() {
        return surfaceColor;
    }
}

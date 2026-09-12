package com.paddleshock.data;

import com.jme3.math.ColorRGBA;

import com.paddleshock.entities.TextureSet;

/** A purchasable paddle: trades speed against size (bigger paddle, slower turn). */
public class PaddleDefinition extends ItemDefinition {

    private final float speedMultiplier;
    private final float sizeMultiplier;
    private final ColorRGBA color;
    private final TextureSet textureSet;

    public PaddleDefinition(String id, String displayName, int price,
            float speedMultiplier, float sizeMultiplier, ColorRGBA color, TextureSet textureSet) {
        super(id, displayName, price);
        this.speedMultiplier = speedMultiplier;
        this.sizeMultiplier = sizeMultiplier;
        this.color = color;
        this.textureSet = textureSet;
    }

    public float getSpeedMultiplier() {
        return speedMultiplier;
    }

    public float getSizeMultiplier() {
        return sizeMultiplier;
    }

    public ColorRGBA getColor() {
        return color;
    }

    public TextureSet getTextureSet() {
        return textureSet;
    }
}

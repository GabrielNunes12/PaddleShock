package com.paddleshock.data;

import com.jme3.math.ColorRGBA;

/** A purchasable ball: trades speed against size (bigger ball, easier to hit, slower). */
public class BallDefinition extends ItemDefinition {

    private final float speedMultiplier;
    private final float sizeMultiplier;
    private final ColorRGBA color;

    public BallDefinition(String id, String displayName, int price,
            float speedMultiplier, float sizeMultiplier, ColorRGBA color) {
        super(id, displayName, price);
        this.speedMultiplier = speedMultiplier;
        this.sizeMultiplier = sizeMultiplier;
        this.color = color;
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
}

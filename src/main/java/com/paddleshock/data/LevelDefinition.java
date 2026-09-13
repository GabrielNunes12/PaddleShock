package com.paddleshock.data;

import com.jme3.math.ColorRGBA;

import com.paddleshock.entities.TextureSet;

/**
 * A free arena: its own environment (sky/lighting/ground/backdrop) *and* its own ball physics
 * quirk (gravity, sideways wind drift, table-bounce energy) layered on top of whatever
 * paddle/table/ball the player bought in the store - independent of those, never replacing them.
 */
public class LevelDefinition extends ItemDefinition {

    private final ColorRGBA skyColor;
    private final ColorRGBA sunTint;
    private final ColorRGBA ambientTint;
    private final ColorRGBA groundColor;
    private final TextureSet groundTexture;
    private final ColorRGBA backdropColor;
    private final String tagline;
    private final float gravityMultiplier;
    private final float windAccelX;
    private final float bounceMultiplier;

    public LevelDefinition(String id, String displayName, int price, ColorRGBA skyColor, ColorRGBA sunTint,
            ColorRGBA ambientTint, ColorRGBA groundColor, TextureSet groundTexture, ColorRGBA backdropColor,
            String tagline, float gravityMultiplier, float windAccelX, float bounceMultiplier) {
        super(id, displayName, price);
        this.skyColor = skyColor;
        this.sunTint = sunTint;
        this.ambientTint = ambientTint;
        this.groundColor = groundColor;
        this.groundTexture = groundTexture;
        this.backdropColor = backdropColor;
        this.tagline = tagline;
        this.gravityMultiplier = gravityMultiplier;
        this.windAccelX = windAccelX;
        this.bounceMultiplier = bounceMultiplier;
    }

    public ColorRGBA getSkyColor() {
        return skyColor;
    }

    public ColorRGBA getSunTint() {
        return sunTint;
    }

    public ColorRGBA getAmbientTint() {
        return ambientTint;
    }

    public ColorRGBA getGroundColor() {
        return groundColor;
    }

    public TextureSet getGroundTexture() {
        return groundTexture;
    }

    public ColorRGBA getBackdropColor() {
        return backdropColor;
    }

    public String getTagline() {
        return tagline;
    }

    /** Multiplies the ball's normal fall/bounce gravity - e.g. a low-gravity arena floats longer. */
    public float getGravityMultiplier() {
        return gravityMultiplier;
    }

    /** Constant sideways acceleration applied to the ball every frame - a wind drift quirk. Usually 0. */
    public float getWindAccelX() {
        return windAccelX;
    }

    /** Multiplies the ball's table-bounce restitution on top of the table's own - energetic arenas hop higher/longer. */
    public float getBounceMultiplier() {
        return bounceMultiplier;
    }
}

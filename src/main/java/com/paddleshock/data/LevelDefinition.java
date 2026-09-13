package com.paddleshock.data;

import com.jme3.math.ColorRGBA;

import com.paddleshock.entities.TextureSet;

/**
 * A purchasable arena: the void/sky color, the lighting mood (tinting the same sun+ambient rig
 * every level uses), a tinted ground plane around the table, and a backdrop wall behind each end.
 * Purely atmospheric - doesn't touch gameplay, unlike the table skin's bounce stat.
 */
public class LevelDefinition extends ItemDefinition {

    private final ColorRGBA skyColor;
    private final ColorRGBA sunTint;
    private final ColorRGBA ambientTint;
    private final ColorRGBA groundColor;
    private final TextureSet groundTexture;
    private final ColorRGBA backdropColor;
    private final String tagline;

    public LevelDefinition(String id, String displayName, int price, ColorRGBA skyColor, ColorRGBA sunTint,
            ColorRGBA ambientTint, ColorRGBA groundColor, TextureSet groundTexture, ColorRGBA backdropColor,
            String tagline) {
        super(id, displayName, price);
        this.skyColor = skyColor;
        this.sunTint = sunTint;
        this.ambientTint = ambientTint;
        this.groundColor = groundColor;
        this.groundTexture = groundTexture;
        this.backdropColor = backdropColor;
        this.tagline = tagline;
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
}

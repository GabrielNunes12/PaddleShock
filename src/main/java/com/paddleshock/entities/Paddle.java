package com.paddleshock.entities;

import com.jme3.asset.AssetManager;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Cylinder;

import com.paddleshock.GameConstants;

/** A single paddle. Position moves along X (side to side) and a small Z range. */
public class Paddle {

    private final Geometry geometry;
    private final float homeZ;
    private float x = 0f;
    private float zOffset = 0f;

    private final float baseSpeedMultiplier;
    private final float baseRadiusMultiplier;
    private float buffSpeedMultiplier = 1f;
    private float buffRadiusMultiplier = 1f;

    public Paddle(AssetManager assetManager, ColorRGBA color, TextureSet textureSet, float homeZ,
            float baseSpeedMultiplier, float baseRadiusMultiplier) {
        this.homeZ = homeZ;
        this.baseSpeedMultiplier = baseSpeedMultiplier;
        this.baseRadiusMultiplier = baseRadiusMultiplier;

        Cylinder shape = new Cylinder(16, 24, GameConstants.PADDLE_RADIUS, GameConstants.PADDLE_HEIGHT, true);
        geometry = new Geometry("paddle", shape);
        geometry.setMaterial(TexturedMaterials.create(assetManager, textureSet.getColorMap(),
                textureSet.getNormalMap(), color));
        TexturedMaterials.generateTangents(geometry);
        geometry.rotate(FastMath.HALF_PI, 0, 0);
        updateTransform();
    }

    /** Adds a raw position delta (already scaled by the caller) and clamps to bounds. */
    public void moveDelta(float deltaX, float deltaZ) {
        float effectiveSpeed = baseSpeedMultiplier * buffSpeedMultiplier;
        x += deltaX * effectiveSpeed;
        zOffset += deltaZ * effectiveSpeed;

        float maxX = GameConstants.TABLE_HALF_WIDTH - getEffectiveRadius();
        x = FastMath.clamp(x, -maxX, maxX);
        zOffset = FastMath.clamp(zOffset, -GameConstants.PADDLE_Z_RANGE, GameConstants.PADDLE_Z_RANGE);

        updateTransform();
    }

    private void updateTransform() {
        geometry.setLocalTranslation(x, GameConstants.PADDLE_HEIGHT, homeZ + zOffset);
        float scale = baseRadiusMultiplier * buffRadiusMultiplier;
        geometry.setLocalScale(scale, 1f, scale);
    }

    public Vector3f getPosition() {
        return geometry.getLocalTranslation();
    }

    public Geometry getGeometry() {
        return geometry;
    }

    public float getEffectiveRadius() {
        return GameConstants.PADDLE_RADIUS * baseRadiusMultiplier * buffRadiusMultiplier;
    }

    /** Temporary multiplier from a power-up; pass 1f to clear it. */
    public void setSpeedBuff(float speedMultiplier) {
        this.buffSpeedMultiplier = speedMultiplier;
    }

    /** Temporary multiplier from a power-up; pass 1f to clear it. */
    public void setRadiusBuff(float radiusMultiplier) {
        this.buffRadiusMultiplier = radiusMultiplier;
        updateTransform();
    }
}

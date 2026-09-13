package com.paddleshock.entities;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;

import com.paddleshock.GameConstants;

/** A single paddle: an imported table-tennis paddle mesh, tinted/textured per catalog item. */
public class Paddle {

    private static final String MODEL_PATH = "Models/Paddle/paddle.glb";

    /** Uniform scale bringing the source model (~2 units wide, ~3.6 tall) down to game scale. */
    private static final float MODEL_SCALE = 0.4f;

    private final Node node;
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

        Spatial model = assetManager.loadModel(MODEL_PATH);
        Material material = TexturedMaterials.create(assetManager, textureSet.getColorMap(),
                textureSet.getNormalMap(), color);
        model.depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry geometry) {
                geometry.setMaterial(material);
            }
        });
        TexturedMaterials.generateTangents(model);
        model.setLocalScale(MODEL_SCALE);

        node = new Node("paddle");
        node.attachChild(model);
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
        node.setLocalTranslation(x, GameConstants.PADDLE_HEIGHT, homeZ + zOffset);
        float scale = baseRadiusMultiplier * buffRadiusMultiplier;
        node.setLocalScale(scale, 1f, scale);
    }

    public Vector3f getPosition() {
        return node.getLocalTranslation();
    }

    /** Directly applies a received authoritative position from a network host snapshot; a
     *  networked joiner client never calls {@link #moveDelta} on the paddle it doesn't own
     *  locally, it only renders whatever position the host last reported. */
    public void setNetworkPosition(float worldX, float worldZ) {
        this.x = worldX;
        this.zOffset = worldZ - homeZ;
        updateTransform();
    }

    public Node getNode() {
        return node;
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

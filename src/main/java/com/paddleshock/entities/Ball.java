package com.paddleshock.entities;

import com.jme3.asset.AssetManager;
import com.jme3.bounding.BoundingBox;
import com.jme3.bounding.BoundingVolume;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Sphere;

import com.paddleshock.GameConstants;

/** The ball: a real mesh (per catalog item) with an XZ velocity, gliding on the table surface. */
public class Ball {

    private final Node node;
    private final Vector3f velocity = new Vector3f();
    private final float radius;
    private final float baseSpeed;
    private final float restitutionMultiplier;

    public Ball(AssetManager assetManager, ColorRGBA color, TextureSet textureSet, BallModel ballModel,
            float speedMultiplier, float sizeMultiplier, float restitutionMultiplier) {
        this.radius = GameConstants.BALL_RADIUS * sizeMultiplier;
        this.baseSpeed = GameConstants.BALL_BASE_SPEED * speedMultiplier;
        this.restitutionMultiplier = restitutionMultiplier;

        Spatial model = ballModel.getPath() != null
                ? assetManager.loadModel(ballModel.getPath())
                : new Geometry("ballShape", new Sphere(16, 16, 1f));

        if (ballModel.isTinted()) {
            Material material = TexturedMaterials.create(assetManager, textureSet.getColorMap(),
                    textureSet.getNormalMap(), color);
            model.depthFirstTraversal(spatial -> {
                if (spatial instanceof Geometry geometry) {
                    geometry.setMaterial(material);
                }
            });
            TexturedMaterials.generateTangents(model);
        }

        // Found models carry their own baked-in scale from the source file, so size them by their
        // actual measured bounds rather than assuming the raw mesh data is exactly unit-radius.
        model.updateModelBound();
        BoundingVolume bound = model.getWorldBound();
        float nativeRadius = bound instanceof BoundingBox box
                ? Math.max(box.getXExtent(), Math.max(box.getYExtent(), box.getZExtent()))
                : 1f;
        model.setLocalScale(radius / nativeRadius);

        node = new Node("ball");
        node.attachChild(model);
        node.setLocalTranslation(0, radius, 0);
    }

    public void launch(float directionZ) {
        float angle = (float) (Math.random() * 0.6 - 0.3);
        velocity.set(
                (float) Math.sin(angle) * baseSpeed,
                0,
                Math.signum(directionZ) * (float) Math.cos(angle) * baseSpeed);
        node.setLocalTranslation(0, radius, 0);
    }

    public void update(float tpf) {
        Vector3f position = node.getLocalTranslation();
        node.setLocalTranslation(position.add(velocity.mult(tpf)));
    }

    public void bounceOffSideRail() {
        Vector3f position = node.getLocalTranslation();
        float maxX = GameConstants.TABLE_HALF_WIDTH - radius;
        float clampedX = Math.max(-maxX, Math.min(maxX, position.x));
        node.setLocalTranslation(clampedX, position.y, position.z);
        velocity.x = -velocity.x * restitutionMultiplier;
    }

    /** Reflects off a paddle, biasing X based on where the ball hit the paddle. */
    public void bounceOffPaddle(Paddle paddle) {
        float offsetX = node.getLocalTranslation().x - paddle.getPosition().x;
        float normalized = offsetX / paddle.getEffectiveRadius();

        float speed = Math.min(
                velocity.length() * GameConstants.BALL_SPEED_RAMP * restitutionMultiplier,
                GameConstants.BALL_MAX_SPEED);
        velocity.z = -velocity.z;
        velocity.x = normalized * speed * 0.6f;

        float newLength = velocity.length();
        if (newLength > 0.0001f) {
            velocity.multLocal(speed / newLength);
        }
    }

    public Vector3f getPosition() {
        return node.getLocalTranslation();
    }

    public Node getNode() {
        return node;
    }

    public float getRadius() {
        return radius;
    }
}

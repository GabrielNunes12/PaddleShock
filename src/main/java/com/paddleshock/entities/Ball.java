package com.paddleshock.entities;

import com.jme3.asset.AssetManager;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Sphere;

import com.paddleshock.GameConstants;

/** The ball: a sphere with an XZ velocity, gliding on the table surface. */
public class Ball {

    private final Geometry geometry;
    private final Vector3f velocity = new Vector3f();
    private final float radius;
    private final float baseSpeed;
    private final float restitutionMultiplier;

    public Ball(AssetManager assetManager, ColorRGBA color, TextureSet textureSet, float speedMultiplier,
            float sizeMultiplier, float restitutionMultiplier) {
        this.radius = GameConstants.BALL_RADIUS * sizeMultiplier;
        this.baseSpeed = GameConstants.BALL_BASE_SPEED * speedMultiplier;
        this.restitutionMultiplier = restitutionMultiplier;

        Sphere shape = new Sphere(16, 16, radius);
        geometry = new Geometry("ball", shape);
        geometry.setMaterial(TexturedMaterials.create(assetManager, textureSet.getColorMap(),
                textureSet.getNormalMap(), color));
        TexturedMaterials.generateTangents(geometry);
        geometry.setLocalTranslation(0, radius, 0);
    }

    public void launch(float directionZ) {
        float angle = (float) (Math.random() * 0.6 - 0.3);
        velocity.set(
                (float) Math.sin(angle) * baseSpeed,
                0,
                Math.signum(directionZ) * (float) Math.cos(angle) * baseSpeed);
        geometry.setLocalTranslation(0, radius, 0);
    }

    public void update(float tpf) {
        Vector3f position = geometry.getLocalTranslation();
        geometry.setLocalTranslation(position.add(velocity.mult(tpf)));
    }

    public void bounceOffSideRail() {
        Vector3f position = geometry.getLocalTranslation();
        float maxX = GameConstants.TABLE_HALF_WIDTH - radius;
        float clampedX = Math.max(-maxX, Math.min(maxX, position.x));
        geometry.setLocalTranslation(clampedX, position.y, position.z);
        velocity.x = -velocity.x * restitutionMultiplier;
    }

    /** Reflects off a paddle, biasing X based on where the ball hit the paddle. */
    public void bounceOffPaddle(Paddle paddle) {
        float offsetX = geometry.getLocalTranslation().x - paddle.getPosition().x;
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
        return geometry.getLocalTranslation();
    }

    public Geometry getGeometry() {
        return geometry;
    }

    public float getRadius() {
        return radius;
    }
}

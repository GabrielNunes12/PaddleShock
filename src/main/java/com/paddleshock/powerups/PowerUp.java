package com.paddleshock.powerups;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Sphere;

/** A floating pickup on the table. Bobs up and down until collected or timed out. */
public class PowerUp {

    private static final float RADIUS = 0.4f;
    private static final float LIFETIME = 12f;

    private final PowerUpType type;
    private final Geometry geometry;
    private final Vector3f basePosition;
    private float age = 0f;

    public PowerUp(AssetManager assetManager, PowerUpType type, Vector3f position) {
        this.type = type;
        this.basePosition = position.clone();

        Sphere shape = new Sphere(12, 12, RADIUS);
        geometry = new Geometry("powerup_" + type.name(), shape);
        Material material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        material.setColor("Color", type.getColor());
        geometry.setMaterial(material);
        geometry.setLocalTranslation(basePosition);
    }

    public void update(float tpf) {
        age += tpf;
        float bob = (float) Math.sin(age * 3f) * 0.15f;
        geometry.setLocalTranslation(basePosition.x, basePosition.y + bob, basePosition.z);
    }

    public boolean isExpired() {
        return age >= LIFETIME;
    }

    public boolean isWithinPickupRange(Vector3f paddlePosition, float paddleRadius) {
        float dx = paddlePosition.x - basePosition.x;
        float dz = paddlePosition.z - basePosition.z;
        float distance = (float) Math.sqrt(dx * dx + dz * dz);
        return distance < paddleRadius + RADIUS;
    }

    public PowerUpType getType() {
        return type;
    }

    public Geometry getGeometry() {
        return geometry;
    }
}

package com.paddleshock.entities;

import java.util.Random;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Box;

import com.paddleshock.data.CosmeticDefinition;

/**
 * Score-celebration cosmetic: a short burst of small tumbling pieces fired upward from a point
 * (the goal you just scored on), falling under gravity and shrinking away. Built from plain boxes
 * so it needs no particle textures. Local-view only.
 */
public class CelebrationBurst {

    private static final int PIECES = 32;
    private static final float LIFETIME = 1.4f;
    private static final float GRAVITY = 9f;

    private final Node node = new Node("celebrationBurst");
    private final Geometry[] pieces = new Geometry[PIECES];
    private final Vector3f[] velocities = new Vector3f[PIECES];
    private final Random random = new Random();
    private float age = LIFETIME;

    public CelebrationBurst(AssetManager assetManager, CosmeticDefinition celebration) {
        // Seen from the far end of the table, so the pieces need to be fairly chunky to read.
        Box box = new Box(0.17f, 0.17f, 0.05f);
        for (int i = 0; i < PIECES; i++) {
            Material material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            ColorRGBA color = celebration.isRainbow()
                    ? BallTrail.hue(i / (float) PIECES)
                    : celebration.getColor().mult(0.8f + 0.4f * random.nextFloat());
            color.a = 1f;
            material.setColor("Color", color);
            material.setColor("GlowColor", color.mult(0.6f));
            pieces[i] = new Geometry("celebrationPiece", box);
            pieces[i].setMaterial(material);
            velocities[i] = new Vector3f();
            node.attachChild(pieces[i]);
        }
        node.setCullHint(Spatial.CullHint.Always);
    }

    public Node getNode() {
        return node;
    }

    /** Fires a fresh burst from {@code origin}. */
    public void trigger(Vector3f origin) {
        age = 0f;
        node.setCullHint(Spatial.CullHint.Inherit);
        for (int i = 0; i < PIECES; i++) {
            pieces[i].setLocalTranslation(origin);
            pieces[i].setLocalScale(1f);
            float angle = random.nextFloat() * 6.2832f;
            float spread = 2f + random.nextFloat() * 3f;
            velocities[i].set((float) Math.cos(angle) * spread, 5f + random.nextFloat() * 5f,
                    (float) Math.sin(angle) * spread);
        }
    }

    public void update(float tpf) {
        if (age >= LIFETIME) {
            return;
        }
        age += tpf;
        if (age >= LIFETIME) {
            node.setCullHint(Spatial.CullHint.Always);
            return;
        }
        float scale = 1f - age / LIFETIME;
        for (int i = 0; i < PIECES; i++) {
            velocities[i].y -= GRAVITY * tpf;
            pieces[i].move(velocities[i].mult(tpf));
            pieces[i].rotate(tpf * 5f, tpf * 3f, 0f);
            pieces[i].setLocalScale(scale);
        }
    }
}

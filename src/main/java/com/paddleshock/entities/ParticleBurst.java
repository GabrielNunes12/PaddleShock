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

/**
 * A short burst of small tumbling pieces fired out from a point, falling under gravity and
 * shrinking away - used for score celebrations and impact sparks. Built from plain boxes (no
 * particle textures). {@link #trigger} can recolor each burst, so one pool serves every color.
 */
public class ParticleBurst {

    /** Shape of a burst: how many pieces, how big, how long, and how they fly. */
    public record Style(int pieces, float size, float lifetime, float gravity, float minSpread, float maxSpread,
            float minUp, float maxUp) {

        public static final Style CELEBRATION = new Style(32, 0.17f, 1.4f, 9f, 2f, 5f, 5f, 10f);
        public static final Style SPARKS = new Style(18, 0.08f, 0.4f, 14f, 2.5f, 6.5f, 1.5f, 4.5f);
    }

    private final Style style;
    private final Node node = new Node("particleBurst");
    private final Geometry[] pieces;
    private final Material[] materials;
    private final Vector3f[] velocities;
    private final Random random = new Random();
    private float age;

    public ParticleBurst(AssetManager assetManager, Style style) {
        this.style = style;
        this.age = style.lifetime();
        Box box = new Box(style.size(), style.size(), style.size() * 0.3f);
        pieces = new Geometry[style.pieces()];
        materials = new Material[style.pieces()];
        velocities = new Vector3f[style.pieces()];
        for (int i = 0; i < style.pieces(); i++) {
            materials[i] = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            pieces[i] = new Geometry("burstPiece", box);
            pieces[i].setMaterial(materials[i]);
            velocities[i] = new Vector3f();
            node.attachChild(pieces[i]);
        }
        node.setCullHint(Spatial.CullHint.Always);
    }

    public Node getNode() {
        return node;
    }

    /** Fires from {@code origin}: {@code rainbow} gives each piece its own hue, else pieces vary
     *  around {@code color}. */
    public void trigger(Vector3f origin, ColorRGBA color, boolean rainbow) {
        age = 0f;
        node.setCullHint(Spatial.CullHint.Inherit);
        for (int i = 0; i < pieces.length; i++) {
            ColorRGBA pieceColor = rainbow ? BallTrail.hue(i / (float) pieces.length)
                    : color.mult(0.8f + 0.4f * random.nextFloat());
            pieceColor.a = 1f;
            materials[i].setColor("Color", pieceColor);
            materials[i].setColor("GlowColor", pieceColor.mult(0.6f));
            pieces[i].setLocalTranslation(origin);
            pieces[i].setLocalScale(1f);
            float angle = random.nextFloat() * 6.2832f;
            float spread = style.minSpread() + random.nextFloat() * (style.maxSpread() - style.minSpread());
            velocities[i].set((float) Math.cos(angle) * spread,
                    style.minUp() + random.nextFloat() * (style.maxUp() - style.minUp()),
                    (float) Math.sin(angle) * spread);
        }
    }

    public boolean isActive() {
        return age < style.lifetime();
    }

    public void update(float tpf) {
        if (!isActive()) {
            return;
        }
        age += tpf;
        if (!isActive()) {
            node.setCullHint(Spatial.CullHint.Always);
            return;
        }
        float scale = 1f - age / style.lifetime();
        for (int i = 0; i < pieces.length; i++) {
            velocities[i].y -= style.gravity() * tpf;
            pieces[i].move(velocities[i].mult(tpf));
            pieces[i].rotate(tpf * 5f, tpf * 3f, 0f);
            pieces[i].setLocalScale(scale);
        }
    }
}

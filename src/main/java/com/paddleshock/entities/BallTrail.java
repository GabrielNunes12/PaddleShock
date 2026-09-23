package com.paddleshock.entities;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Sphere;

import com.paddleshock.data.CosmeticDefinition;

/**
 * Ball-trail cosmetic: a string of fading translucent spheres at the ball's recent positions
 * (see {@link TrailBuffer}). Local-view only. {@link #update} must be told whether the ball is
 * visible, so a Ghost Ball can't leak through its own trail.
 */
public class BallTrail {

    private static final int SEGMENTS = 14;
    private static final float MAX_ALPHA = 0.55f;

    private final Node node = new Node("ballTrail");
    private final TrailBuffer buffer = new TrailBuffer(SEGMENTS);
    private final Geometry[] segments = new Geometry[SEGMENTS];
    private final Material[] materials = new Material[SEGMENTS];
    private final CosmeticDefinition trail;
    private final float ballRadius;
    private float time;

    public BallTrail(AssetManager assetManager, CosmeticDefinition trail, float ballRadius) {
        this.trail = trail;
        this.ballRadius = ballRadius;
        Sphere sphere = new Sphere(10, 10, 1f);
        for (int i = 0; i < SEGMENTS; i++) {
            materials[i] = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            materials[i].getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
            materials[i].getAdditionalRenderState().setDepthWrite(false);
            segments[i] = new Geometry("trailSegment", sphere);
            segments[i].setMaterial(materials[i]);
            segments[i].setQueueBucket(RenderQueue.Bucket.Transparent);
            node.attachChild(segments[i]);
        }
    }

    public Node getNode() {
        return node;
    }

    public void update(Vector3f ballPosition, boolean ballVisible, float tpf) {
        time += tpf;
        if (!ballVisible) {
            buffer.clear();
            node.setCullHint(Spatial.CullHint.Always);
            return;
        }
        node.setCullHint(Spatial.CullHint.Inherit);
        buffer.push(ballPosition.x, ballPosition.y, ballPosition.z);
        for (int i = 0; i < SEGMENTS; i++) {
            if (i >= buffer.size()) {
                segments[i].setCullHint(Spatial.CullHint.Always);
                continue;
            }
            float[] p = buffer.get(i);
            float fraction = (i + 1f) / buffer.size();
            segments[i].setCullHint(Spatial.CullHint.Inherit);
            segments[i].setLocalTranslation(p[0], p[1], p[2]);
            segments[i].setLocalScale(ballRadius * (0.35f + 0.5f * fraction));
            ColorRGBA color = trail.isRainbow() ? hue(time * 0.6f + i * 0.05f) : trail.getColor().clone();
            color.a = buffer.alpha(i, MAX_ALPHA);
            materials[i].setColor("Color", color);
            materials[i].setColor("GlowColor", color.mult(color.a));
        }
    }

    /** A fully saturated color for hue in [0,1) (wrapping). */
    static ColorRGBA hue(float h) {
        float hh = (h % 1f + 1f) % 1f * 6f;
        float x = 1f - Math.abs(hh % 2f - 1f);
        return switch ((int) hh) {
            case 0 -> new ColorRGBA(1f, x, 0f, 1f);
            case 1 -> new ColorRGBA(x, 1f, 0f, 1f);
            case 2 -> new ColorRGBA(0f, 1f, x, 1f);
            case 3 -> new ColorRGBA(0f, x, 1f, 1f);
            case 4 -> new ColorRGBA(x, 0f, 1f, 1f);
            default -> new ColorRGBA(1f, 0f, x, 1f);
        };
    }
}

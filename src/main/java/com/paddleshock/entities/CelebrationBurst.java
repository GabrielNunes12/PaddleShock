package com.paddleshock.entities;

import com.jme3.asset.AssetManager;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;

import com.paddleshock.data.CosmeticDefinition;

/**
 * Score-celebration cosmetic: a {@link ParticleBurst} in the celebration's colors, fired at the
 * goal you just scored on. Local-view only.
 */
public class CelebrationBurst {

    private final ParticleBurst burst;
    private final CosmeticDefinition celebration;

    public CelebrationBurst(AssetManager assetManager, CosmeticDefinition celebration) {
        this.celebration = celebration;
        this.burst = new ParticleBurst(assetManager, ParticleBurst.Style.CELEBRATION);
    }

    public Node getNode() {
        return burst.getNode();
    }

    public void trigger(Vector3f origin) {
        burst.trigger(origin, celebration.getColor(), celebration.isRainbow());
    }

    public void update(float tpf) {
        burst.update(tpf);
    }
}

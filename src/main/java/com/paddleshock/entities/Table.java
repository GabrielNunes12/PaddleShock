package com.paddleshock.entities;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;

import com.paddleshock.GameConstants;

/** The playing surface plus side rails that bounce the ball back in. */
public class Table {

    private final Node node = new Node("table");
    private final float restitutionMultiplier;

    public Table(AssetManager assetManager, ColorRGBA surfaceColor, float restitutionMultiplier) {
        this.restitutionMultiplier = restitutionMultiplier;

        Box surfaceBox = new Box(
                GameConstants.TABLE_HALF_WIDTH,
                0.1f,
                GameConstants.TABLE_HALF_LENGTH);
        Geometry surface = new Geometry("tableSurface", surfaceBox);
        surface.setLocalTranslation(0, -0.1f, 0);
        surface.setMaterial(solidMaterial(assetManager, surfaceColor));
        node.attachChild(surface);

        float railHeight = 0.4f;
        float railThickness = 0.2f;

        Geometry leftRail = rail(assetManager, railThickness, railHeight, GameConstants.TABLE_HALF_LENGTH);
        leftRail.setLocalTranslation(-GameConstants.TABLE_HALF_WIDTH - railThickness, railHeight * 0.5f, 0);
        node.attachChild(leftRail);

        Geometry rightRail = rail(assetManager, railThickness, railHeight, GameConstants.TABLE_HALF_LENGTH);
        rightRail.setLocalTranslation(GameConstants.TABLE_HALF_WIDTH + railThickness, railHeight * 0.5f, 0);
        node.attachChild(rightRail);
    }

    private Geometry rail(AssetManager assetManager, float halfX, float halfY, float halfZ) {
        Box box = new Box(halfX, halfY, halfZ);
        Geometry geometry = new Geometry("rail", box);
        geometry.setMaterial(solidMaterial(assetManager, new ColorRGBA(0.35f, 0.4f, 0.5f, 1f)));
        return geometry;
    }

    private Material solidMaterial(AssetManager assetManager, ColorRGBA color) {
        Material material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        material.setColor("Color", color);
        return material;
    }

    public Node getNode() {
        return node;
    }

    public boolean isOutsideSideRails(Vector3f position, float radius) {
        return Math.abs(position.x) + radius > GameConstants.TABLE_HALF_WIDTH;
    }

    public float getRestitutionMultiplier() {
        return restitutionMultiplier;
    }
}

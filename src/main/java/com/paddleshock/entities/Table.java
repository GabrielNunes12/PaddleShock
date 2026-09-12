package com.paddleshock.entities;

import com.jme3.asset.AssetManager;
import com.jme3.bounding.BoundingBox;
import com.jme3.bounding.BoundingVolume;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Box;

import com.paddleshock.GameConstants;

/** The playing surface plus side rails that bounce the ball back in. */
public class Table {

    private static final String BUMPER_MODEL_PATH = "Models/Decor/bollard.glb";
    private static final float BUMPER_HEIGHT = 0.5f;

    private final Node node = new Node("table");
    private final float restitutionMultiplier;

    public Table(AssetManager assetManager, ColorRGBA surfaceColor, TextureSet textureSet,
            float restitutionMultiplier) {
        this.restitutionMultiplier = restitutionMultiplier;

        Box surfaceBox = new Box(
                GameConstants.TABLE_HALF_WIDTH,
                0.1f,
                GameConstants.TABLE_HALF_LENGTH);
        surfaceBox.scaleTextureCoordinates(new Vector2f(
                GameConstants.TABLE_HALF_WIDTH,
                GameConstants.TABLE_HALF_LENGTH));

        Geometry surface = new Geometry("tableSurface", surfaceBox);
        surface.setLocalTranslation(0, -0.1f, 0);
        surface.setMaterial(TexturedMaterials.create(assetManager, textureSet.getColorMap(),
                textureSet.getNormalMap(), surfaceColor));
        TexturedMaterials.generateTangents(surface);
        node.attachChild(surface);

        float railHeight = 0.4f;
        float railThickness = 0.2f;
        ColorRGBA railColor = new ColorRGBA(0.35f, 0.4f, 0.5f, 1f);

        Geometry leftRail = rail(assetManager, railThickness, railHeight, GameConstants.TABLE_HALF_LENGTH, railColor);
        leftRail.setLocalTranslation(-GameConstants.TABLE_HALF_WIDTH - railThickness, railHeight * 0.5f, 0);
        node.attachChild(leftRail);

        Geometry rightRail = rail(assetManager, railThickness, railHeight, GameConstants.TABLE_HALF_LENGTH, railColor);
        rightRail.setLocalTranslation(GameConstants.TABLE_HALF_WIDTH + railThickness, railHeight * 0.5f, 0);
        node.attachChild(rightRail);

        node.attachChild(buildCornerBumpers(assetManager, surfaceColor, railThickness, railHeight));
    }

    /** Decorative corner posts on the rails, tinted per table variant. Purely visual, no collision. */
    private Node buildCornerBumpers(AssetManager assetManager, ColorRGBA tint, float railThickness, float railHeight) {
        Spatial template = assetManager.loadModel(BUMPER_MODEL_PATH);
        Material material = TexturedMaterials.createSolidLit(assetManager, tint);
        template.depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry geometry) {
                geometry.setMaterial(material);
            }
        });

        template.updateModelBound();
        BoundingVolume bound = template.getWorldBound();
        float nativeHeight = bound instanceof BoundingBox box ? box.getYExtent() * 2f : 1f;
        template.setLocalScale(BUMPER_HEIGHT / nativeHeight);

        Node bumpers = new Node("cornerBumpers");
        float bumperX = GameConstants.TABLE_HALF_WIDTH + railThickness;
        float bumperZ = GameConstants.TABLE_HALF_LENGTH - 0.4f;
        float[][] corners = {{bumperX, bumperZ}, {bumperX, -bumperZ}, {-bumperX, bumperZ}, {-bumperX, -bumperZ}};

        for (float[] corner : corners) {
            Spatial instance = template.clone();
            instance.setLocalTranslation(corner[0], railHeight, corner[1]);
            bumpers.attachChild(instance);
        }
        return bumpers;
    }

    private Geometry rail(AssetManager assetManager, float halfX, float halfY, float halfZ, ColorRGBA color) {
        Box box = new Box(halfX, halfY, halfZ);
        Geometry geometry = new Geometry("rail", box);
        geometry.setMaterial(TexturedMaterials.createSolidLit(assetManager, color));
        return geometry;
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

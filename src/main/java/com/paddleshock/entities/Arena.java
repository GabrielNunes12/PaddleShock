package com.paddleshock.entities;

import com.jme3.asset.AssetManager;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;

import com.paddleshock.GameConstants;

/**
 * The ground plane and backdrop wall surrounding the table - purely atmospheric dressing driven
 * by the player's selected level, independent of the table's own surface (which keeps its own
 * gameplay-relevant bounce stat regardless of arena).
 */
public class Arena {

    private static final float GROUND_HALF_SIZE = 26f;
    private static final float GROUND_Y = -0.35f;
    private static final float WALL_HALF_WIDTH = 16f;
    private static final float WALL_HALF_HEIGHT = 6f;
    private static final float WALL_Y = 5.5f;
    private static final float WALL_Z_OFFSET = 6f;

    private final Node node = new Node("arena");

    public Arena(AssetManager assetManager, ColorRGBA groundColor, TextureSet groundTexture, ColorRGBA backdropColor) {
        Box groundBox = new Box(GROUND_HALF_SIZE, 0.15f, GROUND_HALF_SIZE);
        groundBox.scaleTextureCoordinates(new Vector2f(GROUND_HALF_SIZE, GROUND_HALF_SIZE));
        Geometry ground = new Geometry("arenaGround", groundBox);
        ground.setLocalTranslation(0, GROUND_Y, 0);
        ground.setMaterial(TexturedMaterials.create(assetManager, groundTexture.getColorMap(),
                groundTexture.getNormalMap(), groundColor));
        TexturedMaterials.generateTangents(ground);
        node.attachChild(ground);

        Box wallBox = new Box(WALL_HALF_WIDTH, WALL_HALF_HEIGHT, 0.3f);
        Geometry wall = new Geometry("arenaBackWall", wallBox);
        wall.setLocalTranslation(0, WALL_Y, GameConstants.TABLE_HALF_LENGTH + WALL_Z_OFFSET);
        wall.setMaterial(TexturedMaterials.createSolidLit(assetManager, backdropColor));
        node.attachChild(wall);
    }

    public Node getNode() {
        return node;
    }
}

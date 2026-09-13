package com.paddleshock.entities;

import java.util.Random;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Mesh.Mode;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Sphere;
import com.jme3.util.BufferUtils;

import com.paddleshock.GameConstants;

/**
 * The ground plane and backdrop wall surrounding the table - purely atmospheric dressing driven
 * by the player's selected level, independent of the table's own surface (which keeps its own
 * gameplay-relevant bounce stat regardless of arena). A "floating" level (Space Station) skips
 * the ground/wall entirely - there's no floor in orbit - and gets a starfield and a planet instead.
 */
public class Arena {

    private static final float GROUND_HALF_SIZE = 26f;
    private static final float GROUND_Y = -0.35f;
    private static final float WALL_HALF_WIDTH = 16f;
    private static final float WALL_HALF_HEIGHT = 6f;
    private static final float WALL_Y = 5.5f;
    private static final float WALL_Z_OFFSET = 6f;

    private static final int STAR_COUNT = 900;
    private static final float STAR_SPREAD_X = 90f;
    private static final float STAR_MIN_Y = -20f;
    private static final float STAR_SPREAD_Y = 60f;
    private static final float STAR_MIN_Z_OFFSET = 8f;
    private static final float STAR_SPREAD_Z = 70f;

    private final Node node = new Node("arena");

    public Arena(AssetManager assetManager, ColorRGBA groundColor, TextureSet groundTexture,
            ColorRGBA backdropColor, boolean floating) {
        if (floating) {
            buildStarfield(assetManager);
            buildPlanet(assetManager);
        } else {
            buildGround(assetManager, groundColor, groundTexture);
            buildBackdropWall(assetManager, backdropColor);
        }
    }

    private void buildGround(AssetManager assetManager, ColorRGBA groundColor, TextureSet groundTexture) {
        Box groundBox = new Box(GROUND_HALF_SIZE, 0.15f, GROUND_HALF_SIZE);
        groundBox.scaleTextureCoordinates(new Vector2f(GROUND_HALF_SIZE, GROUND_HALF_SIZE));
        Geometry ground = new Geometry("arenaGround", groundBox);
        ground.setLocalTranslation(0, GROUND_Y, 0);
        ground.setMaterial(TexturedMaterials.create(assetManager, groundTexture.getColorMap(),
                groundTexture.getNormalMap(), groundColor));
        TexturedMaterials.generateTangents(ground);
        node.attachChild(ground);
    }

    private void buildBackdropWall(AssetManager assetManager, ColorRGBA backdropColor) {
        Box wallBox = new Box(WALL_HALF_WIDTH, WALL_HALF_HEIGHT, 0.3f);
        Geometry wall = new Geometry("arenaBackWall", wallBox);
        wall.setLocalTranslation(0, WALL_Y, GameConstants.TABLE_HALF_LENGTH + WALL_Z_OFFSET);
        wall.setMaterial(TexturedMaterials.createSolidLit(assetManager, backdropColor));
        node.attachChild(wall);
    }

    /** A scatter of plain white points behind/around the table - cheap, textureless stars. */
    private void buildStarfield(AssetManager assetManager) {
        Random random = new Random();
        Vector3f[] positions = new Vector3f[STAR_COUNT];
        for (int i = 0; i < STAR_COUNT; i++) {
            float x = (random.nextFloat() - 0.5f) * STAR_SPREAD_X;
            float y = STAR_MIN_Y + random.nextFloat() * STAR_SPREAD_Y;
            float z = GameConstants.TABLE_HALF_LENGTH + STAR_MIN_Z_OFFSET + random.nextFloat() * STAR_SPREAD_Z;
            positions[i] = new Vector3f(x, y, z);
        }

        Mesh starMesh = new Mesh();
        starMesh.setMode(Mode.Points);
        starMesh.setBuffer(Type.Position, 3, BufferUtils.createFloatBuffer(positions));
        starMesh.updateBound();

        Geometry stars = new Geometry("stars", starMesh);
        Material starMaterial = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        starMaterial.setColor("Color", ColorRGBA.White);
        stars.setMaterial(starMaterial);
        node.attachChild(stars);
    }

    /** A distant lit sphere hanging in the starfield - just enough to sell "in orbit". */
    private void buildPlanet(AssetManager assetManager) {
        Sphere sphereShape = new Sphere(24, 24, 0.8f);
        Geometry planet = new Geometry("planet", sphereShape);
        Material planetMaterial = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        planetMaterial.setColor("Color", new ColorRGBA(0.85f, 0.45f, 0.25f, 1f));
        planet.setMaterial(planetMaterial);
        planet.setLocalTranslation(7f, 2f, 11f);
        node.attachChild(planet);
    }

    public Node getNode() {
        return node;
    }
}

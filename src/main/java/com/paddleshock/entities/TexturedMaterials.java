package com.paddleshock.entities;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.scene.Spatial;
import com.jme3.texture.Texture;
import com.jme3.util.TangentBinormalGenerator;

/** Shared helper for building lit, textured materials for paddles/tables/balls. */
final class TexturedMaterials {

    private TexturedMaterials() {
    }

    static Material create(AssetManager assetManager, String colorMapPath, String normalMapPath, ColorRGBA tint) {
        Material material = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");

        Texture diffuse = assetManager.loadTexture(colorMapPath);
        diffuse.setWrap(Texture.WrapMode.Repeat);
        material.setTexture("DiffuseMap", diffuse);

        Texture normal = assetManager.loadTexture(normalMapPath);
        normal.setWrap(Texture.WrapMode.Repeat);
        material.setTexture("NormalMap", normal);

        material.setBoolean("UseMaterialColors", true);
        material.setColor("Diffuse", tint);
        material.setColor("Ambient", tint);
        material.setColor("Specular", ColorRGBA.White.mult(0.6f));
        material.setFloat("Shininess", 24f);

        return material;
    }

    static Material createSolidLit(AssetManager assetManager, ColorRGBA color) {
        Material material = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
        material.setBoolean("UseMaterialColors", true);
        material.setColor("Diffuse", color);
        material.setColor("Ambient", color);
        material.setColor("Specular", ColorRGBA.White.mult(0.4f));
        material.setFloat("Shininess", 16f);
        return material;
    }

    /** Normal mapping needs tangent data that jME's primitive shapes don't generate by default. */
    static void generateTangents(Spatial spatial) {
        TangentBinormalGenerator.generate(spatial);
    }
}

package com.paddleshock.entities;

import java.nio.ByteBuffer;

import com.jme3.asset.AssetManager;
import com.jme3.math.ColorRGBA;
import com.jme3.scene.Spatial;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import com.jme3.util.SkyFactory;

/**
 * A vertical-gradient sky (zenith -> horizon -> below-horizon) generated in code as a tiny
 * equirectangular texture - no image assets needed - so every arena gets a sky instead of a flat
 * clear color. {@link #colorAt} is the pure gradient rule.
 */
public final class SkyGradient {

    private static final int HEIGHT = 128;

    private SkyGradient() {
    }

    public static Spatial create(AssetManager assetManager, ColorRGBA zenith, ColorRGBA horizon, ColorRGBA below) {
        ByteBuffer data = BufferUtils.createByteBuffer(2 * HEIGHT * 4);
        for (int y = 0; y < HEIGHT; y++) {
            // Image row 0 is the bottom of an equirect map (straight down), HEIGHT-1 the zenith.
            ColorRGBA c = colorAt(y / (HEIGHT - 1f), zenith, horizon, below);
            for (int x = 0; x < 2; x++) {
                data.put((byte) (c.r * 255)).put((byte) (c.g * 255)).put((byte) (c.b * 255)).put((byte) 255);
            }
        }
        data.flip();
        Image image = new Image(Image.Format.RGBA8, 2, HEIGHT, data, ColorSpace.Linear);
        Texture2D texture = new Texture2D(image);
        return SkyFactory.createSky(assetManager, texture, SkyFactory.EnvMapType.EquirectMap);
    }

    /** Color at {@code v} in [0,1] from straight down (0) through the horizon (0.5) to straight
     *  up (1): below-horizon color up to the horizon, then eases into the zenith color. */
    public static ColorRGBA colorAt(float v, ColorRGBA zenith, ColorRGBA horizon, ColorRGBA below) {
        if (v <= 0.5f) {
            float t = v / 0.5f;
            return new ColorRGBA().interpolateLocal(below, horizon, t * t);
        }
        float t = (v - 0.5f) / 0.5f;
        return new ColorRGBA().interpolateLocal(horizon, zenith, (float) Math.sqrt(t));
    }
}

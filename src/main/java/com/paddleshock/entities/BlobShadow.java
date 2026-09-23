package com.paddleshock.entities;

import java.nio.ByteBuffer;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Quad;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;

/**
 * A soft dark disc on the table under the ball - the cheap depth cue used when real-time shadows
 * are off (LOW quality). Shrinks and fades as the ball rises.
 */
public class BlobShadow {

    private static final int TEXTURE_SIZE = 64;
    private final Geometry geometry;
    private final Material material;
    private final float radius;
    private final com.jme3.math.ColorRGBA tint = new com.jme3.math.ColorRGBA(1f, 1f, 1f, 1f);

    public BlobShadow(AssetManager assetManager, float ballRadius) {
        this.radius = ballRadius * 1.6f;
        ByteBuffer data = BufferUtils.createByteBuffer(TEXTURE_SIZE * TEXTURE_SIZE * 4);
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                float dx = (x + 0.5f) / TEXTURE_SIZE * 2f - 1f;
                float dy = (y + 0.5f) / TEXTURE_SIZE * 2f - 1f;
                float d = FastMath.sqrt(dx * dx + dy * dy);
                // Soft edge, solid core: 1 - d^3 stays dark most of the way out, then falls off.
                float alpha = FastMath.clamp(1f - d * d * d, 0f, 1f);
                data.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) (alpha * 170));
            }
        }
        data.flip();
        Texture2D texture = new Texture2D(new Image(Image.Format.RGBA8, TEXTURE_SIZE, TEXTURE_SIZE, data, ColorSpace.Linear));

        material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        material.setTexture("ColorMap", texture);
        material.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        material.getAdditionalRenderState().setDepthWrite(false);
        // Pulled toward the camera slightly so it never z-fights the table surface it lies on.
        material.getAdditionalRenderState().setPolyOffset(-1f, -1f);
        geometry = new Geometry("ballBlobShadow", new Quad(2f, 2f));
        geometry.setMaterial(material);
        geometry.setQueueBucket(RenderQueue.Bucket.Transparent);
        geometry.rotate(-FastMath.HALF_PI, 0f, 0f);
    }

    public Geometry getGeometry() {
        return geometry;
    }

    /** Follows the ball: centered under it on the table, smaller and fainter the higher it is. */
    public void update(Vector3f ballPosition, boolean ballVisible) {
        float height = Math.max(0f, ballPosition.y - radius);
        float fade = FastMath.clamp(1f - height / 4f, 0.15f, 1f);
        float size = radius * (0.7f + 0.3f * fade);
        geometry.setCullHint(ballVisible ? com.jme3.scene.Spatial.CullHint.Inherit : com.jme3.scene.Spatial.CullHint.Always);
        geometry.setLocalScale(size, size, 1f);
        // The quad's corner sits at its origin; after the -90deg X rotation it extends +x/+z.
        geometry.setLocalTranslation(ballPosition.x - size, 0.01f, ballPosition.z + size);
        tint.a = fade;
        material.setColor("Color", tint);
    }
}

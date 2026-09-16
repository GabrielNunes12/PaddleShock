package com.paddleshock.entities;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.material.RenderState.FaceCullMode;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue.Bucket;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Mesh.Mode;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.plugins.AWTLoader;
import com.jme3.util.BufferUtils;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** A live "P : O" digit readout for the Classic Court scoreboard prop (see
 *  {@code Models/Decor/scoreboard.glb}), redrawn whenever the score changes. The model is the
 *  housing only (stand, posts, blank black panel) - it no longer ships baked digits, so this
 *  quad is the entire readout, not an overlay covering up static art.
 *
 * <p>The quad is a SIBLING of the loaded scoreboard model (attached to the same node the
 * scoreboard model itself was attached to), not a child of it and not transformed via its own
 * {@code setLocalScale}/{@code setLocalRotation} - both were tried and, for reasons not fully
 * root-caused (most likely some interaction between a non-default scale/rotation on a bare
 * hand-built Geometry and how this engine build resolves its render bucket/bounds), silently
 * failed to render at all despite every other signal - attachment, material, texture upload -
 * confirming the geometry was live and correctly parented. Baking the scoreboard model's
 * translation/rotation/scale directly into the quad's vertex positions instead (leaving the
 * Geometry itself at its default identity transform) renders correctly, so that's what this
 * does. */
public final class ScoreboardDisplay {

    private static final int TEX_W = 256;
    private static final int TEX_H = 96;

    // Native-space (pre-scale) offset/size of the panel quad, expressed in the scoreboard
    // model's own local coordinate system - measured directly from the model source (usable
    // black panel is 2.660 wide x 1.260 tall, centered at X 0.000 / Y 2.550, front surface at
    // Z 0.135) by blenderguy-0d, not eyeballed. Sized a bit inside the panel's full bounds
    // rather than filling it edge to edge.
    private static final float PANEL_WIDTH = 2.3f;
    private static final float PANEL_HEIGHT = 0.85f;
    private static final float PANEL_CENTER_X = 0.0f;
    private static final float PANEL_CENTER_Y = 2.52f;
    // Measured front surface is Z 0.135; padded well past it (rather than the bare minimum) so
    // the quad clears the panel with margin instead of relying on razor-thin, precision-
    // sensitive separation.
    private static final float PANEL_Z_OFFSET = 0.4f;

    private final Material material;
    private int lastLeft = Integer.MIN_VALUE;
    private int lastRight = Integer.MIN_VALUE;

    /** @param attachParent sibling parent to attach the quad to (the same node the scoreboard
     *          model itself was attached to) - NOT the scoreboard model itself.
     *  @param scoreboardModel the already-positioned scoreboard model, read only for its
     *          translation/rotation/scale so the quad lines up with it in world space.
     *  @param mirrored the scoreboard rotated 180&deg; (the far/opponent-facing one) reads its
     *          text left-right mirrored with the same UV mapping the near one uses correctly -
     *          confirmed empirically with a real 2-instance host/joiner match, not something
     *          worth re-deriving the exact cause of. Pass {@code true} for that one to flip U
     *          the other way and correct it. */
    public ScoreboardDisplay(AssetManager assetManager, Node attachParent, Spatial scoreboardModel,
            boolean mirrored) {
        float halfW = PANEL_WIDTH / 2f;
        float halfH = PANEL_HEIGHT / 2f;
        Vector3f[] localCorners = {
            new Vector3f(PANEL_CENTER_X - halfW, PANEL_CENTER_Y - halfH, PANEL_Z_OFFSET),
            new Vector3f(PANEL_CENTER_X + halfW, PANEL_CENTER_Y - halfH, PANEL_Z_OFFSET),
            new Vector3f(PANEL_CENTER_X + halfW, PANEL_CENTER_Y + halfH, PANEL_Z_OFFSET),
            new Vector3f(PANEL_CENTER_X - halfW, PANEL_CENTER_Y + halfH, PANEL_Z_OFFSET),
        };

        Vector3f scale = scoreboardModel.getLocalScale();
        Quaternion rotation = scoreboardModel.getLocalRotation();
        Vector3f translation = scoreboardModel.getLocalTranslation();
        Vector3f[] vertices = new Vector3f[4];
        for (int i = 0; i < 4; i++) {
            Vector3f scaled = localCorners[i].mult(scale);
            vertices[i] = rotation.mult(scaled).addLocal(translation);
        }

        // V always flipped: AWTLoader's un-flipped row order puts the source image's top row at
        // V=0 (the bottom of the quad), flipping it upside down otherwise - true regardless of
        // which way the prop is rotated. U flips the other way for the 180-degree-rotated
        // (mirrored) board - see the constructor's @param mirrored.
        float u0 = mirrored ? 0 : 1;
        float u1 = mirrored ? 1 : 0;
        Vector2f[] texCoords = {
            new Vector2f(u0, 1), new Vector2f(u1, 1), new Vector2f(u1, 0), new Vector2f(u0, 0),
        };

        Mesh mesh = new Mesh();
        mesh.setMode(Mode.Triangles);
        mesh.setBuffer(Type.Position, 3, BufferUtils.createFloatBuffer(vertices));
        mesh.setBuffer(Type.TexCoord, 2, BufferUtils.createFloatBuffer(texCoords));
        mesh.setBuffer(Type.Index, 3, new short[] {0, 1, 2, 0, 2, 3});
        mesh.updateBound();

        material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        material.getAdditionalRenderState().setBlendMode(BlendMode.Off);
        material.getAdditionalRenderState().setFaceCullMode(FaceCullMode.Off);

        Geometry geometry = new Geometry("scoreboardDisplay", mesh);
        geometry.setMaterial(material);
        geometry.setQueueBucket(Bucket.Opaque);
        attachParent.attachChild(geometry);

        update(0, 0);
    }

    /** Redraws the readout only when the score actually changed - cheap to call every HUD
     *  refresh, avoids re-rasterizing a new texture every frame it's not needed. */
    public void update(int leftScore, int rightScore) {
        if (leftScore == lastLeft && rightScore == lastRight) {
            return;
        }
        lastLeft = leftScore;
        lastRight = rightScore;
        material.setTexture("ColorMap", renderTexture(leftScore, rightScore));
    }

    private static Texture renderTexture(int leftScore, int rightScore) {
        BufferedImage image = new BufferedImage(TEX_W, TEX_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(Color.BLACK);
        g.fillRect(0, 0, TEX_W, TEX_H);

        String text = String.format("%02d : %02d", clampDigits(leftScore), clampDigits(rightScore));
        Color amber = new Color(0xFF, 0xB0, 0x00);
        Font font = new Font(Font.MONOSPACED, Font.BOLD, 56);
        g.setFont(font);
        java.awt.FontMetrics metrics = g.getFontMetrics();
        int textWidth = metrics.stringWidth(text);
        int textX = (TEX_W - textWidth) / 2;
        int textY = (TEX_H - metrics.getHeight()) / 2 + metrics.getAscent();

        // Faint glow pass (a few offset copies) under the crisp main pass - cheap stand-in for
        // real bloom, reads better against the black panel from a distance.
        g.setColor(new Color(amber.getRed(), amber.getGreen(), amber.getBlue(), 70));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                g.drawString(text, textX + dx, textY + dy);
            }
        }
        g.setColor(amber);
        g.drawString(text, textX, textY);
        g.dispose();

        Image jmeImage = new AWTLoader().load(image, false);
        Texture2D texture = new Texture2D(jmeImage);
        texture.setMagFilter(Texture.MagFilter.Bilinear);
        texture.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
        return texture;
    }

    private static int clampDigits(int score) {
        return Math.max(0, Math.min(99, score));
    }
}

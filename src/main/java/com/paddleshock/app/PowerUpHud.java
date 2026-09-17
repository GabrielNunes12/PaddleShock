package com.paddleshock.app;

import com.jme3.asset.AssetManager;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue.Bucket;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.util.BufferUtils;

import com.paddleshock.data.PowerUpDefinition;
import com.paddleshock.powerups.PowerUpManager;
import com.paddleshock.ui.Theme;

/**
 * The three power-up slot boxes (color swatch, key number, name, cooldown countdown) shown along
 * the bottom of the HUD. Purely a rendering of whatever {@link PowerUpDefinition}[] loadout and
 * {@link PowerUpManager} cooldown state it's given each frame - no simulation, networking, or
 * scene-decor concerns, so it was a self-contained piece of {@code GameplayAppState}'s HUD setup.
 * Never built at all for {@code GameplayAppState.Mode#SPECTATOR} (a viewer's own loadout is
 * irrelevant to the match it's watching) - see the caller.
 */
final class PowerUpHud {

    private static final float BOX_SIZE = 64f;
    private static final float BOX_GAP = 12f;
    private static final ColorRGBA BOX_COOLDOWN_COLOR = new ColorRGBA(0.180f, 0.196f, 0.235f, 1f);

    private final Geometry[] boxes = new Geometry[3];
    private final BitmapText[] keyTexts = new BitmapText[3];
    private final BitmapText[] iconTexts = new BitmapText[3];
    private final BitmapText[] cooldownTexts = new BitmapText[3];
    private final BitmapText[] nameTexts = new BitmapText[3];

    /** Builds the loadout's slot boxes (skipping any empty slot) at {@code boxTopY}, 20px from the
     *  left edge, spaced by {@link #BOX_SIZE} + {@link #BOX_GAP}. */
    void build(AssetManager assetManager, Node hudNode, BitmapFont font, float boxTopY, PowerUpDefinition[] loadout) {
        for (int i = 0; i < loadout.length; i++) {
            PowerUpDefinition def = loadout[i];
            if (def == null) {
                continue;
            }
            float boxX = 20 + i * (BOX_SIZE + BOX_GAP);
            boxes[i] = attachBox(assetManager, hudNode, boxX, boxTopY, def.getType().getColor());

            BitmapText keyText = new BitmapText(font);
            keyText.setSize(13);
            keyText.setColor(Theme.TEXT);
            keyText.setText(Integer.toString(i + 1));
            keyText.setLocalTranslation(boxX + 6, boxTopY - 2, 2);
            hudNode.attachChild(keyText);
            keyTexts[i] = keyText;

            BitmapText iconText = new BitmapText(font);
            iconText.setSize(28);
            iconText.setColor(Theme.ON_ACCENT);
            iconText.setText(def.getDisplayName().substring(0, 1).toUpperCase());
            iconText.setLocalTranslation(boxX + BOX_SIZE / 2f - 9, boxTopY - BOX_SIZE / 2f + 15, 2);
            hudNode.attachChild(iconText);
            iconTexts[i] = iconText;

            BitmapText cooldownText = new BitmapText(font);
            cooldownText.setSize(20);
            cooldownText.setColor(Theme.TEXT);
            cooldownText.setLocalTranslation(boxX + BOX_SIZE / 2f - 8, boxTopY - BOX_SIZE / 2f + 11, 3);
            hudNode.attachChild(cooldownText);
            cooldownTexts[i] = cooldownText;

            BitmapText nameText = new BitmapText(font);
            nameText.setSize(12);
            nameText.setColor(Theme.TEXT_DIM);
            nameText.setText(def.getDisplayName().toUpperCase());
            nameText.setLocalTranslation(boxX, boxTopY - BOX_SIZE - 6, 0);
            hudNode.attachChild(nameText);
            nameTexts[i] = nameText;
        }
    }

    /** A flat square, filled with the power-up's own color, used as its HUD slot icon. */
    private Geometry attachBox(AssetManager assetManager, Node hudNode, float x, float topY, ColorRGBA color) {
        Vector3f[] vertices = {
            new Vector3f(0, 0, 0),
            new Vector3f(BOX_SIZE, 0, 0),
            new Vector3f(BOX_SIZE, -BOX_SIZE, 0),
            new Vector3f(0, -BOX_SIZE, 0),
        };

        Mesh mesh = new Mesh();
        mesh.setBuffer(Type.Position, 3, BufferUtils.createFloatBuffer(vertices));
        mesh.setBuffer(Type.Index, 3, new short[] {0, 1, 2, 0, 2, 3});
        mesh.updateBound();

        Material material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        material.setColor("Color", color);
        material.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        material.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);

        Geometry geometry = new Geometry("powerUpBox", mesh);
        geometry.setMaterial(material);
        geometry.setQueueBucket(Bucket.Gui);
        geometry.setLocalTranslation(x, topY, 0);
        hudNode.attachChild(geometry);
        return geometry;
    }

    /** Grays a slot's box out and counts its cooldown down once used; back to full color when
     *  ready. {@code powerUpManager} is null-safe (treated as "no cooldown"), since a joiner has
     *  no local {@code PowerUpManager} to read from. */
    void update(PowerUpDefinition[] loadout, PowerUpManager powerUpManager) {
        for (int i = 0; i < boxes.length; i++) {
            Geometry box = boxes[i];
            PowerUpDefinition def = loadout[i];
            if (box == null || def == null) {
                continue;
            }
            float remaining = powerUpManager == null ? 0f
                    : powerUpManager.getPlayerCooldownRemaining(def.getType());
            boolean onCooldown = remaining > 0f;

            box.getMaterial().setColor("Color", onCooldown ? BOX_COOLDOWN_COLOR : def.getType().getColor());
            iconTexts[i].setCullHint(onCooldown ? Spatial.CullHint.Always : Spatial.CullHint.Never);
            cooldownTexts[i].setCullHint(onCooldown ? Spatial.CullHint.Never : Spatial.CullHint.Always);
            if (onCooldown) {
                cooldownTexts[i].setText(Integer.toString((int) Math.ceil(remaining)));
            }
            nameTexts[i].setColor(onCooldown ? Theme.TEXT_DIM : Theme.TEXT);
        }
    }
}

package com.paddleshock.app;

import java.util.List;

import com.jme3.asset.AssetManager;
import com.jme3.bounding.BoundingBox;
import com.jme3.bounding.BoundingVolume;
import com.jme3.math.FastMath;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;

import com.paddleshock.GameConstants;
import com.paddleshock.entities.ScoreboardDisplay;

/**
 * Builds the per-level themed side decor (bench/arcade machines/palm trees/satellite dishes, plus
 * the Classic Court scoreboard prop(s)) attached beside the table. Pure asset loading/placement -
 * no simulation, networking, or HUD concerns - so it needed nothing from {@link GameplayAppState}
 * beyond the asset manager, the level id, and whether a real opponent exists (to decide whether a
 * second, opponent-facing scoreboard is worth adding).
 */
final class SceneDecorBuilder {

    private SceneDecorBuilder() {
    }

    /** Builds the decor node for {@code levelId} and appends any {@link ScoreboardDisplay}
     *  instances created (e.g. the Classic Court scoreboard's live digit readout) to
     *  {@code scoreboardDisplaysOut} - see {@code GameplayAppState#updateScoreText}, which reads
     *  that same list back to keep every instance in sync. */
    static Node build(AssetManager assetManager, String levelId, boolean hasRealOpponent,
            List<ScoreboardDisplay> scoreboardDisplaysOut) {
        Node decor = new Node("themedDecor");
        float rightX = GameConstants.TABLE_HALF_WIDTH + 2f;
        float leftX = -GameConstants.TABLE_HALF_WIDTH - 2f;

        switch (levelId) {
            case "level_classic" -> {
                decor.attachChild(loadProp(assetManager, "Models/Decor/bench.glb", 0.7f, rightX, -2f, -0.35f));

                // Near the player's own end, beside the table (not overlapping its surface). The
                // model's front (the recessed black display panel) faces its own local +Z - see
                // ScoreboardDisplay's measured panel constants - but the single-player/host
                // camera sits at z=-11 (see GameplayAppState#setUpCamera), i.e. on the -Z side, so
                // this one needs a 180-degree turn to present that front to it instead of the
                // plain back.
                addScoreboard(assetManager, decor, scoreboardDisplaysOut, leftX, -2f, FastMath.PI, true);

                // A second one near the far end (close to the opponent's own paddle position), for
                // a real opponent to read from their own end - only meaningful in a real match, so
                // skipped in single-player. A joiner's camera is mirrored to the OPPOSITE end
                // (z=+11, see setUpCamera()'s Mode.JOINER branch), which sits on the +Z side of
                // this board - exactly where its front already faces unrotated.
                if (hasRealOpponent) {
                    addScoreboard(assetManager, decor, scoreboardDisplaysOut, leftX, 2f, 0f, false);
                }
            }
            case "level_neon" -> {
                decor.attachChild(loadProp(assetManager, "Models/Decor/arcade_machine.glb", 2.0f, rightX, -3f, FastMath.QUARTER_PI * 0.6f));
                decor.attachChild(loadProp(assetManager, "Models/Decor/arcade_machine.glb", 2.0f, leftX, -3f, -FastMath.QUARTER_PI * 0.6f));
            }
            case "level_sunset" -> {
                // Smaller and pushed further out/back than the other props - the raw models read
                // oversized and crowded the frame at the same size/spot the others use.
                decor.attachChild(loadProp(assetManager, "Models/Decor/palm_tree.glb", 2.6f, rightX + 1.5f, 1f, 0f));
                decor.attachChild(loadProp(assetManager, "Models/Decor/beach_umbrella.glb", 1.7f, leftX - 1.5f, 1f, 0f));
            }
            case "level_space" -> {
                decor.attachChild(loadProp(assetManager, "Models/Decor/satellite_dish.glb", 1.8f, rightX, -3f, 0f));
                decor.attachChild(loadProp(assetManager, "Models/Decor/satellite_dish.glb", 1.8f, leftX, -3f, FastMath.PI));
            }
            default -> {
                // No themed decor defined; the level falls back to an empty side (shouldn't happen
                // for any catalog level today).
            }
        }
        return decor;
    }

    /** Loads a scoreboard prop at the given spot beside the table and attaches its live digit
     *  readout, appended to {@code scoreboardDisplaysOut}. {@code mirrored} must be true for the
     *  180-degree-rotated (far/opponent-facing) board - see {@link ScoreboardDisplay}'s constructor. */
    private static void addScoreboard(AssetManager assetManager, Node decor,
            List<ScoreboardDisplay> scoreboardDisplaysOut, float x, float z, float rotationY, boolean mirrored) {
        Spatial scoreboard = loadProp(assetManager, "Models/Decor/scoreboard.glb", 2.4f, x, z, rotationY);
        decor.attachChild(scoreboard);
        scoreboardDisplaysOut.add(new ScoreboardDisplay(assetManager, decor, scoreboard, mirrored));
    }

    /** Loads a decor model, scales it to a target height, and places it beside the table. */
    private static Spatial loadProp(AssetManager assetManager, String modelPath, float targetHeight,
            float x, float z, float rotationY) {
        Spatial model = assetManager.loadModel(modelPath);
        scaleToHeight(model, targetHeight);
        model.rotate(0, rotationY, 0);
        model.setLocalTranslation(x, 0f, z);
        return model;
    }

    /** Scales a loaded model (whose own baked-in size varies per source file) to a target height. */
    private static void scaleToHeight(Spatial spatial, float targetHeight) {
        spatial.updateModelBound();
        BoundingVolume bound = spatial.getWorldBound();
        float nativeHeight = bound instanceof BoundingBox box ? box.getYExtent() * 2f : 1f;
        spatial.setLocalScale(targetHeight / nativeHeight);
    }
}

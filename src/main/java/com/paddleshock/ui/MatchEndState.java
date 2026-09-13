package com.paddleshock.ui;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
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
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.util.BufferUtils;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.Insets3f;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.component.SpringGridLayout;

import com.paddleshock.GameConstants;
import com.paddleshock.app.PaddleShockApp;

/**
 * Shown when a match ends: a "bold sports broadcast" scoreboard overlay over the frozen
 * table (the gameplay scene stays attached, just paused, behind this). The win screen is
 * loud and asymmetric (an accent banner bleeding off the left edge, a reward chip); the
 * defeat screen reuses the same angled-panel language but centered, smaller and muted -
 * deliberately less energy than a win.
 */
public class MatchEndState extends BaseAppState {

    private static final float BUTTON_WIDTH = 320f;

    private final Node uiRoot = new Node("matchEndUi");

    private boolean playerWon;
    private int rewardEarned;
    private int playerScore;
    private int opponentScore;

    /** Sets the outcome to display next time this state is enabled. */
    public void setResult(boolean playerWon, int rewardEarned, int playerScore, int opponentScore) {
        this.playerWon = playerWon;
        this.rewardEarned = rewardEarned;
        this.playerScore = playerScore;
        this.opponentScore = opponentScore;
    }

    @Override
    protected void initialize(Application application) {
        // Built fresh in rebuild() every time the screen is shown.
    }

    private void rebuild(PaddleShockApp app) {
        uiRoot.detachAllChildren();

        SimpleApplication simpleApp = (SimpleApplication) app;
        float screenW = simpleApp.getCamera().getWidth();
        float screenH = simpleApp.getCamera().getHeight();

        buildDimOverlay(screenW, screenH);

        if (playerWon) {
            buildWinLayout(app, screenW, screenH);
        } else {
            buildDefeatLayout(app, screenW, screenH);
        }
    }

    private void buildDimOverlay(float screenW, float screenH) {
        Container overlay = new Container();
        overlay.setBackground(new QuadBackgroundComponent(Theme.OVERLAY));
        overlay.setPreferredSize(new Vector3f(screenW, screenH, 0));
        overlay.setLocalTranslation(0, screenH, 0);
        uiRoot.attachChild(overlay);
    }

    private void buildWinLayout(PaddleShockApp app, float screenW, float screenH) {
        // Headline banner: bleeds off the left edge, orange fill, dark (ON_ACCENT) text.
        attachAngledQuad(-60, screenH - 84, 148, 0, 660, 660 * 0.86f, 0, Theme.ORANGE, 1);
        attachText(36, screenH - 124, "YOU WIN", 56, Theme.ON_ACCENT);

        // Scoreboard tile: winner's number bright, loser's dim.
        attachAngledQuad(40, screenH - 268, 128, 0, 460, 460, 24, Theme.PANEL_HOVER, 1);
        attachScoreboard(40, 460, screenH - 268, 128, playerScore, opponentScore, Theme.TEXT, Theme.TEXT_DIM);

        // Reward chip.
        attachAngledQuad(528, screenH - 316, 60, 14, 232, 232, 0, Theme.GREEN, 1);
        attachText(550, screenH - 335, "+" + rewardEarned + " CREDITS", 20, Theme.ON_ACCENT);

        // Actions: left-anchored, matching the banner's asymmetric composition.
        Container actions = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        addMenuButton(actions, "REMATCH", Theme.ORANGE, Theme.ON_ACCENT, app::showLoadout);
        addMenuButton(actions, "STORE", Theme.PANEL_HOVER, Theme.TEXT, app::showStore);
        addMenuButton(actions, "MAIN MENU", Theme.PANEL_HOVER, Theme.TEXT, app::showMainMenu);
        actions.setLocalTranslation(40, screenH - 460, 2);
        uiRoot.attachChild(actions);
    }

    private void buildDefeatLayout(PaddleShockApp app, float screenW, float screenH) {
        float blockHeight = 84 + 24 + 108 + 20 + 24 + 24 + 96;
        float topY = (screenH + blockHeight) / 2f;
        float centerX = screenW / 2f;

        float bannerWidth = 340;
        attachAngledQuad(centerX - bannerWidth / 2f, topY, 84, 12, bannerWidth, bannerWidth, 0, Theme.PANEL_HOVER, 1);
        attachText(centerX - bannerWidth / 2f + 44, topY - 30, "DEFEAT", 36, Theme.TEXT);

        float scoreY = topY - 84 - 24;
        float scoreWidth = 320;
        attachAngledQuad(centerX - scoreWidth / 2f, scoreY, 108, 0, scoreWidth, scoreWidth, 18, Theme.PANEL, 1);
        attachScoreboard(centerX - scoreWidth / 2f, scoreWidth, scoreY, 108, playerScore, opponentScore, Theme.TEXT_DIM, Theme.TEXT);

        float consolationY = scoreY - 108 - 20;
        attachCenteredText(centerX, consolationY, "First to " + GameConstants.WIN_SCORE + " wins. Try again!", 16, Theme.TEXT_DIM);

        Container actions = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        addMenuButton(actions, "REMATCH", Theme.ORANGE, Theme.ON_ACCENT, app::showLoadout);
        addMenuButton(actions, "MAIN MENU", Theme.PANEL_HOVER, Theme.TEXT, app::showMainMenu);
        Vector3f actionsSize = actions.getPreferredSize();
        actions.setLocalTranslation(centerX - actionsSize.x / 2f, consolationY - 24, 2);
        uiRoot.attachChild(actions);
    }

    /** Big "playerScore : opponentScore" readout plus a small caption, roughly centered in the given tile. */
    private void attachScoreboard(float tileX, float tileWidth, float tileTopY, float tileHeight,
            int leftScore, int rightScore, ColorRGBA leftColor, ColorRGBA rightColor) {
        String left = Integer.toString(leftScore);
        String right = Integer.toString(rightScore);
        float centerX = tileX + tileWidth / 2f;
        float textY = tileTopY - (tileHeight - 60) / 2f;

        // Rough visual centering for the "N : N" readout without measuring glyph widths.
        float totalWidth = (left.length() + right.length()) * 34f + 40f;
        float cursorX = centerX - totalWidth / 2f;

        attachText(cursorX, textY, left, 48, leftColor);
        cursorX += left.length() * 34f + 8f;
        attachText(cursorX, textY - 8, ":", 28, Theme.TEXT_DIM);
        cursorX += 24f;
        attachText(cursorX, textY, right, 48, rightColor);

        attachCenteredText(centerX, tileTopY - tileHeight + 22, "FINAL SCORE", 11, Theme.TEXT_DIM);
    }

    private void addMenuButton(Container menu, String label, ColorRGBA bg, ColorRGBA fg, Runnable action) {
        Button button = menu.addChild(new Button(label));
        button.setInsets(new Insets3f(6, 0, 6, 0));
        button.setBackground(new QuadBackgroundComponent(bg));
        button.setColor(fg);
        button.setFontSize(16);
        button.setPreferredSize(new Vector3f(BUTTON_WIDTH, 46, 0));
        button.addClickCommands(source -> {
            ((PaddleShockApp) getApplication()).getAudioManager().playSfx("button_click.ogg");
            action.run();
        });
    }

    private void attachText(float x, float topY, String text, float size, ColorRGBA color) {
        BitmapText bitmapText = new BitmapText(getFont());
        bitmapText.setSize(size);
        bitmapText.setColor(color);
        bitmapText.setText(text);
        bitmapText.setLocalTranslation(x, topY, 3);
        uiRoot.attachChild(bitmapText);
    }

    private void attachCenteredText(float centerX, float topY, String text, float size, ColorRGBA color) {
        BitmapText bitmapText = new BitmapText(getFont());
        bitmapText.setSize(size);
        bitmapText.setColor(color);
        bitmapText.setText(text);
        bitmapText.setLocalTranslation(centerX - bitmapText.getLineWidth() / 2f, topY, 3);
        uiRoot.attachChild(bitmapText);
    }

    private BitmapFont getFont() {
        return ((SimpleApplication) getApplication()).getAssetManager().loadFont("Interface/Fonts/Default.fnt");
    }

    /**
     * A flat quad clipped to an arbitrary trapezoid, e.g. {@code clip-path: polygon(...)} in CSS -
     * used for the angled banner/scoreboard/chip shapes instead of plain rectangles.
     * {@code x}/{@code topY} anchor the shape's top-left bounding corner; the four x-offsets (from
     * {@code x}) place each of the top-left, top-right, bottom-right and bottom-left corners.
     */
    private void attachAngledQuad(float x, float topY, float height,
            float topLeftX, float topRightX, float bottomRightX, float bottomLeftX, ColorRGBA color, float z) {
        Vector3f[] vertices = {
            new Vector3f(topLeftX, 0, 0),
            new Vector3f(topRightX, 0, 0),
            new Vector3f(bottomRightX, -height, 0),
            new Vector3f(bottomLeftX, -height, 0),
        };

        Mesh mesh = new Mesh();
        mesh.setBuffer(Type.Position, 3, BufferUtils.createFloatBuffer(vertices));
        mesh.setBuffer(Type.Index, 3, new short[] {0, 1, 2, 0, 2, 3});
        mesh.updateBound();

        Material material = new Material(getApplication().getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        material.setColor("Color", color);
        material.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        material.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);

        Geometry geometry = new Geometry("matchEndPanel", mesh);
        geometry.setMaterial(material);
        geometry.setQueueBucket(Bucket.Gui);
        geometry.setLocalTranslation(x, topY, z);
        uiRoot.attachChild(geometry);
    }

    @Override
    protected void cleanup(Application application) {
        // uiRoot is detached in onDisable(); nothing else owns native resources here.
    }

    @Override
    protected void onEnable() {
        rebuild((PaddleShockApp) getApplication());
        ((SimpleApplication) getApplication()).getGuiNode().attachChild(uiRoot);
        getApplication().getInputManager().setCursorVisible(true);
    }

    @Override
    protected void onDisable() {
        uiRoot.removeFromParent();
    }
}

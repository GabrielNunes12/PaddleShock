package com.paddleshock.app;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.input.InputManager;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;

import java.util.ArrayList;
import java.util.List;

import com.paddleshock.GameConstants;
import com.paddleshock.data.BallDefinition;
import com.paddleshock.data.Catalog;
import com.paddleshock.data.PaddleDefinition;
import com.paddleshock.data.PlayerProfile;
import com.paddleshock.data.PowerUpDefinition;
import com.paddleshock.data.TableDefinition;
import com.paddleshock.entities.Ball;
import com.paddleshock.entities.Paddle;
import com.paddleshock.entities.Table;
import com.paddleshock.entities.TextureSet;
import com.paddleshock.input.PlayerInput;
import com.paddleshock.powerups.PowerUpManager;
import com.paddleshock.ui.Theme;

/** A single match vs. the AI: scene setup, per-frame simulation, scoring, pause key. */
public class GameplayAppState extends BaseAppState implements ActionListener {

    private static final String ACTION_PAUSE = "PS_Pause";
    private static final String[] POWERUP_ACTIONS = {"PS_PowerUp1", "PS_PowerUp2", "PS_PowerUp3"};
    private static final int[] POWERUP_KEYS = {KeyInput.KEY_1, KeyInput.KEY_2, KeyInput.KEY_3};
    private static final float AI_MAX_SPEED = 6.5f;
    private static final float AI_POWERUP_MIN_INTERVAL = 3f;
    private static final float AI_POWERUP_MAX_INTERVAL = 6f;

    private final Node gameNode = new Node("gameplayRoot");
    private final Node hudNode = new Node("gameplayHud");

    private PaddleShockApp app;
    private Table table;
    private Paddle playerPaddle;
    private Paddle opponentPaddle;
    private Ball ball;
    private final PlayerInput playerInput = new PlayerInput();
    private PowerUpManager powerUpManager;
    private final PowerUpDefinition[] powerUpLoadout = new PowerUpDefinition[3];
    private final BitmapText[] powerUpTexts = new BitmapText[3];
    private float aiPowerUpTimer = AI_POWERUP_MIN_INTERVAL;

    private int playerScore = 0;
    private int opponentScore = 0;
    private BitmapText scoreText;

    private final Vector3f screenRightWorld = new Vector3f();
    private final Vector3f screenUpWorld = new Vector3f();

    @Override
    protected void initialize(Application application) {
        this.app = (PaddleShockApp) application;
        SimpleApplication simpleApp = (SimpleApplication) application;

        setUpCamera(simpleApp);
        setUpLights();
        setUpScene();
        setUpHud(simpleApp);

        playerInput.register(app.getInputManager());
        registerPauseKey(app.getInputManager());
        registerPowerUpKeys(app.getInputManager());

        simpleApp.getRootNode().attachChild(gameNode);
        simpleApp.getGuiNode().attachChild(hudNode);

        ball.launch(Math.random() < 0.5 ? 1f : -1f);
    }

    private void setUpCamera(SimpleApplication simpleApp) {
        simpleApp.getCamera().setLocation(new Vector3f(0, 7f, -11f));
        simpleApp.getCamera().lookAt(new Vector3f(0, 0, -1f), Vector3f.UNIT_Y);

        // Map mouse movement to table-plane directions using the camera's actual
        // orientation, rather than assuming screen-right is world +X: the camera
        // is angled, so that assumption was inverting the paddle's controls.
        Vector3f camLeft = simpleApp.getCamera().getLeft();
        screenRightWorld.set(-camLeft.x, 0, -camLeft.z).normalizeLocal();

        Vector3f camDirection = simpleApp.getCamera().getDirection();
        screenUpWorld.set(camDirection.x, 0, camDirection.z).normalizeLocal();
    }

    private void setUpLights() {
        float brightness = app.getGameSettings().getBrightness();

        DirectionalLight sun = new DirectionalLight();
        sun.setDirection(new Vector3f(-0.5f, -1f, -0.5f).normalizeLocal());
        sun.setColor(ColorRGBA.White.mult(1.1f * brightness));
        gameNode.addLight(sun);

        AmbientLight ambient = new AmbientLight();
        ambient.setColor(ColorRGBA.White.mult(0.6f * brightness));
        gameNode.addLight(ambient);
    }

    private void setUpScene() {
        PlayerProfile profile = app.getProfile();

        PaddleDefinition paddleDef = Catalog.findPaddle(profile.getEquippedId("paddle"))
                .orElse(Catalog.PADDLES.get(0));
        TableDefinition tableDef = Catalog.findTable(profile.getEquippedId("table"))
                .orElse(Catalog.TABLES.get(0));
        BallDefinition ballDef = Catalog.findBall(profile.getEquippedId("ball"))
                .orElse(Catalog.BALLS.get(0));

        table = new Table(getApplication().getAssetManager(), tableDef.getSurfaceColor(),
                tableDef.getTextureSet(), tableDef.getRestitutionMultiplier());
        gameNode.attachChild(table.getNode());

        playerPaddle = new Paddle(getApplication().getAssetManager(), paddleDef.getColor(), paddleDef.getTextureSet(),
                GameConstants.PADDLE_PLAYER_Z, paddleDef.getSpeedMultiplier(), paddleDef.getSizeMultiplier());
        gameNode.attachChild(playerPaddle.getNode());

        opponentPaddle = new Paddle(getApplication().getAssetManager(), new ColorRGBA(1f, 0.35f, 0.3f, 1f),
                TextureSet.PLASTIC, GameConstants.PADDLE_OPPONENT_Z, 1f, 1f);
        gameNode.attachChild(opponentPaddle.getNode());

        ball = new Ball(getApplication().getAssetManager(), ballDef.getColor(), ballDef.getTextureSet(),
                ballDef.getBallModel(), ballDef.getSpeedMultiplier(), ballDef.getSizeMultiplier(),
                tableDef.getRestitutionMultiplier());
        gameNode.attachChild(ball.getNode());

        powerUpManager = new PowerUpManager(playerPaddle, opponentPaddle);
        resolveLoadout(profile);

        gameNode.attachChild(buildSideDecor());
        gameNode.attachChild(buildTrophyDecor());
    }

    /** Resolves the player's 3 store-configured power-up slots into their catalog definitions. */
    private void resolveLoadout(PlayerProfile profile) {
        List<String> loadout = profile.getLoadout();
        for (int i = 0; i < powerUpLoadout.length; i++) {
            String id = loadout.get(i);
            powerUpLoadout[i] = id.isEmpty() ? null : Catalog.findPowerUp(id).orElse(null);
        }
    }

    /** A miniature ping-pong table (with its own tiny paddles/net/ball) as a display piece beside the real table. */
    private Spatial buildSideDecor() {
        Spatial decor = getApplication().getAssetManager().loadModel("Models/Decor/pingpong.glb");
        decor.setLocalScale(3f);
        decor.rotate(0, FastMath.QUARTER_PI * 0.6f, 0);
        decor.setLocalTranslation(GameConstants.TABLE_HALF_WIDTH + 2f, 0f, -3f);
        return decor;
    }

    /** A trophy display piece on the opposite side of the table from the mini ping-pong table. */
    private Spatial buildTrophyDecor() {
        Spatial trophy = getApplication().getAssetManager().loadModel("Models/Decor/trophy.glb");
        scaleToHeight(trophy, 1.4f);
        trophy.setLocalTranslation(-GameConstants.TABLE_HALF_WIDTH - 2f, 0f, -3f);
        return trophy;
    }

    /** Scales a loaded model (whose own baked-in size varies per source file) to a target height. */
    private void scaleToHeight(Spatial spatial, float targetHeight) {
        spatial.updateModelBound();
        com.jme3.bounding.BoundingVolume bound = spatial.getWorldBound();
        float nativeHeight = bound instanceof com.jme3.bounding.BoundingBox box ? box.getYExtent() * 2f : 1f;
        spatial.setLocalScale(targetHeight / nativeHeight);
    }

    private void setUpHud(SimpleApplication simpleApp) {
        BitmapFont font = simpleApp.getAssetManager().loadFont("Interface/Fonts/Default.fnt");
        scoreText = new BitmapText(font);
        scoreText.setSize(28);
        scoreText.setLocalTranslation(20, simpleApp.getCamera().getHeight() - 20, 0);
        hudNode.attachChild(scoreText);
        updateScoreText();

        for (int i = 0; i < powerUpLoadout.length; i++) {
            if (powerUpLoadout[i] == null) {
                continue;
            }
            BitmapText text = new BitmapText(font);
            text.setSize(16);
            text.setLocalTranslation(20 + i * 230f, simpleApp.getCamera().getHeight() - 56, 0);
            hudNode.attachChild(text);
            powerUpTexts[i] = text;
        }
        updatePowerUpHud();
    }

    private void updateScoreText() {
        scoreText.setText("You " + playerScore + " : " + opponentScore + " AI  (Esc: pause)");
    }

    private void updatePowerUpHud() {
        for (int i = 0; i < powerUpTexts.length; i++) {
            BitmapText text = powerUpTexts[i];
            PowerUpDefinition def = powerUpLoadout[i];
            if (text == null || def == null) {
                continue;
            }
            float remaining = powerUpManager == null ? 0f : powerUpManager.getPlayerCooldownRemaining(def.getType());
            String key = "[" + (i + 1) + "] ";
            if (remaining > 0f) {
                text.setText(key + def.getDisplayName().toUpperCase() + "  " + (int) Math.ceil(remaining) + "s");
                text.setColor(Theme.TEXT_DIM);
            } else {
                text.setText(key + def.getDisplayName().toUpperCase());
                text.setColor(Theme.ORANGE);
            }
        }
    }

    private void registerPauseKey(InputManager inputManager) {
        inputManager.addMapping(ACTION_PAUSE, new KeyTrigger(KeyInput.KEY_ESCAPE));
        inputManager.addListener(this, ACTION_PAUSE);
    }

    private void registerPowerUpKeys(InputManager inputManager) {
        for (int i = 0; i < POWERUP_ACTIONS.length; i++) {
            inputManager.addMapping(POWERUP_ACTIONS[i], new KeyTrigger(POWERUP_KEYS[i]));
            inputManager.addListener(this, POWERUP_ACTIONS[i]);
        }
    }

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        if (ACTION_PAUSE.equals(name) && isPressed && isEnabled()) {
            app.showPause();
            return;
        }
        if (!isPressed || !isEnabled()) {
            return;
        }
        for (int i = 0; i < POWERUP_ACTIONS.length; i++) {
            if (POWERUP_ACTIONS[i].equals(name)) {
                activatePlayerPowerUp(i);
                return;
            }
        }
    }

    private void activatePlayerPowerUp(int slot) {
        PowerUpDefinition def = powerUpLoadout[slot];
        if (def == null) {
            return;
        }
        powerUpManager.activatePlayerPowerUp(def.getType(), def.getCooldownSeconds());
    }

    @Override
    public void update(float tpf) {
        float[] mouseDelta = playerInput.consumeDelta();
        float scale = GameConstants.MOUSE_SENSITIVITY * app.getGameSettings().getMouseSensitivity();
        float worldDeltaX = (screenRightWorld.x * mouseDelta[0] + screenUpWorld.x * mouseDelta[1]) * scale;
        float worldDeltaZ = (screenRightWorld.z * mouseDelta[0] + screenUpWorld.z * mouseDelta[1]) * scale;
        playerPaddle.moveDelta(worldDeltaX, worldDeltaZ);
        updateOpponentAi(tpf);

        ball.update(tpf);
        powerUpManager.update(tpf);
        updateAiPowerUps(tpf);
        updatePowerUpHud();
        handleCollisions();
    }

    private void updateOpponentAi(float tpf) {
        float toBall = ball.getPosition().x - opponentPaddle.getPosition().x;
        float maxStep = AI_MAX_SPEED * tpf;
        float step = Math.max(-maxStep, Math.min(maxStep, toBall));
        opponentPaddle.moveDelta(step, 0);
    }

    /** The AI mirrors the player's own loadout (there's no separate AI/ranked kit yet) and fires
     *  a random ready one every few seconds, so bought power-ups don't just favor the player. */
    private void updateAiPowerUps(float tpf) {
        aiPowerUpTimer -= tpf;
        if (aiPowerUpTimer > 0f) {
            return;
        }
        aiPowerUpTimer = AI_POWERUP_MIN_INTERVAL
                + (float) (Math.random() * (AI_POWERUP_MAX_INTERVAL - AI_POWERUP_MIN_INTERVAL));

        List<PowerUpDefinition> ready = new ArrayList<>();
        for (PowerUpDefinition def : powerUpLoadout) {
            if (def != null && powerUpManager.isAiReady(def.getType())) {
                ready.add(def);
            }
        }
        if (ready.isEmpty()) {
            return;
        }
        PowerUpDefinition chosen = ready.get((int) (Math.random() * ready.size()));
        powerUpManager.activateAiPowerUp(chosen.getType(), chosen.getCooldownSeconds());
    }

    private void handleCollisions() {
        Vector3f pos = ball.getPosition();

        if (table.isOutsideSideRails(pos, ball.getRadius())) {
            ball.bounceOffSideRail();
        }

        tryPaddleBounce(playerPaddle);
        tryPaddleBounce(opponentPaddle);

        if (pos.z < -GameConstants.TABLE_HALF_LENGTH) {
            opponentScore++;
            updateScoreText();
            if (opponentScore >= GameConstants.WIN_SCORE) {
                app.endMatch(false, playerScore, opponentScore);
            } else {
                ball.launch(1f);
            }
        } else if (pos.z > GameConstants.TABLE_HALF_LENGTH) {
            playerScore++;
            updateScoreText();
            if (playerScore >= GameConstants.WIN_SCORE) {
                app.endMatch(true, playerScore, opponentScore);
            } else {
                ball.launch(-1f);
            }
        }
    }

    private void tryPaddleBounce(Paddle paddle) {
        Vector3f ballPos = ball.getPosition();
        Vector3f paddlePos = paddle.getPosition();

        float zGap = ballPos.z - paddlePos.z;
        boolean withinReach = Math.abs(zGap) < (ball.getRadius() + GameConstants.PADDLE_HEIGHT);
        boolean withinPaddleWidth = Math.abs(ballPos.x - paddlePos.x) < paddle.getEffectiveRadius() + ball.getRadius();

        if (withinReach && withinPaddleWidth) {
            ball.bounceOffPaddle(paddle);
        }
    }

    public void startNewMatch() {
        playerScore = 0;
        opponentScore = 0;
        updateScoreText();
        ball.launch(Math.random() < 0.5 ? 1f : -1f);
    }

    @Override
    protected void cleanup(Application application) {
        SimpleApplication simpleApp = (SimpleApplication) application;
        simpleApp.getRootNode().detachChild(gameNode);
        simpleApp.getGuiNode().detachChild(hudNode);
        simpleApp.getInputManager().deleteMapping(ACTION_PAUSE);
        for (String action : POWERUP_ACTIONS) {
            simpleApp.getInputManager().deleteMapping(action);
        }
        simpleApp.getInputManager().removeListener(this);
    }

    @Override
    protected void onEnable() {
        getApplication().getInputManager().setCursorVisible(false);
    }

    @Override
    protected void onDisable() {
        // Pause/store/options states manage cursor visibility themselves while active.
    }
}

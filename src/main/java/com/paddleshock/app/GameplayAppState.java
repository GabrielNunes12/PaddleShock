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
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;

import com.paddleshock.GameConstants;
import com.paddleshock.data.BallDefinition;
import com.paddleshock.data.Catalog;
import com.paddleshock.data.PaddleDefinition;
import com.paddleshock.data.PlayerProfile;
import com.paddleshock.data.TableDefinition;
import com.paddleshock.entities.Ball;
import com.paddleshock.entities.Paddle;
import com.paddleshock.entities.Table;
import com.paddleshock.input.PlayerInput;
import com.paddleshock.powerups.PowerUpManager;

/** A single match vs. the AI: scene setup, per-frame simulation, scoring, pause key. */
public class GameplayAppState extends BaseAppState implements ActionListener {

    private static final String ACTION_PAUSE = "PS_Pause";
    private static final float AI_MAX_SPEED = 6.5f;

    private final Node gameNode = new Node("gameplayRoot");
    private final Node hudNode = new Node("gameplayHud");

    private PaddleShockApp app;
    private Table table;
    private Paddle playerPaddle;
    private Paddle opponentPaddle;
    private Ball ball;
    private final PlayerInput playerInput = new PlayerInput();
    private PowerUpManager powerUpManager;

    private int playerScore = 0;
    private int opponentScore = 0;
    private BitmapText scoreText;

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

        simpleApp.getRootNode().attachChild(gameNode);
        simpleApp.getGuiNode().attachChild(hudNode);

        ball.launch(Math.random() < 0.5 ? 1f : -1f);
    }

    private void setUpCamera(SimpleApplication simpleApp) {
        simpleApp.getCamera().setLocation(new Vector3f(0, 7f, -11f));
        simpleApp.getCamera().lookAt(new Vector3f(0, 0, -1f), Vector3f.UNIT_Y);
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
                tableDef.getRestitutionMultiplier());
        gameNode.attachChild(table.getNode());

        playerPaddle = new Paddle(getApplication().getAssetManager(), paddleDef.getColor(),
                GameConstants.PADDLE_PLAYER_Z, paddleDef.getSpeedMultiplier(), paddleDef.getSizeMultiplier());
        gameNode.attachChild(playerPaddle.getGeometry());

        opponentPaddle = new Paddle(getApplication().getAssetManager(), new ColorRGBA(1f, 0.35f, 0.3f, 1f),
                GameConstants.PADDLE_OPPONENT_Z, 1f, 1f);
        gameNode.attachChild(opponentPaddle.getGeometry());

        ball = new Ball(getApplication().getAssetManager(), ballDef.getColor(),
                ballDef.getSpeedMultiplier(), ballDef.getSizeMultiplier(), tableDef.getRestitutionMultiplier());
        gameNode.attachChild(ball.getGeometry());

        powerUpManager = new PowerUpManager(getApplication().getAssetManager(), gameNode, playerPaddle, opponentPaddle);
    }

    private void setUpHud(SimpleApplication simpleApp) {
        BitmapFont font = simpleApp.getAssetManager().loadFont("Interface/Fonts/Default.fnt");
        scoreText = new BitmapText(font);
        scoreText.setSize(font.getCharSet().getRenderedSize() * 2f);
        scoreText.setLocalTranslation(20, simpleApp.getCamera().getHeight() - 20, 0);
        hudNode.attachChild(scoreText);
        updateScoreText();
    }

    private void updateScoreText() {
        scoreText.setText("You " + playerScore + " : " + opponentScore + " AI  (Esc: pause)");
    }

    private void registerPauseKey(InputManager inputManager) {
        inputManager.addMapping(ACTION_PAUSE, new KeyTrigger(KeyInput.KEY_ESCAPE));
        inputManager.addListener(this, ACTION_PAUSE);
    }

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        if (ACTION_PAUSE.equals(name) && isPressed && isEnabled()) {
            app.showPause();
        }
    }

    @Override
    public void update(float tpf) {
        float[] mouseDelta = playerInput.consumeDelta();
        playerPaddle.moveDelta(
                mouseDelta[0] * GameConstants.MOUSE_SENSITIVITY * app.getGameSettings().getMouseSensitivity(),
                mouseDelta[1] * GameConstants.MOUSE_SENSITIVITY * app.getGameSettings().getMouseSensitivity());
        updateOpponentAi(tpf);

        ball.update(tpf);
        powerUpManager.update(tpf);
        handleCollisions();
    }

    private void updateOpponentAi(float tpf) {
        float toBall = ball.getPosition().x - opponentPaddle.getPosition().x;
        float maxStep = AI_MAX_SPEED * tpf;
        float step = Math.max(-maxStep, Math.min(maxStep, toBall));
        opponentPaddle.moveDelta(step, 0);
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
            ball.launch(1f);
        } else if (pos.z > GameConstants.TABLE_HALF_LENGTH) {
            playerScore++;
            updateScoreText();
            ball.launch(-1f);
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

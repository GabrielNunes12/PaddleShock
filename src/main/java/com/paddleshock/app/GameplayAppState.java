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
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue.Bucket;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.util.BufferUtils;

import java.util.ArrayList;
import java.util.List;

import com.paddleshock.GameConstants;
import com.paddleshock.data.BallDefinition;
import com.paddleshock.data.Catalog;
import com.paddleshock.data.LevelDefinition;
import com.paddleshock.data.PaddleDefinition;
import com.paddleshock.data.PlayerProfile;
import com.paddleshock.data.PowerUpDefinition;
import com.paddleshock.data.TableDefinition;
import com.paddleshock.entities.Arena;
import com.paddleshock.entities.Ball;
import com.paddleshock.entities.Paddle;
import com.paddleshock.entities.Table;
import com.paddleshock.entities.TextureSet;
import com.paddleshock.input.PlayerInput;
import com.paddleshock.net.NetClient;
import com.paddleshock.net.NetHost;
import com.paddleshock.net.NetProtocol;
import com.paddleshock.sim.MatchSimulation;
import com.paddleshock.sim.PaddleInput;
import com.paddleshock.sim.TickResult;
import com.paddleshock.ui.Theme;

/**
 * A single match: scene setup, per-frame simulation, scoring, pause key. Runs in one of three
 * {@link Mode}s - unchanged single-player-vs-AI, listen-server host (runs {@link MatchSimulation}
 * and broadcasts snapshots to a joiner), or joiner (sends local input, renders received
 * snapshots, runs no simulation of its own). The scene/HUD setup and local-input gathering are
 * shared by all three; only {@link #update(float)} branches by mode.
 */
public class GameplayAppState extends BaseAppState implements ActionListener {

    /** Which role this instance of the gameplay state is playing. */
    public enum Mode { SINGLE_PLAYER, HOST, JOINER }

    private static final String ACTION_PAUSE = "PS_Pause";
    private static final String[] POWERUP_ACTIONS = {"PS_PowerUp1", "PS_PowerUp2", "PS_PowerUp3"};
    private static final int[] POWERUP_KEYS = {KeyInput.KEY_1, KeyInput.KEY_2, KeyInput.KEY_3};
    private static final float AI_MAX_SPEED = 6.5f;
    private static final float AI_POWERUP_MIN_INTERVAL = 3f;
    private static final float AI_POWERUP_MAX_INTERVAL = 6f;
    private static final float POWERUP_BOX_SIZE = 64f;
    private static final float POWERUP_BOX_GAP = 12f;
    private static final ColorRGBA POWERUP_BOX_COOLDOWN_COLOR = new ColorRGBA(0.180f, 0.196f, 0.235f, 1f);

    private final Node gameNode = new Node("gameplayRoot");
    private final Node hudNode = new Node("gameplayHud");

    private PaddleShockApp app;
    private LevelDefinition level;
    private Table table;
    private Paddle playerPaddle;
    private Paddle opponentPaddle;
    private Ball ball;
    private final PlayerInput playerInput = new PlayerInput();
    private MatchSimulation matchSimulation;
    private final PowerUpDefinition[] powerUpLoadout = new PowerUpDefinition[3];
    private final Geometry[] powerUpBoxes = new Geometry[3];
    private final BitmapText[] powerUpKeyTexts = new BitmapText[3];
    private final BitmapText[] powerUpIconTexts = new BitmapText[3];
    private final BitmapText[] powerUpCooldownTexts = new BitmapText[3];
    private final BitmapText[] powerUpNameTexts = new BitmapText[3];
    private float aiPowerUpTimer = AI_POWERUP_MIN_INTERVAL;

    /** Set by the key-1/2/3 handler, consumed (and cleared) on the very next {@link #update}, so it
     *  reaches {@link MatchSimulation#tick} as part of the same tick-shaped input the future remote
     *  opponent will also send its activations through. */
    private Integer pendingPlayerPowerUpSlot;

    private BitmapText scoreText;

    private final Vector3f screenRightWorld = new Vector3f();
    private final Vector3f screenUpWorld = new Vector3f();

    private final Mode mode;
    private final NetHost netHost;
    private final NetClient netClient;

    /** Consumed (and cleared) on the very next {@link #update}, same buffering as
     *  {@link #pendingPlayerPowerUpSlot} - used only in {@link Mode#JOINER}, where the local
     *  player's power-up activation is sent to the host rather than applied locally. */
    private Integer pendingJoinerPowerUpSlot;

    /** Scores as last reported by the host's snapshot; only used in {@link Mode#JOINER}, since a
     *  joiner has no local {@link MatchSimulation} to read scores from directly. */
    private int joinerDisplayScore;
    private int hostDisplayScore;

    /** Reference-compared against the latest snapshot each frame so joiner-side SFX/HUD/match-over
     *  reactions (driven off a snapshot's flags) fire exactly once per snapshot, not once per
     *  render frame the same snapshot happens to still be the "latest" one. */
    private NetProtocol.SnapshotMessage lastAppliedSnapshot;

    /** The existing, unchanged single-player-vs-AI match. */
    public GameplayAppState() {
        this(Mode.SINGLE_PLAYER, null, null);
    }

    /** A listen-server host match: runs {@link MatchSimulation} locally and broadcasts snapshots
     *  to the joiner connected via {@code netHost}. */
    public GameplayAppState(NetHost netHost) {
        this(Mode.HOST, netHost, null);
    }

    /** A joiner match: sends local input to, and renders snapshots received from, the host
     *  connected via {@code netClient}. Runs no {@link MatchSimulation} of its own. */
    public GameplayAppState(NetClient netClient) {
        this(Mode.JOINER, null, netClient);
    }

    private GameplayAppState(Mode mode, NetHost netHost, NetClient netClient) {
        this.mode = mode;
        this.netHost = netHost;
        this.netClient = netClient;
    }

    @Override
    protected void initialize(Application application) {
        this.app = (PaddleShockApp) application;
        SimpleApplication simpleApp = (SimpleApplication) application;
        level = Catalog.findLevel(app.getProfile().getEquippedId("level")).orElse(Catalog.LEVELS.get(0));
        simpleApp.getViewPort().setBackgroundColor(level.getSkyColor());

        setUpCamera(simpleApp);
        setUpLights();
        setUpScene();
        setUpHud(simpleApp);

        playerInput.register(app.getInputManager());
        registerPauseKey(app.getInputManager());
        registerPowerUpKeys(app.getInputManager());

        simpleApp.getRootNode().attachChild(gameNode);
        simpleApp.getGuiNode().attachChild(hudNode);

        if (matchSimulation != null) {
            matchSimulation.startNewMatch();
        }
        updateScoreText();
    }

    private void setUpCamera(SimpleApplication simpleApp) {
        if (mode == Mode.JOINER) {
            // World coordinates are authored entirely from the host's point of view (see
            // applySnapshotToScene): the joiner's own paddle always renders at
            // PADDLE_OPPONENT_Z, the host's at PADDLE_PLAYER_Z. Using the same fixed camera
            // as the host/single-player would put the joiner behind the HOST's paddle,
            // watching their own paddle from across the table - mirror the camera to the
            // opposite side instead, so every player always sees the match from behind their
            // own paddle. The mouse/gamepad input mapping below is derived from the camera's
            // actual orientation, so it self-corrects for the mirror with no further changes.
            simpleApp.getCamera().setLocation(new Vector3f(0, 7f, 11f));
            simpleApp.getCamera().lookAt(new Vector3f(0, 0, 1f), Vector3f.UNIT_Y);
        } else {
            simpleApp.getCamera().setLocation(new Vector3f(0, 7f, -11f));
            simpleApp.getCamera().lookAt(new Vector3f(0, 0, -1f), Vector3f.UNIT_Y);
        }

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
        sun.setColor(level.getSunTint().mult(1.1f * brightness));
        gameNode.addLight(sun);

        AmbientLight ambient = new AmbientLight();
        ambient.setColor(level.getAmbientTint().mult(0.6f * brightness));
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

        Arena arena = new Arena(getApplication().getAssetManager(), level.getGroundColor(),
                level.getGroundTexture(), level.getBackdropColor(), level.isFloating());
        gameNode.attachChild(arena.getNode());

        table = new Table(getApplication().getAssetManager(), tableDef.getSurfaceColor(),
                tableDef.getTextureSet(), tableDef.getRestitutionMultiplier());
        gameNode.attachChild(table.getNode());

        playerPaddle = new Paddle(getApplication().getAssetManager(), paddleDef.getColor(), paddleDef.getTextureSet(),
                GameConstants.PADDLE_PLAYER_Z, paddleDef.getSpeedMultiplier(), paddleDef.getSizeMultiplier());
        gameNode.attachChild(playerPaddle.getNode());

        opponentPaddle = new Paddle(getApplication().getAssetManager(), new ColorRGBA(1f, 0.35f, 0.3f, 1f),
                TextureSet.PLASTIC, GameConstants.PADDLE_OPPONENT_Z, 1f, 1f);
        gameNode.attachChild(opponentPaddle.getNode());

        // The level's own bounce energy stacks with the table's, so e.g. a bouncy table in the
        // high-energy Neon arena hops noticeably higher than the same table anywhere else.
        float combinedRestitution = tableDef.getRestitutionMultiplier() * level.getBounceMultiplier();
        ball = new Ball(getApplication().getAssetManager(), ballDef.getColor(), ballDef.getTextureSet(),
                ballDef.getBallModel(), ballDef.getSpeedMultiplier(), ballDef.getSizeMultiplier(),
                combinedRestitution, level.getGravityMultiplier(), level.getWindAccelX());
        gameNode.attachChild(ball.getNode());

        // A joiner never runs its own simulation - it only renders whatever the host's
        // MatchSimulation reports via network snapshots.
        if (mode != Mode.JOINER) {
            matchSimulation = new MatchSimulation(ball, playerPaddle, opponentPaddle, table);
        }
        resolveLoadout(profile);

        if ("level_classic".equals(level.getId())) {
            gameNode.attachChild(buildSideDecor());
            gameNode.attachChild(buildTrophyDecor());
        } else {
            gameNode.attachChild(buildThemedDecor());
        }
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

    /** Themed side decor for every non-classic level, replacing the ping-pong table/trophy pair. */
    private Node buildThemedDecor() {
        Node decor = new Node("themedDecor");
        float rightX = GameConstants.TABLE_HALF_WIDTH + 2f;
        float leftX = -GameConstants.TABLE_HALF_WIDTH - 2f;

        switch (level.getId()) {
            case "level_neon" -> {
                decor.attachChild(loadProp("Models/Decor/arcade_machine.glb", 2.0f, rightX, -3f, FastMath.QUARTER_PI * 0.6f));
                decor.attachChild(loadProp("Models/Decor/arcade_machine.glb", 2.0f, leftX, -3f, -FastMath.QUARTER_PI * 0.6f));
            }
            case "level_sunset" -> {
                // Smaller and pushed further out/back than the other props - the raw models read
                // oversized and crowded the frame at the same size/spot the others use.
                decor.attachChild(loadProp("Models/Decor/palm_tree.glb", 2.6f, rightX + 1.5f, 1f, 0f));
                decor.attachChild(loadProp("Models/Decor/beach_umbrella.glb", 1.7f, leftX - 1.5f, 1f, 0f));
            }
            case "level_space" -> {
                decor.attachChild(loadProp("Models/Decor/satellite_dish.glb", 1.8f, rightX, -3f, 0f));
                decor.attachChild(loadProp("Models/Decor/satellite_dish.glb", 1.8f, leftX, -3f, FastMath.PI));
            }
            default -> {
                // No themed decor defined; the level falls back to an empty side (shouldn't happen
                // for any catalog level today).
            }
        }
        return decor;
    }

    /** Loads a decor model, scales it to a target height, and places it beside the table. */
    private Spatial loadProp(String modelPath, float targetHeight, float x, float z, float rotationY) {
        Spatial model = getApplication().getAssetManager().loadModel(modelPath);
        scaleToHeight(model, targetHeight);
        model.rotate(0, rotationY, 0);
        model.setLocalTranslation(x, 0f, z);
        return model;
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

        float boxTopY = simpleApp.getCamera().getHeight() - 64;
        for (int i = 0; i < powerUpLoadout.length; i++) {
            PowerUpDefinition def = powerUpLoadout[i];
            if (def == null) {
                continue;
            }
            float boxX = 20 + i * (POWERUP_BOX_SIZE + POWERUP_BOX_GAP);
            powerUpBoxes[i] = attachPowerUpBox(boxX, boxTopY, def.getType().getColor());

            BitmapText keyText = new BitmapText(font);
            keyText.setSize(13);
            keyText.setColor(Theme.TEXT);
            keyText.setText(Integer.toString(i + 1));
            keyText.setLocalTranslation(boxX + 6, boxTopY - 2, 2);
            hudNode.attachChild(keyText);
            powerUpKeyTexts[i] = keyText;

            BitmapText iconText = new BitmapText(font);
            iconText.setSize(28);
            iconText.setColor(Theme.ON_ACCENT);
            iconText.setText(def.getDisplayName().substring(0, 1).toUpperCase());
            iconText.setLocalTranslation(boxX + POWERUP_BOX_SIZE / 2f - 9, boxTopY - POWERUP_BOX_SIZE / 2f + 15, 2);
            hudNode.attachChild(iconText);
            powerUpIconTexts[i] = iconText;

            BitmapText cooldownText = new BitmapText(font);
            cooldownText.setSize(20);
            cooldownText.setColor(Theme.TEXT);
            cooldownText.setLocalTranslation(boxX + POWERUP_BOX_SIZE / 2f - 8, boxTopY - POWERUP_BOX_SIZE / 2f + 11, 3);
            hudNode.attachChild(cooldownText);
            powerUpCooldownTexts[i] = cooldownText;

            BitmapText nameText = new BitmapText(font);
            nameText.setSize(12);
            nameText.setColor(Theme.TEXT_DIM);
            nameText.setText(def.getDisplayName().toUpperCase());
            nameText.setLocalTranslation(boxX, boxTopY - POWERUP_BOX_SIZE - 6, 0);
            hudNode.attachChild(nameText);
            powerUpNameTexts[i] = nameText;
        }
        updatePowerUpHud();
    }

    private void updateScoreText() {
        switch (mode) {
            case SINGLE_PLAYER -> scoreText.setText("You " + matchSimulation.getPlayerScore() + " : "
                    + matchSimulation.getOpponentScore() + " AI  (Esc: pause)");
            case HOST -> scoreText.setText("You " + matchSimulation.getPlayerScore() + " : "
                    + matchSimulation.getOpponentScore() + " Joiner  (Esc: pause)");
            case JOINER -> scoreText.setText("You " + joinerDisplayScore + " : "
                    + hostDisplayScore + " Host  (Esc: pause)");
        }
    }

    /** A flat square, filled with the power-up's own color, used as its HUD slot icon. */
    private Geometry attachPowerUpBox(float x, float topY, ColorRGBA color) {
        Vector3f[] vertices = {
            new Vector3f(0, 0, 0),
            new Vector3f(POWERUP_BOX_SIZE, 0, 0),
            new Vector3f(POWERUP_BOX_SIZE, -POWERUP_BOX_SIZE, 0),
            new Vector3f(0, -POWERUP_BOX_SIZE, 0),
        };

        Mesh mesh = new Mesh();
        mesh.setBuffer(Type.Position, 3, BufferUtils.createFloatBuffer(vertices));
        mesh.setBuffer(Type.Index, 3, new short[] {0, 1, 2, 0, 2, 3});
        mesh.updateBound();

        Material material = new Material(getApplication().getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
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

    /** Grays a slot's box out and counts its cooldown down once used; back to full color when ready. */
    private void updatePowerUpHud() {
        for (int i = 0; i < powerUpBoxes.length; i++) {
            Geometry box = powerUpBoxes[i];
            PowerUpDefinition def = powerUpLoadout[i];
            if (box == null || def == null) {
                continue;
            }
            float remaining = matchSimulation == null ? 0f
                    : matchSimulation.getPowerUpManager().getPlayerCooldownRemaining(def.getType());
            boolean onCooldown = remaining > 0f;

            box.getMaterial().setColor("Color", onCooldown ? POWERUP_BOX_COOLDOWN_COLOR : def.getType().getColor());
            powerUpIconTexts[i].setCullHint(onCooldown ? Spatial.CullHint.Always : Spatial.CullHint.Never);
            powerUpCooldownTexts[i].setCullHint(onCooldown ? Spatial.CullHint.Never : Spatial.CullHint.Always);
            if (onCooldown) {
                powerUpCooldownTexts[i].setText(Integer.toString((int) Math.ceil(remaining)));
            }
            powerUpNameTexts[i].setColor(onCooldown ? Theme.TEXT_DIM : Theme.TEXT);
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
        if (powerUpLoadout[slot] == null) {
            return;
        }
        // Buffered rather than applied straight to the PowerUpManager here: activation now flows
        // through the same tick-shaped PaddleInput the AI (and a remote opponent, via NetHost/
        // NetClient) uses, so there's exactly one code path that turns "activate slot N" into a
        // simulation effect. In JOINER mode there's no local PowerUpManager to apply it to at
        // all - it's buffered the same way, but consumed into the outgoing network packet instead.
        if (mode == Mode.JOINER) {
            pendingJoinerPowerUpSlot = slot;
        } else {
            pendingPlayerPowerUpSlot = slot;
        }
    }

    @Override
    public void update(float tpf) {
        switch (mode) {
            case SINGLE_PLAYER -> updateSinglePlayer(tpf);
            case HOST -> updateHost(tpf);
            case JOINER -> updateJoiner(tpf);
        }
    }

    private void updateSinglePlayer(float tpf) {
        PaddleInput playerTickInput = computeLocalPaddleInput(tpf);
        PaddleInput opponentTickInput = computeOpponentAiInput(tpf);

        TickResult result = matchSimulation.tick(tpf, playerTickInput, opponentTickInput);

        applyTickResult(result);
        updatePowerUpHud();
    }

    private void updateHost(float tpf) {
        PaddleInput hostTickInput = computeLocalPaddleInput(tpf);
        PaddleInput joinerTickInput = netHost.hasJoiner() ? netHost.pollJoinerPaddleInput() : PaddleInput.none();

        TickResult result = matchSimulation.tick(tpf, hostTickInput, joinerTickInput);

        applyTickResult(result);
        updatePowerUpHud();

        if (netHost.hasJoiner()) {
            netHost.sendSnapshot(buildSnapshot(result));
        }
    }

    private NetProtocol.SnapshotMessage buildSnapshot(TickResult result) {
        Vector3f ballPos = matchSimulation.getBall().getPosition();
        Vector3f ballVel = matchSimulation.getBall().getVelocity();
        Vector3f hostPaddlePos = matchSimulation.getPlayerPaddle().getPosition();
        Vector3f joinerPaddlePos = matchSimulation.getOpponentPaddle().getPosition();

        int flags = 0;
        if (result.isWallBounce()) {
            flags |= NetProtocol.FLAG_WALL_BOUNCE;
        }
        if (result.isPlayerPaddleHit()) {
            flags |= NetProtocol.FLAG_HOST_PADDLE_HIT;
        }
        if (result.isOpponentPaddleHit()) {
            flags |= NetProtocol.FLAG_JOINER_PADDLE_HIT;
        }
        if (result.isAnyPowerUpActivated()) {
            flags |= NetProtocol.FLAG_POWERUP_ACTIVATED;
        }
        if (result.isMatchOver()) {
            flags |= NetProtocol.FLAG_MATCH_OVER;
            if (result.isPlayerWon()) {
                flags |= NetProtocol.FLAG_HOST_WON;
            }
        }

        return new NetProtocol.SnapshotMessage(
                ballPos.x, ballPos.y, ballPos.z,
                ballVel.x, ballVel.z, matchSimulation.getBall().getVerticalVelocity(),
                hostPaddlePos.x, hostPaddlePos.z,
                joinerPaddlePos.x, joinerPaddlePos.z,
                matchSimulation.getPlayerScore(), matchSimulation.getOpponentScore(),
                flags);
    }

    private void updateJoiner(float tpf) {
        PaddleInput localTickInput = computeLocalPaddleInput(tpf);
        PowerUpDefinition activated = consumePendingJoinerPowerUp();
        String powerUpId = activated != null ? activated.getId() : "";
        netClient.sendInput(localTickInput.getDeltaX(), localTickInput.getDeltaZ(), powerUpId);

        NetProtocol.SnapshotMessage snapshot = netClient.getLatestSnapshot();
        if (snapshot != null) {
            applySnapshotToScene(snapshot);
        }
    }

    private void applySnapshotToScene(NetProtocol.SnapshotMessage snapshot) {
        ball.setNetworkState(snapshot.ballX(), snapshot.ballY(), snapshot.ballZ(),
                snapshot.ballVelX(), snapshot.ballVelZ(), snapshot.ballVerticalVel());
        // playerPaddle/opponentPaddle here just mean "the two paddle nodes in this scene": on the
        // joiner, playerPaddle renders the host's paddle and opponentPaddle renders the joiner's
        // own paddle (i.e. the one this client's own mouse/gamepad input drives, authoritatively
        // echoed back by the host) - there is no local moveDelta() call on either in this mode.
        playerPaddle.setNetworkPosition(snapshot.hostPaddleX(), snapshot.hostPaddleZ());
        opponentPaddle.setNetworkPosition(snapshot.joinerPaddleX(), snapshot.joinerPaddleZ());

        hostDisplayScore = snapshot.hostScore();
        joinerDisplayScore = snapshot.joinerScore();

        // Reference-compared: only react to flags on a snapshot we haven't already processed, so
        // a frame that re-reads the same "latest" snapshot (client frame faster than host tick
        // rate) doesn't replay its SFX/score-update/match-over reaction a second time.
        if (snapshot != lastAppliedSnapshot) {
            lastAppliedSnapshot = snapshot;
            if (snapshot.isWallBounce()) {
                app.getAudioManager().playSfx("wall_bounce.ogg");
            }
            if (snapshot.isHostPaddleHit() || snapshot.isJoinerPaddleHit()) {
                app.getAudioManager().playSfx("paddle_hit.ogg");
            }
            if (snapshot.isPowerUpActivated()) {
                app.getAudioManager().playSfx("powerup_activate.ogg");
            }
            updateScoreText();
            if (snapshot.isMatchOver()) {
                // From the joiner's own point of view: "you" are the joiner, so isHostWon()
                // (a host-perspective flag) is negated to get whether the local viewer won.
                app.endRankedJoinerMatch(!snapshot.isHostWon(), joinerDisplayScore, hostDisplayScore);
            }
        }
    }

    /** Local mouse/gamepad input gathered into a tick-shaped {@link PaddleInput}, exactly as
     *  single-player has always gathered its player-side input - shared by all three modes: it's
     *  fed straight into {@link MatchSimulation#tick} in {@link Mode#SINGLE_PLAYER}/{@link Mode#HOST},
     *  or sent over the network in {@link Mode#JOINER}. */
    private PaddleInput computeLocalPaddleInput(float tpf) {
        float[] mouseDelta = playerInput.consumeDelta();
        float[] gamepadStick = playerInput.consumeGamepadInput();
        boolean gamepadActive = gamepadStick[0] != 0f || gamepadStick[1] != 0f;
        float worldDeltaX;
        float worldDeltaZ;
        if (gamepadActive) {
            float gamepadScale = GameConstants.GAMEPAD_MOVE_SPEED * tpf;
            worldDeltaX = (screenRightWorld.x * gamepadStick[0] + screenUpWorld.x * gamepadStick[1]) * gamepadScale;
            worldDeltaZ = (screenRightWorld.z * gamepadStick[0] + screenUpWorld.z * gamepadStick[1]) * gamepadScale;
        } else {
            float scale = GameConstants.MOUSE_SENSITIVITY * app.getGameSettings().getMouseSensitivity();
            worldDeltaX = (screenRightWorld.x * mouseDelta[0] + screenUpWorld.x * mouseDelta[1]) * scale;
            worldDeltaZ = (screenRightWorld.z * mouseDelta[0] + screenUpWorld.z * mouseDelta[1]) * scale;
        }

        PowerUpDefinition activated = mode == Mode.JOINER ? null : consumePendingPlayerPowerUp();
        return new PaddleInput(worldDeltaX, worldDeltaZ, activated);
    }

    private PowerUpDefinition consumePendingJoinerPowerUp() {
        if (pendingJoinerPowerUpSlot == null) {
            return null;
        }
        PowerUpDefinition def = powerUpLoadout[pendingJoinerPowerUpSlot];
        pendingJoinerPowerUpSlot = null;
        return def;
    }

    private PowerUpDefinition consumePendingPlayerPowerUp() {
        if (pendingPlayerPowerUpSlot == null) {
            return null;
        }
        PowerUpDefinition def = powerUpLoadout[pendingPlayerPowerUpSlot];
        pendingPlayerPowerUpSlot = null;
        return def;
    }

    /** Local AI decision-making: there's no remote opponent yet, so this still lives here rather
     *  than in {@code MatchSimulation}, but its output is packaged into the same {@link PaddleInput}
     *  shape a networked opponent will eventually be fed through instead. */
    private PaddleInput computeOpponentAiInput(float tpf) {
        float toBall = matchSimulation.getBall().getPosition().x - matchSimulation.getOpponentPaddle().getPosition().x;
        float maxStep = AI_MAX_SPEED * tpf;
        float step = Math.max(-maxStep, Math.min(maxStep, toBall));

        PowerUpDefinition chosen = pickAiPowerUp(tpf);
        return new PaddleInput(step, 0, chosen);
    }

    /** The AI mirrors the player's own loadout (there's no separate AI/ranked kit yet) and fires
     *  a random ready one every few seconds, so bought power-ups don't just favor the player. */
    private PowerUpDefinition pickAiPowerUp(float tpf) {
        aiPowerUpTimer -= tpf;
        if (aiPowerUpTimer > 0f) {
            return null;
        }
        aiPowerUpTimer = AI_POWERUP_MIN_INTERVAL
                + (float) (Math.random() * (AI_POWERUP_MAX_INTERVAL - AI_POWERUP_MIN_INTERVAL));

        List<PowerUpDefinition> ready = new ArrayList<>();
        for (PowerUpDefinition def : powerUpLoadout) {
            if (def != null && matchSimulation.getPowerUpManager().isAiReady(def.getType())) {
                ready.add(def);
            }
        }
        if (ready.isEmpty()) {
            return null;
        }
        return ready.get((int) (Math.random() * ready.size()));
    }

    /** Translates what the simulation reported happened this tick into SFX/HUD/app side effects. */
    private void applyTickResult(TickResult result) {
        if (result.isWallBounce()) {
            app.getAudioManager().playSfx("wall_bounce.ogg");
        }
        if (result.isAnyPaddleHit()) {
            app.getAudioManager().playSfx("paddle_hit.ogg");
        }
        if (result.isAnyPowerUpActivated()) {
            app.getAudioManager().playSfx("powerup_activate.ogg");
        }
        if (result.getScorer() != TickResult.Scorer.NONE) {
            updateScoreText();
            app.getAudioManager().playSfx("score.ogg");
            if (result.isMatchOver()) {
                if (mode == Mode.HOST) {
                    app.endRankedHostMatch(result.isPlayerWon(), matchSimulation.getPlayerScore(),
                            matchSimulation.getOpponentScore(), netHost.getJoinerPlayerId(), netHost.getLobbyCode());
                } else {
                    app.endMatch(result.isPlayerWon(), matchSimulation.getPlayerScore(), matchSimulation.getOpponentScore());
                }
            }
        }
    }

    public void startNewMatch() {
        if (matchSimulation != null) {
            matchSimulation.startNewMatch();
        }
        updateScoreText();
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
        simpleApp.getViewPort().setBackgroundColor(ColorRGBA.Black);
        // Network resources (the UDP socket + its background receive thread) belong to this
        // match's lifetime, not the app shell's - close them whenever this state goes away,
        // whether via a normal match end or the player quitting to the main menu mid-match.
        if (netHost != null) {
            netHost.close();
        }
        if (netClient != null) {
            netClient.close();
        }
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

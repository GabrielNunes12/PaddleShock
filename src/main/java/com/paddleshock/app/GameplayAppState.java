package com.paddleshock.app;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.input.InputManager;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.input.controls.MouseButtonTrigger;
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
import com.paddleshock.entities.PaddleModel;
import com.paddleshock.entities.ScoreboardDisplay;
import com.paddleshock.entities.Table;
import com.paddleshock.entities.TextureSet;
import com.paddleshock.i18n.I18n;
import com.paddleshock.net.NetClient;
import com.paddleshock.net.NetHost;
import com.paddleshock.net.NetProtocol;
import com.paddleshock.powerups.PowerUpType;
import com.paddleshock.replay.ReplaySample;
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

    /** Which role this instance of the gameplay state is playing. {@link #SPECTATOR} shares the
     *  connection type (and most of the receiving/rendering logic) with {@link #JOINER} - see the
     *  {@link #GameplayAppState(NetClient, boolean)} constructor - but never sends input and is
     *  never treated as "the opponent" by the host. */
    public enum Mode { SINGLE_PLAYER, HOST, JOINER, SPECTATOR }

    private static final String ACTION_PAUSE = "PS_Pause";
    private static final String ACTION_REPLAY_SKIP = "PS_ReplaySkip";
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
    private final PlayerInputGatherer inputGatherer = new PlayerInputGatherer();
    private MatchSimulation matchSimulation;
    private final PowerUpDefinition[] powerUpLoadout = new PowerUpDefinition[3];

    /** The AI opponent's own fixed power-up kit ({@link Mode#SINGLE_PLAYER} only) - deliberately
     *  NOT the player's own {@link #powerUpLoadout}, so a player who buys/equips more power-ups
     *  doesn't hand the AI a stronger kit too. A small, hardcoded pair; timing/frequency of when
     *  the AI fires them is still governed entirely by {@link com.paddleshock.settings.AiDifficulty}
     *  via {@link #aiPowerUpMinInterval}/{@link #aiPowerUpMaxInterval}. */
    private static final String[] AI_POWERUP_IDS = {"powerup_speed_boost", "powerup_slow_opponent"};
    private final PowerUpDefinition[] aiPowerUpLoadout = new PowerUpDefinition[AI_POWERUP_IDS.length];
    private final Geometry[] powerUpBoxes = new Geometry[3];
    private final BitmapText[] powerUpKeyTexts = new BitmapText[3];
    private final BitmapText[] powerUpIconTexts = new BitmapText[3];
    private final BitmapText[] powerUpCooldownTexts = new BitmapText[3];
    private final BitmapText[] powerUpNameTexts = new BitmapText[3];
    /** Only meaningful in {@link Mode#SINGLE_PLAYER} - resolved from the player's chosen
     *  {@link com.paddleshock.settings.AiDifficulty} in {@link #initialize}. */
    private float aiMaxSpeed;
    private float aiPowerUpMinInterval;
    private float aiPowerUpMaxInterval;
    private float aiPowerUpTimer;

    // Colorblind-accessible power-up activation feedback: a transient centered text banner naming
    // both WHO activated it and whether it's a BUFF or a DEBUFF in words, not just the power-up's
    // swatch color - see showPowerUpBanner(). Previously the only feedback for an activation
    // (including an incoming debuff landing on the local player) was a sound effect.
    private static final float POWERUP_BANNER_SECONDS = 2.2f;
    private BitmapText powerUpBannerText;
    private float powerUpBannerTimer;

    private BitmapText scoreText;

    /** Live "P : O" readouts on the Classic Court scoreboard prop(s); empty on every other level
     *  (the prop only exists there). One prop near the player's own end in every mode; a second
     *  near the opponent's end too when there's a real opponent to read it (multiplayer/
     *  spectator) - skipped in {@link Mode#SINGLE_PLAYER} since the AI has no use for one. */
    private final List<ScoreboardDisplay> scoreboardDisplays = new ArrayList<>(2);

    private final Vector3f screenRightWorld = new Vector3f();
    private final Vector3f screenUpWorld = new Vector3f();

    private final Mode mode;
    private final NetHost netHost;
    private final NetClient netClient;

    /** Whether a multiplayer match should route its end through the ranked ladder (LP report/
     *  fetch + rank display) rather than the plain {@code endMatch}. Meaningless in
     *  {@link Mode#SINGLE_PLAYER}, which always uses the plain path. Set from the host's own
     *  UNRANKED choice in {@code MultiplayerState}, communicated to a joiner via the WELCOME
     *  handshake (see {@link NetProtocol}). */
    private final boolean ranked;

    /** Scores as last reported by the host's snapshot; only used in {@link Mode#JOINER}, since a
     *  joiner has no local {@link MatchSimulation} to read scores from directly. */
    private int joinerDisplayScore;
    private int hostDisplayScore;

    /** Reference-compared against the latest snapshot each frame so joiner-side SFX/HUD/match-over
     *  reactions (driven off a snapshot's flags) fire exactly once per snapshot, not once per
     *  render frame the same snapshot happens to still be the "latest" one. */
    private NetProtocol.SnapshotMessage lastAppliedSnapshot;

    /** Guards against re-reporting a disconnect every frame between the check firing and
     *  {@code setEnabled(false)} actually taking effect. Cleared on {@link #startNewMatch()} so a
     *  rematch can detect a fresh disconnect. */
    private boolean disconnectHandled = false;

    /** Joiner-side only: how long {@code netClient.isHostTimedOut()} has been continuously true -
     *  reset the instant a fresh packet arrives from the host (which flips {@code isHostTimedOut()}
     *  back to false on its own, since it's just "time since last packet"). While this is below
     *  {@link #JOINER_RECONNECT_WINDOW_SECONDS}, {@link #updateJoiner} keeps re-sending HELLO (see
     *  {@link #JOINER_RECONNECT_HELLO_INTERVAL_SECONDS}) instead of giving up immediately - a
     *  transient NAT remap (Wi-Fi blip, mobile handoff) is common enough to deserve a real chance
     *  to self-heal rather than an instant dead-end (see {@code NetHost#handleHello}'s matching
     *  reconnect-by-player-id acceptance). Only past this window does {@link #handleHostDisconnected}
     *  actually fire. */
    private float joinerReconnectElapsedSeconds;
    private float joinerReconnectHelloTimer;
    private static final float JOINER_RECONNECT_HELLO_INTERVAL_SECONDS = 1f;
    /** A few seconds beyond {@link NetClient#DISCONNECT_TIMEOUT_MS} (which has already elapsed by
     *  the time this window even starts) - long enough to give a real transient blip a chance to
     *  self-heal, short enough that a genuinely dead host still dead-ends in a reasonable time. */
    private static final float JOINER_RECONNECT_WINDOW_SECONDS = 8f;

    /** Records+plays back recent renderable match state as an instant replay right after the
     *  match ends, before handing off to {@code PaddleShockApp}'s normal match-end flow. Runs for
     *  all three {@link Mode}s; see {@link #recordReplaySample}/{@link #applySnapshotToScene} for
     *  where samples are fed in. Constructed once {@link #ball}/{@link #playerPaddle}/
     *  {@link #opponentPaddle} exist - see {@link #setUpScene}. */
    private ReplayController replayController;

    /** The existing, unchanged single-player-vs-AI match. */
    public GameplayAppState() {
        this(Mode.SINGLE_PLAYER, null, null, false);
    }

    /** A listen-server host match: runs {@link MatchSimulation} locally and broadcasts snapshots
     *  to the joiner connected via {@code netHost}. {@code ranked} is the host's own UNRANKED
     *  choice from {@code MultiplayerState} ({@link NetHost#isRanked()}). */
    public GameplayAppState(NetHost netHost, boolean ranked) {
        this(Mode.HOST, netHost, null, ranked);
    }

    /** A joiner OR spectator match: renders snapshots received from the host connected via
     *  {@code netClient}. Runs no {@link MatchSimulation} of its own. {@code ranked} reflects the
     *  host's choice, learned via the WELCOME handshake ({@link NetClient#isRanked()}). Which of
     *  the two this actually is was already decided at connect time ({@link NetClient#isSpectator()},
     *  set from the role the joining screen chose) - a joiner additionally sends local input every
     *  frame; a spectator never does. */
    public GameplayAppState(NetClient netClient, boolean ranked) {
        this(netClient.isSpectator() ? Mode.SPECTATOR : Mode.JOINER, null, netClient, ranked);
    }

    private GameplayAppState(Mode mode, NetHost netHost, NetClient netClient, boolean ranked) {
        this.mode = mode;
        this.netHost = netHost;
        this.netClient = netClient;
        this.ranked = ranked;
    }

    @Override
    protected void initialize(Application application) {
        this.app = (PaddleShockApp) application;
        SimpleApplication simpleApp = (SimpleApplication) application;
        level = Catalog.findLevel(app.getProfile().getEquippedId("level")).orElse(Catalog.LEVELS.get(0));
        simpleApp.getViewPort().setBackgroundColor(level.getSkyColor());

        com.paddleshock.settings.AiDifficulty aiDifficulty = app.getGameSettings().getAiDifficulty();
        aiMaxSpeed = aiDifficulty.getMaxSpeed();
        aiPowerUpMinInterval = aiDifficulty.getPowerUpMinInterval();
        aiPowerUpMaxInterval = aiDifficulty.getPowerUpMaxInterval();
        aiPowerUpTimer = aiPowerUpMinInterval;

        setUpCamera(simpleApp);
        setUpLights();
        setUpScene();
        setUpHud(simpleApp);

        // A spectator's mouse movement must never affect anything in the scene: skip registering
        // the mouse/gamepad-follows-paddle capture and the power-up hotkeys entirely, rather than
        // relying on the update loop simply never consuming them - see updateSpectator().
        if (mode != Mode.SPECTATOR) {
            inputGatherer.register(app.getInputManager());
            inputGatherer.registerPowerUpKeys(app.getInputManager(), this);
        }
        registerPauseKey(app.getInputManager());
        registerReplaySkipKey(app.getInputManager());

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
        } else if (mode == Mode.SPECTATOR) {
            // Neither "side" of the table is the spectator's own - a raised, centered overhead
            // view of the whole table reads better than mirroring either player's own low,
            // paddle's-eye camera onto a viewer who isn't controlling anything. No side-select UI
            // (out of scope - see the constraints), just this one sensible default framing.
            simpleApp.getCamera().setLocation(new Vector3f(0, 13f, 13f));
            simpleApp.getCamera().lookAt(new Vector3f(0, 0, 0f), Vector3f.UNIT_Y);
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
                paddleDef.getPaddleModel(), GameConstants.PADDLE_PLAYER_Z, paddleDef.getSpeedMultiplier(),
                paddleDef.getSizeMultiplier());
        gameNode.attachChild(playerPaddle.getNode());

        opponentPaddle = new Paddle(getApplication().getAssetManager(), new ColorRGBA(1f, 0.35f, 0.3f, 1f),
                TextureSet.PLASTIC, PaddleModel.CLASSIC, GameConstants.PADDLE_OPPONENT_Z, 1f, 1f);
        gameNode.attachChild(opponentPaddle.getNode());

        // The level's own bounce energy stacks with the table's, so e.g. a bouncy table in the
        // high-energy Neon arena hops noticeably higher than the same table anywhere else.
        float combinedRestitution = tableDef.getRestitutionMultiplier() * level.getBounceMultiplier();
        ball = new Ball(getApplication().getAssetManager(), ballDef.getColor(), ballDef.getTextureSet(),
                ballDef.getBallModel(), ballDef.getSpeedMultiplier(), ballDef.getSizeMultiplier(),
                combinedRestitution, level.getGravityMultiplier(), level.getWindAccelX());
        gameNode.attachChild(ball.getNode());

        // A joiner or spectator never runs its own simulation - both only render whatever the
        // host's MatchSimulation reports via network snapshots.
        if (mode != Mode.JOINER && mode != Mode.SPECTATOR) {
            matchSimulation = new MatchSimulation(ball, playerPaddle, opponentPaddle, table);
        }
        resolveLoadout(profile);

        gameNode.attachChild(buildThemedDecor());

        replayController = new ReplayController(ball, playerPaddle, opponentPaddle, app.getInputManager());
    }

    /** Resolves the player's 3 store-configured power-up slots into their catalog definitions. */
    private void resolveLoadout(PlayerProfile profile) {
        List<String> loadout = profile.getLoadout();
        for (int i = 0; i < powerUpLoadout.length; i++) {
            String id = loadout.get(i);
            powerUpLoadout[i] = id.isEmpty() ? null : Catalog.findPowerUp(id).orElse(null);
        }
        if (mode == Mode.SINGLE_PLAYER) {
            for (int i = 0; i < AI_POWERUP_IDS.length; i++) {
                aiPowerUpLoadout[i] = Catalog.findPowerUp(AI_POWERUP_IDS[i]).orElse(null);
            }
        }
    }

    /** Themed side decor per level. */
    private Node buildThemedDecor() {
        Node decor = new Node("themedDecor");
        float rightX = GameConstants.TABLE_HALF_WIDTH + 2f;
        float leftX = -GameConstants.TABLE_HALF_WIDTH - 2f;

        switch (level.getId()) {
            case "level_classic" -> {
                decor.attachChild(loadProp("Models/Decor/bench.glb", 0.7f, rightX, -2f, -0.35f));

                // Near the player's own end, beside the table (not overlapping its surface). The
                // model's front (the recessed black display panel) faces its own local +Z - see
                // ScoreboardDisplay's measured panel constants - but the single-player/host
                // camera sits at z=-11 (see setUpCamera()), i.e. on the -Z side, so this one
                // needs a 180-degree turn to present that front to it instead of the plain back.
                addScoreboard(decor, leftX, -2f, FastMath.PI, true);

                // A second one near the far end (close to the opponent's own paddle position),
                // for a real opponent to read from their own end - only meaningful in a real
                // match, so skipped in single-player. A joiner's camera is mirrored to the
                // OPPOSITE end (z=+11, see setUpCamera()'s Mode.JOINER branch), which sits on
                // the +Z side of this board - exactly where its front already faces unrotated.
                if (mode != Mode.SINGLE_PLAYER) {
                    addScoreboard(decor, leftX, 2f, 0f, false);
                }
            }
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

    /** Loads a scoreboard prop at the given spot beside the table and attaches its live digit
     *  readout, tracked in {@link #scoreboardDisplays} so {@link #updateScoreText} keeps every
     *  instance in sync. {@code mirrored} must be true for the 180-degree-rotated (far/opponent-
     *  facing) board - see {@link ScoreboardDisplay}'s constructor. */
    private void addScoreboard(Node decor, float x, float z, float rotationY, boolean mirrored) {
        Spatial scoreboard = loadProp("Models/Decor/scoreboard.glb", 2.4f, x, z, rotationY);
        decor.attachChild(scoreboard);
        scoreboardDisplays.add(
                new ScoreboardDisplay(getApplication().getAssetManager(), decor, scoreboard, mirrored));
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

        powerUpBannerText = new BitmapText(font);
        powerUpBannerText.setSize(20);
        powerUpBannerText.setLocalTranslation(0, simpleApp.getCamera().getHeight() - 110, 2);
        powerUpBannerText.setCullHint(Spatial.CullHint.Always);
        hudNode.attachChild(powerUpBannerText);
        powerUpBannerTimer = 0f;

        replayController.buildHud(hudNode, font, simpleApp.getCamera().getWidth(), simpleApp.getCamera().getHeight());

        // A spectator has nothing to activate - its own equipped loadout isn't even relevant to
        // the match it's watching, so skip the power-up slot HUD entirely rather than showing a
        // viewer's own unrelated loadout icons over someone else's match.
        if (mode == Mode.SPECTATOR) {
            return;
        }

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
        int mine;
        int opponent;
        switch (mode) {
            case SINGLE_PLAYER -> {
                mine = matchSimulation.getPlayerScore();
                opponent = matchSimulation.getOpponentScore();
                scoreText.setText(I18n.t("gameplay.score_single_player", mine, opponent));
            }
            case HOST -> {
                mine = matchSimulation.getPlayerScore();
                opponent = matchSimulation.getOpponentScore();
                scoreText.setText(I18n.t("gameplay.score_host", mine, opponent));
            }
            case JOINER -> {
                mine = joinerDisplayScore;
                opponent = hostDisplayScore;
                scoreText.setText(I18n.t("gameplay.score_joiner", mine, opponent));
            }
            // Read-only view: neither side is "you" - name both players plainly instead, and
            // show the scoreboard prop in host-then-joiner order to match.
            case SPECTATOR -> {
                mine = hostDisplayScore;
                opponent = joinerDisplayScore;
                scoreText.setText(I18n.t("gameplay.score_spectator", mine, opponent));
            }
            default -> throw new IllegalStateException("Unhandled mode: " + mode);
        }
        for (ScoreboardDisplay display : scoreboardDisplays) {
            display.update(mine, opponent);
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

    /** Left click, Enter, or Space all jump straight to the normal match-end flow while an
     *  instant replay is playing (see {@link #startReplay}) - not everyone wants to watch it every
     *  time. Registered unconditionally (like the pause/power-up keys) but only acted on while
     *  {@code replayController.isReplaying()} is true - see {@link #onAction}. */
    private void registerReplaySkipKey(InputManager inputManager) {
        inputManager.addMapping(ACTION_REPLAY_SKIP,
                new MouseButtonTrigger(MouseInput.BUTTON_LEFT),
                new KeyTrigger(KeyInput.KEY_RETURN),
                new KeyTrigger(KeyInput.KEY_SPACE));
        inputManager.addListener(this, ACTION_REPLAY_SKIP);
    }

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        if (ACTION_REPLAY_SKIP.equals(name) && isPressed && replayController.isReplaying()) {
            replayController.skip();
            return;
        }
        if (replayController.isReplaying()) {
            // Pause/power-up input is meaningless while the instant replay owns the scene.
            return;
        }
        if (ACTION_PAUSE.equals(name) && isPressed && isEnabled()) {
            app.showPause();
            return;
        }
        if (!isPressed || !isEnabled()) {
            return;
        }
        for (int i = 0; i < PlayerInputGatherer.POWERUP_ACTIONS.length; i++) {
            if (PlayerInputGatherer.POWERUP_ACTIONS[i].equals(name)) {
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
        inputGatherer.activateSlot(slot, mode == Mode.JOINER);
    }

    @Override
    public void update(float tpf) {
        if (replayController.isReplaying()) {
            replayController.update(tpf);
            updatePowerUpBanner(tpf);
            return;
        }
        switch (mode) {
            case SINGLE_PLAYER -> updateSinglePlayer(tpf);
            case HOST -> updateHost(tpf);
            case JOINER -> updateJoiner(tpf);
            case SPECTATOR -> updateSpectator(tpf);
        }
        updatePowerUpBanner(tpf);
    }

    /** Shows a centered, timed HUD banner naming who activated a power-up and whether it's a BUFF
     *  or a DEBUFF in plain text - the only feedback for an activation was previously a sound
     *  effect plus the power-up's own swatch color, which a red-green colorblind player can't
     *  reliably tell apart (Slow Opponent's red/orange vs. Paddle Grow's green, for example).
     *  Overwrites any banner already showing, so the most recent activation always wins. */
    private void showPowerUpBanner(boolean activatedByLocalViewer, PowerUpType type) {
        showPowerUpBanner(activatedByLocalViewer ? I18n.t("gameplay.powerup_you") : I18n.t("gameplay.powerup_opponent"), type);
    }

    /** Same banner, but for a viewer who isn't one of the two players (a spectator) - {@code who}
     *  names the actual side ("HOST"/"JOINER") instead of the YOU/OPPONENT wording above, which
     *  only makes sense from a participant's own point of view. */
    private void showPowerUpBanner(String who, PowerUpType type) {
        if (powerUpBannerText == null || type == null) {
            return;
        }
        String kind = type.isDebuff() ? I18n.t("gameplay.powerup_debuff") : I18n.t("gameplay.powerup_buff");
        String text = I18n.t("gameplay.powerup_banner", kind, who, type.getLabel().toUpperCase());
        powerUpBannerText.setText(text);
        powerUpBannerText.setColor(type.getColor());
        float screenW = ((SimpleApplication) getApplication()).getCamera().getWidth();
        powerUpBannerText.setLocalTranslation(
                (screenW - powerUpBannerText.getLineWidth()) / 2f, powerUpBannerText.getLocalTranslation().y, 2);
        powerUpBannerText.setCullHint(Spatial.CullHint.Never);
        powerUpBannerTimer = POWERUP_BANNER_SECONDS;
    }

    private void updatePowerUpBanner(float tpf) {
        if (powerUpBannerTimer <= 0f || powerUpBannerText == null) {
            return;
        }
        powerUpBannerTimer -= tpf;
        if (powerUpBannerTimer <= 0f) {
            powerUpBannerText.setCullHint(Spatial.CullHint.Always);
        }
    }

    private void updateSinglePlayer(float tpf) {
        PaddleInput playerTickInput = computeLocalPaddleInput(tpf);
        PaddleInput opponentTickInput = computeOpponentAiInput(tpf);

        TickResult result = matchSimulation.tick(tpf, playerTickInput, opponentTickInput);
        recordReplaySample(tpf);

        applyTickResult(result);
        updatePowerUpHud();
    }

    private void updateHost(float tpf) {
        if (netHost.hasJoiner() && netHost.isJoinerTimedOut()) {
            handleJoinerDisconnected();
            return;
        }

        PaddleInput hostTickInput = computeLocalPaddleInput(tpf);
        PaddleInput joinerTickInput = netHost.hasJoiner() ? netHost.pollJoinerPaddleInput() : PaddleInput.none();

        TickResult result = matchSimulation.tick(tpf, hostTickInput, joinerTickInput);
        recordReplaySample(tpf);

        applyTickResult(result);
        updatePowerUpHud();

        // Broadcast whenever there's anyone to broadcast to - a real joiner, or any spectators
        // (spectating and playing are independent; a spectator-only host still runs the match and
        // must still send it snapshots - see NetHost#sendSnapshot).
        if (netHost.hasJoiner() || netHost.getSpectatorCount() > 0) {
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

        // "player"/"opponent" in a HOST-mode TickResult mean host/joiner respectively (see
        // updateHost's tick() argument order) - translate that into the actor-side the joiner's
        // own HUD needs to tell "you activated this" from "the host activated this" (see
        // NetProtocol.SnapshotMessage.getActivatedPowerUpType()).
        int powerUpActorSide = NetProtocol.SnapshotMessage.ACTOR_NONE;
        int powerUpTypeOrdinal = -1;
        if (result.isPlayerPowerUpActivated()) {
            powerUpActorSide = NetProtocol.SnapshotMessage.ACTOR_HOST;
            powerUpTypeOrdinal = result.getPlayerActivatedType().ordinal();
        } else if (result.isOpponentPowerUpActivated()) {
            powerUpActorSide = NetProtocol.SnapshotMessage.ACTOR_JOINER;
            powerUpTypeOrdinal = result.getOpponentActivatedType().ordinal();
        }

        return new NetProtocol.SnapshotMessage(
                ballPos.x, ballPos.y, ballPos.z,
                ballVel.x, ballVel.z, matchSimulation.getBall().getVerticalVelocity(),
                hostPaddlePos.x, hostPaddlePos.z,
                joinerPaddlePos.x, joinerPaddlePos.z,
                matchSimulation.getPlayerScore(), matchSimulation.getOpponentScore(),
                flags, powerUpActorSide, powerUpTypeOrdinal);
    }

    /** The host stops applying stale input and hangs the match forever if a joiner's process dies
     *  or the network drops - report a forfeit win (ranked, if this was a ranked match at all) and
     *  hand off to a real "opponent disconnected" UI state instead. */
    private void handleJoinerDisconnected() {
        if (disconnectHandled) {
            return;
        }
        disconnectHandled = true;
        app.endRankedHostMatchByForfeit(matchSimulation.getPlayerScore(), matchSimulation.getOpponentScore(),
                netHost, ranked);
    }

    /** Symmetric to {@link #handleJoinerDisconnected} for the joiner side: a dead/unreachable host
     *  leaves the joiner staring at a frozen last snapshot forever otherwise. No rank report is
     *  made here - only the host reports match results (see the ranked-ladder trust model), and a
     *  joiner has no way to verify anything the host isn't also seeing. */
    private void handleHostDisconnected() {
        if (disconnectHandled) {
            return;
        }
        disconnectHandled = true;
        if (mode == Mode.SPECTATOR) {
            // A spectator never goes through the "connection lost" match-end UI (that's built for
            // a real participant, with its own rematch negotiation) - just drop it back to the
            // Multiplayer screen, same as a normal spectated match ending.
            app.endSpectatedMatch();
        } else {
            app.handleJoinerConnectionLost(joinerDisplayScore, hostDisplayScore);
        }
    }

    private void updateJoiner(float tpf) {
        if (netClient.isHostTimedOut()) {
            joinerReconnectElapsedSeconds += tpf;
            if (joinerReconnectElapsedSeconds >= JOINER_RECONNECT_WINDOW_SECONDS) {
                // Bounded automatic reconnect attempts didn't get a WELCOME back in time - this is
                // a genuinely dead/unreachable host, not just a transient blip. Fall through to the
                // permanent dead-end exactly as before this feature existed.
                handleHostDisconnected();
                return;
            }
            // Still within the reconnect window: keep re-sending HELLO to the same host
            // address/port on the SAME NetClient (same socket, same player id) - reusing the exact
            // retry pattern MultiplayerState already uses while first connecting. If the host
            // accepts it (see NetHost#handleHello's reconnect-by-player-id path) a fresh WELCOME/
            // snapshot will arrive and isHostTimedOut() flips back to false on its own next frame,
            // resuming the match with no further action needed here.
            joinerReconnectHelloTimer += tpf;
            if (joinerReconnectHelloTimer >= JOINER_RECONNECT_HELLO_INTERVAL_SECONDS) {
                joinerReconnectHelloTimer = 0f;
                netClient.sendHello();
            }
            return;
        }
        joinerReconnectElapsedSeconds = 0f;
        joinerReconnectHelloTimer = 0f;

        PaddleInput localTickInput = computeLocalPaddleInput(tpf);
        PowerUpDefinition activated = inputGatherer.consumeNetworkActivation(powerUpLoadout);
        String powerUpId = activated != null ? activated.getId() : "";
        netClient.sendInput(localTickInput.getDeltaX(), localTickInput.getDeltaZ(), powerUpId);

        NetProtocol.SnapshotMessage snapshot = netClient.getLatestSnapshot();
        if (snapshot != null) {
            applySnapshotToScene(snapshot, tpf);
        }
    }

    /** Same receiving/rendering loop as {@link #updateJoiner}, minus ever sending input - a
     *  spectator never has anything of its own to send (see {@code NetClient#sendInput}'s
     *  spectator no-op, and {@code NetClient.isSpectator()} being what selects this mode in the
     *  first place), so it never calls {@link #computeLocalPaddleInput} at all. */
    private void updateSpectator(float tpf) {
        if (netClient.isHostTimedOut()) {
            joinerReconnectElapsedSeconds += tpf;
            if (joinerReconnectElapsedSeconds >= JOINER_RECONNECT_WINDOW_SECONDS) {
                handleHostDisconnected();
                return;
            }
            joinerReconnectHelloTimer += tpf;
            if (joinerReconnectHelloTimer >= JOINER_RECONNECT_HELLO_INTERVAL_SECONDS) {
                joinerReconnectHelloTimer = 0f;
                netClient.sendHello();
            }
            return;
        }
        joinerReconnectElapsedSeconds = 0f;
        joinerReconnectHelloTimer = 0f;

        NetProtocol.SnapshotMessage snapshot = netClient.getLatestSnapshot();
        if (snapshot != null) {
            applySnapshotToScene(snapshot, tpf);
        }
    }

    private void applySnapshotToScene(NetProtocol.SnapshotMessage snapshot, float tpf) {
        ball.setNetworkState(snapshot.ballX(), snapshot.ballY(), snapshot.ballZ(),
                snapshot.ballVelX(), snapshot.ballVelZ(), snapshot.ballVerticalVel());
        // playerPaddle/opponentPaddle here just mean "the two paddle nodes in this scene": on the
        // joiner, playerPaddle renders the host's paddle and opponentPaddle renders the joiner's
        // own paddle (i.e. the one this client's own mouse/gamepad input drives, authoritatively
        // echoed back by the host) - there is no local moveDelta() call on either in this mode.
        playerPaddle.setNetworkPosition(snapshot.hostPaddleX(), snapshot.hostPaddleZ());
        opponentPaddle.setNetworkPosition(snapshot.joinerPaddleX(), snapshot.joinerPaddleZ());

        // A joiner has no local MatchSimulation tick to hook (see recordReplaySample) - it
        // redraws the scene from whatever's the latest network snapshot each render frame, so
        // that's the equivalent point to sample from here, using the real frame tpf.
        replayController.recordSample(new ReplaySample(tpf,
                snapshot.ballX(), snapshot.ballY(), snapshot.ballZ(),
                snapshot.ballVelX(), snapshot.ballVelZ(), snapshot.ballVerticalVel(),
                snapshot.hostPaddleX(), snapshot.hostPaddleZ(),
                snapshot.joinerPaddleX(), snapshot.joinerPaddleZ(),
                snapshot.hostScore(), snapshot.joinerScore()));

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
                PowerUpType activatedType = snapshot.getActivatedPowerUpType();
                if (activatedType != null) {
                    if (mode == Mode.SPECTATOR) {
                        // Neither side is "you" for a spectator - name the actual side instead of
                        // the joiner-relative YOU/OPPONENT wording below.
                        String who = snapshot.powerUpActorSide() == NetProtocol.SnapshotMessage.ACTOR_HOST
                                ? I18n.t("gameplay.powerup_host") : I18n.t("gameplay.powerup_joiner");
                        showPowerUpBanner(who, activatedType);
                    } else {
                        // From the joiner's own point of view "you" are the joiner (mirroring the
                        // isHostWon() negation just below), so ACTOR_JOINER means the local viewer.
                        boolean byLocalViewer = snapshot.powerUpActorSide() == NetProtocol.SnapshotMessage.ACTOR_JOINER;
                        showPowerUpBanner(byLocalViewer, activatedType);
                    }
                }
            }
            updateScoreText();
            if (snapshot.isMatchOver()) {
                if (mode == Mode.SPECTATOR) {
                    // A spectator never goes through the ranked-report/match-history/rival-tracking
                    // (or instant-replay) flows below - those are for the two real participants
                    // only, and must never touch PlayerProfile on a spectator's behalf - just
                    // return to the Multiplayer screen once the final snapshot shows it's over.
                    app.endSpectatedMatch();
                } else {
                    // From the joiner's own point of view: "you" are the joiner, so isHostWon()
                    // (a host-perspective flag) is negated to get whether the local viewer won.
                    boolean localPlayerWon = !snapshot.isHostWon();
                    int localScore = joinerDisplayScore;
                    int otherScore = hostDisplayScore;
                    Runnable endAction;
                    if (ranked) {
                        endAction = () -> app.endRankedJoinerMatch(localPlayerWon, localScore, otherScore, netClient);
                    } else {
                        // Unranked joiner: the host's playerId (if it sent one - see NetProtocol
                        // TYPE_WELCOME / NetClient#getHostPlayerId) is the opponent for the rival tracker.
                        String hostPlayerId = netClient.getHostPlayerId();
                        endAction = () -> app.endMatch(localPlayerWon, localScore, otherScore, hostPlayerId);
                    }
                    startReplay(endAction);
                }
            }
        }
    }

    /** Local mouse/gamepad input gathered into a tick-shaped {@link PaddleInput}, exactly as
     *  single-player has always gathered its player-side input - shared by all three modes: it's
     *  fed straight into {@link MatchSimulation#tick} in {@link Mode#SINGLE_PLAYER}/{@link Mode#HOST},
     *  or sent over the network in {@link Mode#JOINER}. */
    private PaddleInput computeLocalPaddleInput(float tpf) {
        float[] worldDelta = inputGatherer.consumeWorldDelta(
                tpf, screenRightWorld, screenUpWorld, app.getGameSettings().getMouseSensitivity());

        PowerUpDefinition activated = mode == Mode.JOINER ? null : inputGatherer.consumeLocalActivation(powerUpLoadout);
        return new PaddleInput(worldDelta[0], worldDelta[1], activated);
    }

    /** Local AI decision-making: there's no remote opponent yet, so this still lives here rather
     *  than in {@code MatchSimulation}, but its output is packaged into the same {@link PaddleInput}
     *  shape a networked opponent will eventually be fed through instead. */
    private PaddleInput computeOpponentAiInput(float tpf) {
        float toBall = matchSimulation.getBall().getPosition().x - matchSimulation.getOpponentPaddle().getPosition().x;
        float maxStep = aiMaxSpeed * tpf;
        float step = Math.max(-maxStep, Math.min(maxStep, toBall));

        PowerUpDefinition chosen = pickAiPowerUp(tpf);
        return new PaddleInput(step, 0, chosen);
    }

    /** The AI picks from its own fixed {@link #aiPowerUpLoadout} - independent of whatever the
     *  player has equipped - and fires a random ready one every few seconds. */
    private PowerUpDefinition pickAiPowerUp(float tpf) {
        aiPowerUpTimer -= tpf;
        if (aiPowerUpTimer > 0f) {
            return null;
        }
        aiPowerUpTimer = aiPowerUpMinInterval
                + (float) (Math.random() * (aiPowerUpMaxInterval - aiPowerUpMinInterval));

        List<PowerUpDefinition> ready = new ArrayList<>();
        for (PowerUpDefinition def : aiPowerUpLoadout) {
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
            // In SINGLE_PLAYER and HOST modes "player" always means the local viewer (in HOST mode
            // that's the host themselves - see updateHost's tick() argument order), so these map
            // straight onto showPowerUpBanner's "activated by the local viewer" flag.
            if (result.isPlayerPowerUpActivated()) {
                showPowerUpBanner(true, result.getPlayerActivatedType());
            } else if (result.isOpponentPowerUpActivated()) {
                showPowerUpBanner(false, result.getOpponentActivatedType());
            }
        }
        if (result.getScorer() != TickResult.Scorer.NONE) {
            updateScoreText();
            app.getAudioManager().playSfx("score.ogg");
            if (result.isMatchOver()) {
                boolean playerWon = result.isPlayerWon();
                int finalPlayerScore = matchSimulation.getPlayerScore();
                int finalOpponentScore = matchSimulation.getOpponentScore();
                Runnable endAction;
                if (mode == Mode.HOST && ranked) {
                    endAction = () -> app.endRankedHostMatch(playerWon, finalPlayerScore, finalOpponentScore, netHost);
                } else {
                    // HOST mode here means an unranked LAN/lobby match (ranked HOST already
                    // handled above) - the opponent's playerId is known from HELLO the same way
                    // the ranked path knows it; SINGLE_PLAYER has no real opponent at all.
                    String opponentPlayerId = mode == Mode.HOST ? netHost.getJoinerPlayerId() : null;
                    endAction = () -> app.endMatch(playerWon, finalPlayerScore, finalOpponentScore, opponentPlayerId);
                }
                // Play the instant replay of the winning point over this same scene first - see
                // startReplay() - THEN run the exact endMatch/endRankedHostMatch call that would
                // otherwise have run right here, so PaddleShockApp's match-end flow is unchanged
                // from its own point of view.
                startReplay(endAction);
            }
        }
    }

    /** Samples the current tick's renderable state (ball + both paddles + score) into
     *  {@link #replayController} - called once per simulation tick in {@link Mode#SINGLE_PLAYER}/
     *  {@link Mode#HOST}, right after {@link MatchSimulation#tick} has updated the scene's Ball/
     *  Paddle objects. {@link Mode#JOINER} has no local tick to hook; it samples from
     *  {@link #applySnapshotToScene} instead. */
    private void recordReplaySample(float tpf) {
        Vector3f ballPos = matchSimulation.getBall().getPosition();
        Vector3f ballVel = matchSimulation.getBall().getVelocity();
        Vector3f playerPaddlePos = matchSimulation.getPlayerPaddle().getPosition();
        Vector3f opponentPaddlePos = matchSimulation.getOpponentPaddle().getPosition();
        replayController.recordSample(new ReplaySample(tpf,
                ballPos.x, ballPos.y, ballPos.z,
                ballVel.x, ballVel.z, matchSimulation.getBall().getVerticalVelocity(),
                playerPaddlePos.x, playerPaddlePos.z,
                opponentPaddlePos.x, opponentPaddlePos.z,
                matchSimulation.getPlayerScore(), matchSimulation.getOpponentScore()));
    }

    /** Starts playing the just-recorded buffer back over this scene's real ball/paddle objects,
     *  deferring {@code postMatchEndAction} (the exact {@code PaddleShockApp} match-end call that
     *  would otherwise run immediately) until playback finishes naturally or is skipped (see
     *  {@link #onAction}) - see {@link ReplayController#start}. */
    private void startReplay(Runnable postMatchEndAction) {
        replayController.start(postMatchEndAction);
    }

    public void startNewMatch() {
        if (matchSimulation != null) {
            matchSimulation.startNewMatch();
        }
        // Reset the replay buffer/state too, so a rematch never opens with a stale replay from
        // the previous match still queued up (see resumeMultiplayerRematch).
        replayController.reset();
        // Also reset the joiner-only display state and the disconnect guard: needed for a
        // multiplayer rematch reusing this same GameplayAppState/connection rather than a fresh
        // single-player match, where these are already at their defaults and this is a no-op.
        joinerDisplayScore = 0;
        hostDisplayScore = 0;
        lastAppliedSnapshot = null;
        disconnectHandled = false;
        joinerReconnectElapsedSeconds = 0f;
        joinerReconnectHelloTimer = 0f;
        updateScoreText();
    }

    public Mode getMode() {
        return mode;
    }

    public NetHost getNetHost() {
        return netHost;
    }

    public NetClient getNetClient() {
        return netClient;
    }

    @Override
    protected void cleanup(Application application) {
        SimpleApplication simpleApp = (SimpleApplication) application;
        simpleApp.getRootNode().detachChild(gameNode);
        simpleApp.getGuiNode().detachChild(hudNode);
        simpleApp.getInputManager().deleteMapping(ACTION_PAUSE);
        simpleApp.getInputManager().deleteMapping(ACTION_REPLAY_SKIP);
        // Only ever deleted if they were actually registered in initialize() - see the mode check
        // there (a spectator never registers the power-up hotkeys at all).
        if (mode != Mode.SPECTATOR) {
            for (String action : PlayerInputGatherer.POWERUP_ACTIONS) {
                simpleApp.getInputManager().deleteMapping(action);
            }
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

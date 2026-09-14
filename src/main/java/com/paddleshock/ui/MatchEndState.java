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
import com.simsilica.lemur.Label;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.component.SpringGridLayout;

import java.util.concurrent.atomic.AtomicReference;

import com.paddleshock.GameConstants;
import com.paddleshock.app.GameplayAppState;
import com.paddleshock.app.PaddleShockApp;
import com.paddleshock.net.NetClient;
import com.paddleshock.net.NetHost;
import com.paddleshock.net.RankState;

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

    // Ranked-match rank display: the actual RankClient call happens on a background thread owned
    // by PaddleShockApp (see endRankedHostMatch/endRankedJoinerMatch) - this just polls for its
    // result and rebuilds once, the same async-result pattern MultiplayerState uses.
    private boolean ranked = false;
    private volatile boolean rankLookupDone = false;
    private final AtomicReference<RankState> rankResult = new AtomicReference<>();
    private boolean rankShown = false;

    /** Extra annotation line shown under the normal win/defeat content, e.g. a forfeit-win notice.
     *  Cleared whenever a fresh result is set. */
    private String extraNotice;

    /** True for a joiner whose host vanished mid-match (see {@code PaddleShockApp#handleJoinerConnectionLost}) -
     *  shows a dedicated dead-end screen instead of the normal win/defeat layout, since there's no
     *  legitimate winner to declare from the joiner's point of view and no rematch is possible
     *  (the connection is gone). */
    private boolean connectionLost = false;

    /** Multiplayer rematch negotiation - see {@link #onRematchClicked()}/{@link #pollRematch}. */
    private enum RematchPhase { NONE, REQUESTED_LOCAL, REQUESTED_REMOTE }
    private RematchPhase rematchPhase = RematchPhase.NONE;
    private float rematchTimer;
    private static final float REMATCH_TIMEOUT_SECONDS = 20f;

    /** How often to ping the peer while this screen is up, well under {@code DISCONNECT_TIMEOUT_MS}
     *  (5s) - see {@link NetProtocol#TYPE_KEEPALIVE}. Nothing else is sent once a match ends, so
     *  without this a player who just reads the result for a few seconds before clicking REMATCH
     *  would have the peer look falsely disconnected. */
    private static final float KEEPALIVE_INTERVAL_SECONDS = 1.5f;
    private float keepAliveTimer;

    /** Sets the outcome to display next time this state is enabled (a non-ranked match - single
     *  player vs AI, or a non-ranked forfeit win). */
    public void setResult(boolean playerWon, int rewardEarned, int playerScore, int opponentScore) {
        this.playerWon = playerWon;
        this.rewardEarned = rewardEarned;
        this.playerScore = playerScore;
        this.opponentScore = opponentScore;
        this.ranked = false;
        this.connectionLost = false;
        this.extraNotice = null;
        this.rematchPhase = RematchPhase.NONE;
        this.keepAliveTimer = 0f;
    }

    /** Same as {@link #setResult}, but for a ranked multiplayer match - shows a "looking up
     *  rank..." placeholder until {@link #reportRankResult} delivers the actual outcome. */
    public void setRankedResult(boolean playerWon, int rewardEarned, int playerScore, int opponentScore) {
        setResult(playerWon, rewardEarned, playerScore, opponentScore);
        this.ranked = true;
        this.rankLookupDone = false;
        this.rankResult.set(null);
        this.rankShown = false;
    }

    /** A joiner whose host disconnected mid-match: no winner to declare, no rank report, no
     *  rematch possible - just a clear dead end back to the multiplayer/main menus. */
    public void setConnectionLost(int playerScore, int opponentScore) {
        this.playerScore = playerScore;
        this.opponentScore = opponentScore;
        this.connectionLost = true;
        this.ranked = false;
        this.extraNotice = null;
        this.rematchPhase = RematchPhase.NONE;
    }

    /** An extra annotation line shown under the normal win/defeat content - e.g. a forfeit-win
     *  disclaimer. {@code null} to show nothing extra. */
    public void setExtraNotice(String text) {
        this.extraNotice = text;
    }

    /** Called from a background thread once the rank report/fetch call completes; {@code null}
     *  means it failed (offline, service unreachable) - the match itself already completed fine
     *  either way, so this only affects what's shown, never blocks anything. */
    public void reportRankResult(RankState state) {
        rankResult.set(state);
        rankLookupDone = true;
    }

    @Override
    public void update(float tpf) {
        if (ranked && !rankShown && rankLookupDone) {
            rankShown = true;
            rebuild((PaddleShockApp) getApplication());
        }
        pollRematch(tpf);
    }

    /** Polls the still-open multiplayer connection for rematch negotiation traffic from the peer;
     *  see {@link #onRematchClicked()} for how a proposal starts. No-op for single-player or a
     *  connection-lost screen (no peer left to negotiate with). */
    private void pollRematch(float tpf) {
        if (connectionLost) {
            return;
        }
        PaddleShockApp app = (PaddleShockApp) getApplication();
        GameplayAppState gp = app.getGameplayState();
        if (gp == null || gp.getMode() == GameplayAppState.Mode.SINGLE_PLAYER) {
            return;
        }
        NetHost host = gp.getNetHost();
        NetClient client = gp.getNetClient();

        keepAliveTimer += tpf;
        if (keepAliveTimer >= KEEPALIVE_INTERVAL_SECONDS) {
            keepAliveTimer = 0f;
            if (host != null) {
                host.sendKeepAlive();
            } else {
                client.sendKeepAlive();
            }
        }

        switch (rematchPhase) {
            case NONE -> {
                boolean requestedByPeer = host != null ? host.isRematchRequestedByPeer() : client.isRematchRequestedByPeer();
                if (requestedByPeer) {
                    rematchPhase = RematchPhase.REQUESTED_REMOTE;
                    rebuild(app);
                }
            }
            case REQUESTED_LOCAL -> {
                boolean accepted = host != null ? host.isRematchAccepted() : client.isRematchAccepted();
                boolean declined = host != null ? host.isRematchDeclined() : client.isRematchDeclined();
                // Both sides proposing at once (each clicked REMATCH before seeing the other's
                // request) counts as mutual agreement - no need to make either wait on an explicit
                // accept of a request that already matches what they themselves asked for.
                boolean mutualRequest = host != null ? host.isRematchRequestedByPeer() : client.isRematchRequestedByPeer();
                rematchTimer += tpf;
                if (accepted || mutualRequest) {
                    startRematch(app, host, client);
                } else if (declined || rematchTimer > REMATCH_TIMEOUT_SECONDS) {
                    // On a real decline the peer already knows; on our own timeout it doesn't -
                    // tell it explicitly so a peer that accepts a moment later gets a clean
                    // "declined" on their own screen instead of silently sending into a connection
                    // that already moved on (which used to surface as a confusing false
                    // "opponent disconnected").
                    if (!declined) {
                        if (host != null) {
                            host.sendRematchDecline();
                        } else {
                            client.sendRematchDecline();
                        }
                    }
                    resetRematchFlags(host, client);
                    rematchPhase = RematchPhase.NONE;
                    app.showMultiplayer();
                }
            }
            case REQUESTED_REMOTE -> {
                // Nothing to poll - waiting on this player to click ACCEPT/DECLINE.
            }
        }
    }

    private void resetRematchFlags(NetHost host, NetClient client) {
        if (host != null) {
            host.resetRematchState();
        }
        if (client != null) {
            client.resetRematchState();
        }
    }

    private void startRematch(PaddleShockApp app, NetHost host, NetClient client) {
        resetRematchFlags(host, client);
        rematchPhase = RematchPhase.NONE;
        rematchTimer = 0f;
        app.resumeMultiplayerRematch();
    }

    /** REMATCH button handler: for single-player, unchanged (back to loadout). For a still-live
     *  multiplayer connection, proposes replaying the SAME two players over it instead of silently
     *  falling into single-player-vs-AI - the actual bug this fixes. If the connection is already
     *  dead (opponent's socket timed out), falls back to the multiplayer menu instead. */
    private void onRematchClicked() {
        PaddleShockApp app = (PaddleShockApp) getApplication();
        GameplayAppState gp = app.getGameplayState();
        if (gp == null || gp.getMode() == GameplayAppState.Mode.SINGLE_PLAYER) {
            app.showLoadout();
            return;
        }

        boolean sent;
        if (gp.getMode() == GameplayAppState.Mode.HOST) {
            NetHost host = gp.getNetHost();
            sent = host != null && host.hasJoiner() && !host.isJoinerTimedOut();
            if (sent) {
                host.sendRematchRequest();
            }
        } else {
            NetClient client = gp.getNetClient();
            sent = client != null && !client.isHostTimedOut();
            if (sent) {
                client.sendRematchRequest();
            }
        }

        if (!sent) {
            app.showMultiplayer();
            return;
        }
        rematchPhase = RematchPhase.REQUESTED_LOCAL;
        rematchTimer = 0f;
        rebuild(app);
    }

    private void onAcceptRematchClicked() {
        PaddleShockApp app = (PaddleShockApp) getApplication();
        GameplayAppState gp = app.getGameplayState();
        if (gp == null) {
            return;
        }
        NetHost host = gp.getNetHost();
        NetClient client = gp.getNetClient();
        if (host != null) {
            host.sendRematchAccept();
        } else if (client != null) {
            client.sendRematchAccept();
        }
        startRematch(app, host, client);
    }

    private void onDeclineRematchClicked() {
        PaddleShockApp app = (PaddleShockApp) getApplication();
        GameplayAppState gp = app.getGameplayState();
        if (gp == null) {
            return;
        }
        NetHost host = gp.getNetHost();
        NetClient client = gp.getNetClient();
        if (host != null) {
            host.sendRematchDecline();
        } else if (client != null) {
            client.sendRematchDecline();
        }
        resetRematchFlags(host, client);
        rematchPhase = RematchPhase.NONE;
        rebuild(app);
    }

    private void onCancelRematchClicked() {
        PaddleShockApp app = (PaddleShockApp) getApplication();
        GameplayAppState gp = app.getGameplayState();
        NetHost host = gp == null ? null : gp.getNetHost();
        NetClient client = gp == null ? null : gp.getNetClient();
        if (host != null) {
            host.sendRematchDecline();
        } else if (client != null) {
            client.sendRematchDecline();
        }
        resetRematchFlags(host, client);
        rematchPhase = RematchPhase.NONE;
        rebuild(app);
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

        if (connectionLost) {
            buildConnectionLostLayout(app, screenW, screenH);
        } else if (playerWon) {
            buildWinLayout(app, screenW, screenH);
        } else {
            buildDefeatLayout(app, screenW, screenH);
        }
    }

    /** Dedicated dead-end screen for a joiner whose host disconnected mid-match - see
     *  {@link #setConnectionLost}. No REMATCH (the connection is gone); just clear ways back in. */
    private void buildConnectionLostLayout(PaddleShockApp app, float screenW, float screenH) {
        float blockHeight = 84 + 24 + 108 + 20 + 24 + 96;
        float topY = (screenH + blockHeight) / 2f;
        float centerX = screenW / 2f;

        float bannerWidth = 380;
        attachAngledQuad(centerX - bannerWidth / 2f, topY, 84, 12, bannerWidth, bannerWidth, 0, Theme.PANEL_HOVER, 1);
        attachText(centerX - bannerWidth / 2f + 30, topY - 30, "CONNECTION LOST", 30, Theme.TEXT);

        float scoreY = topY - 84 - 24;
        float scoreWidth = 320;
        attachAngledQuad(centerX - scoreWidth / 2f, scoreY, 108, 0, scoreWidth, scoreWidth, 18, Theme.PANEL, 1);
        attachScoreboard(centerX - scoreWidth / 2f, scoreWidth, scoreY, 108, playerScore, opponentScore, Theme.TEXT_DIM, Theme.TEXT_DIM);

        float consolationY = scoreY - 108 - 20;
        attachCenteredText(centerX, consolationY, "Your opponent disconnected. No result was recorded.", 15, Theme.TEXT_DIM);

        Container actions = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        addMenuButton(actions, "MULTIPLAYER", Theme.ORANGE, Theme.ON_ACCENT, app::showMultiplayer);
        addMenuButton(actions, "MAIN MENU", Theme.PANEL_HOVER, Theme.TEXT, app::showMainMenu);
        Vector3f actionsSize = actions.getPreferredSize();
        actions.setLocalTranslation(centerX - actionsSize.x / 2f, consolationY - 24, 2);
        uiRoot.attachChild(actions);
    }

    /** Builds the REMATCH-area button(s) for the win/defeat layouts, replacing a plain REMATCH
     *  button with the right controls for whatever the rematch negotiation state actually is (see
     *  {@link #pollRematch}/{@link #onRematchClicked}). */
    private void attachRematchControls(PaddleShockApp app, Container actions) {
        switch (rematchPhase) {
            case REQUESTED_LOCAL -> {
                Label waiting = actions.addChild(new Label("Waiting for opponent to accept rematch..."));
                waiting.setFontSize(13);
                waiting.setColor(Theme.TEXT_DIM);
                waiting.setInsets(new Insets3f(6, 0, 6, 0));
                addMenuButton(actions, "CANCEL", Theme.PANEL_HOVER, Theme.TEXT, this::onCancelRematchClicked);
            }
            case REQUESTED_REMOTE -> {
                Label prompt = actions.addChild(new Label("Opponent wants a rematch!"));
                prompt.setFontSize(13);
                prompt.setColor(Theme.TEXT);
                prompt.setInsets(new Insets3f(6, 0, 6, 0));
                addMenuButton(actions, "ACCEPT REMATCH", Theme.ORANGE, Theme.ON_ACCENT, this::onAcceptRematchClicked);
                addMenuButton(actions, "DECLINE", Theme.PANEL_HOVER, Theme.TEXT, this::onDeclineRematchClicked);
            }
            case NONE -> addMenuButton(actions, "REMATCH", Theme.ORANGE, Theme.ON_ACCENT, this::onRematchClicked);
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

        if (ranked) {
            attachText(40, screenH - 358, rankLineText(), 15, rankLineColor());
        }
        if (extraNotice != null) {
            attachText(40, screenH - (ranked ? 380 : 358), extraNotice, 13, Theme.TEXT_DIM);
        }

        // Actions: left-anchored, matching the banner's asymmetric composition.
        Container actions = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        attachRematchControls(app, actions);
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

        float actionsY = consolationY - 24;
        if (ranked) {
            attachCenteredText(centerX, consolationY - 26, rankLineText(), 14, rankLineColor());
            actionsY -= 30;
        }
        if (extraNotice != null) {
            attachCenteredText(centerX, actionsY - 4, extraNotice, 12, Theme.TEXT_DIM);
            actionsY -= 26;
        }

        Container actions = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        attachRematchControls(app, actions);
        addMenuButton(actions, "MAIN MENU", Theme.PANEL_HOVER, Theme.TEXT, app::showMainMenu);
        Vector3f actionsSize = actions.getPreferredSize();
        actions.setLocalTranslation(centerX - actionsSize.x / 2f, actionsY, 2);
        uiRoot.attachChild(actions);
    }

    /** "Silver II (65 LP, +18) - PROMOTED!" style summary of the rank change from this match, or
     *  a placeholder while the background lookup/report call is still in flight or failed. */
    private String rankLineText() {
        if (!rankLookupDone) {
            return "Updating rank...";
        }
        RankState rank = rankResult.get();
        if (rank == null) {
            return "(rank unavailable - offline?)";
        }
        // Spelled out rather than a "+"/"-" sign: at this HUD font's small size a "+" glyph is
        // easy to misread as a dash, which would silently flip the apparent meaning.
        int lpChange = rank.getLpChange();
        String delta = lpChange == 0 ? "no change"
                : lpChange > 0 ? lpChange + " gained" : (-lpChange) + " lost";
        String suffix = switch (rank.getPromoSeriesResult() == null ? "" : rank.getPromoSeriesResult()) {
            case "started" -> " - PROMO SERIES!";
            case "ongoing" -> " - promo series continues";
            case "won" -> "";
            case "lost" -> " - promos failed, keep grinding";
            default -> "";
        };
        if (rank.wasPromoted()) {
            suffix = " - PROMOTED!";
        } else if (rank.wasDemoted()) {
            suffix = " - demoted";
        }
        return rank.formatLabel() + " - " + rank.getLp() + " LP (" + delta + ")" + suffix;
    }

    private ColorRGBA rankLineColor() {
        if (!rankLookupDone) {
            return Theme.TEXT_DIM;
        }
        RankState rank = rankResult.get();
        return rank == null ? Theme.TEXT_DIM : rank.getTier().getColor();
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

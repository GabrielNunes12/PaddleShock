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
import com.paddleshock.i18n.I18n;
import com.paddleshock.tour.TourOpponent;
import com.paddleshock.tour.WorldTour;
import com.paddleshock.app.GameplayAppState;
import com.paddleshock.app.Navigator;
import com.paddleshock.app.PlayerContext;
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

    /** Set (via {@link #setTourResult}) when the match was a World Tour match - swaps the REMATCH
     *  button for NEXT/RETRY + WORLD TOUR. Cleared by every {@link #setResult}. */
    private TourOpponent tourOpponent;

    /** 1 or 2 when the match was local versus (the winning player), else 0 - see {@link #setLocalVersusResult}. */
    private int localVersusWinner;

    /** The match's target score, for the defeat screen's "first to N" line. */
    private int winScore = GameConstants.WIN_SCORE;

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
        this.tourOpponent = null;
        this.localVersusWinner = 0;
        this.winScore = GameConstants.WIN_SCORE;
        this.rematchPhase = RematchPhase.NONE;
        this.keepAliveTimer = 0f;
    }

    /** A local versus result: always the celebratory layout, titled with the winning player, no
     *  credits; scores are Player 1 : Player 2. REMATCH replays locally. */
    public void setLocalVersusResult(boolean player1Won, int player1Score, int player2Score) {
        setResult(true, 0, player1Score, player2Score);
        this.localVersusWinner = player1Won ? 1 : 2;
    }

    /** Marks the result just set via {@link #setResult} as a World Tour match against
     *  {@code opponent}; {@code firstWin} adds the "beaten / next unlocked" notice. */
    public void setTourResult(TourOpponent opponent, boolean firstWin) {
        this.tourOpponent = opponent;
        this.winScore = opponent.winScore();
        if (!firstWin) {
            return;
        }
        TourOpponent next = nextInLadder(opponent);
        this.extraNotice = next != null
                ? I18n.t("matchend.tour_beaten_next", opponent.name().toUpperCase(), next.name().toUpperCase())
                : I18n.t("matchend.tour_complete");
    }

    private static TourOpponent nextInLadder(TourOpponent opponent) {
        int index = WorldTour.OPPONENTS.indexOf(opponent);
        return index >= 0 && index + 1 < WorldTour.OPPONENTS.size() ? WorldTour.OPPONENTS.get(index + 1) : null;
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
            rebuild((Navigator) getApplication());
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
        PlayerContext ctx = (PlayerContext) getApplication();
        Navigator nav = (Navigator) getApplication();
        GameplayAppState gp = ctx.getGameplayState();
        if (gp == null || gp.getMode() == GameplayAppState.Mode.SINGLE_PLAYER
                || gp.getMode() == GameplayAppState.Mode.LOCAL_VERSUS) {
            return; // no network peer to keep alive or negotiate with
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
                    rebuild(nav);
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
                    startRematch(ctx, host, client);
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
                    nav.showMultiplayer();
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

    private void startRematch(PlayerContext ctx, NetHost host, NetClient client) {
        resetRematchFlags(host, client);
        rematchPhase = RematchPhase.NONE;
        rematchTimer = 0f;
        ctx.resumeMultiplayerRematch();
    }

    /** REMATCH button handler: for single-player, unchanged (back to loadout). For a still-live
     *  multiplayer connection, proposes replaying the SAME two players over it instead of silently
     *  falling into single-player-vs-AI - the actual bug this fixes. If the connection is already
     *  dead (opponent's socket timed out), falls back to the multiplayer menu instead. */
    private void onRematchClicked() {
        PlayerContext ctx = (PlayerContext) getApplication();
        Navigator nav = (Navigator) getApplication();
        GameplayAppState gp = ctx.getGameplayState();
        if (gp == null || gp.getMode() == GameplayAppState.Mode.SINGLE_PLAYER) {
            nav.showLoadout();
            return;
        }
        if (gp.getMode() == GameplayAppState.Mode.LOCAL_VERSUS) {
            nav.startLocalVersus();
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
            nav.showMultiplayer();
            return;
        }
        rematchPhase = RematchPhase.REQUESTED_LOCAL;
        rematchTimer = 0f;
        rebuild(nav);
    }

    private void onAcceptRematchClicked() {
        PlayerContext ctx = (PlayerContext) getApplication();
        GameplayAppState gp = ctx.getGameplayState();
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
        startRematch(ctx, host, client);
    }

    private void onDeclineRematchClicked() {
        PlayerContext ctx = (PlayerContext) getApplication();
        Navigator nav = (Navigator) getApplication();
        GameplayAppState gp = ctx.getGameplayState();
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
        rebuild(nav);
    }

    private void onCancelRematchClicked() {
        PlayerContext ctx = (PlayerContext) getApplication();
        Navigator nav = (Navigator) getApplication();
        GameplayAppState gp = ctx.getGameplayState();
        NetHost host = gp == null ? null : gp.getNetHost();
        NetClient client = gp == null ? null : gp.getNetClient();
        if (host != null) {
            host.sendRematchDecline();
        } else if (client != null) {
            client.sendRematchDecline();
        }
        resetRematchFlags(host, client);
        rematchPhase = RematchPhase.NONE;
        rebuild(nav);
    }

    @Override
    protected void initialize(Application application) {
        // Built fresh in rebuild() every time the screen is shown.
    }

    private void rebuild(Navigator nav) {
        uiRoot.detachAllChildren();

        SimpleApplication simpleApp = (SimpleApplication) getApplication();
        float screenW = simpleApp.getCamera().getWidth();
        float screenH = simpleApp.getCamera().getHeight();

        buildDimOverlay(screenW, screenH);

        if (connectionLost) {
            buildConnectionLostLayout(nav, screenW, screenH);
        } else if (playerWon) {
            buildWinLayout(nav, screenW, screenH);
        } else {
            buildDefeatLayout(nav, screenW, screenH);
        }
    }

    /** Dedicated dead-end screen for a joiner whose host disconnected mid-match - see
     *  {@link #setConnectionLost}. No REMATCH (the connection is gone); just clear ways back in. */
    private void buildConnectionLostLayout(Navigator nav, float screenW, float screenH) {
        float blockHeight = 84 + 24 + 108 + 20 + 24 + 96;
        float topY = (screenH + blockHeight) / 2f;
        float centerX = screenW / 2f;

        float bannerWidth = 380;
        attachAngledQuad(centerX - bannerWidth / 2f, topY, 84, 12, bannerWidth, bannerWidth, 0, Theme.PANEL_HOVER, 1);
        attachText(centerX - bannerWidth / 2f + 30, topY - 30, I18n.t("matchend.connection_lost"), 30, Theme.TEXT);

        float scoreY = topY - 84 - 24;
        float scoreWidth = 320;
        attachAngledQuad(centerX - scoreWidth / 2f, scoreY, 108, 0, scoreWidth, scoreWidth, 18, Theme.PANEL, 1);
        attachScoreboard(centerX - scoreWidth / 2f, scoreWidth, scoreY, 108, playerScore, opponentScore, Theme.TEXT_DIM, Theme.TEXT_DIM);

        float consolationY = scoreY - 108 - 20;
        attachCenteredText(centerX, consolationY, I18n.t("matchend.connection_lost_desc"), 15, Theme.TEXT_DIM);

        Container actions = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        addMenuButton(actions, I18n.t("matchend.multiplayer"), Theme.ORANGE, Theme.ON_ACCENT, nav::showMultiplayer);
        addMenuButton(actions, I18n.t("matchend.main_menu"), Theme.PANEL_HOVER, Theme.TEXT, nav::showMainMenu);
        Vector3f actionsSize = actions.getPreferredSize();
        actions.setLocalTranslation(centerX - actionsSize.x / 2f, consolationY - 24, 2);
        uiRoot.attachChild(actions);
    }

    /** Builds the REMATCH-area button(s) for the win/defeat layouts, replacing a plain REMATCH
     *  button with the right controls for whatever the rematch negotiation state actually is (see
     *  {@link #pollRematch}/{@link #onRematchClicked}). */
    private void attachRematchControls(Container actions) {
        switch (rematchPhase) {
            case REQUESTED_LOCAL -> {
                Label waiting = actions.addChild(new Label(I18n.t("matchend.waiting_rematch")));
                waiting.setFontSize(13);
                waiting.setColor(Theme.TEXT_DIM);
                waiting.setInsets(new Insets3f(6, 0, 6, 0));
                addMenuButton(actions, I18n.t("matchend.cancel"), Theme.PANEL_HOVER, Theme.TEXT, this::onCancelRematchClicked);
            }
            case REQUESTED_REMOTE -> {
                Label prompt = actions.addChild(new Label(I18n.t("matchend.opponent_wants_rematch")));
                prompt.setFontSize(13);
                prompt.setColor(Theme.TEXT);
                prompt.setInsets(new Insets3f(6, 0, 6, 0));
                addMenuButton(actions, I18n.t("matchend.accept_rematch"), Theme.ORANGE, Theme.ON_ACCENT, this::onAcceptRematchClicked);
                addMenuButton(actions, I18n.t("matchend.decline"), Theme.PANEL_HOVER, Theme.TEXT, this::onDeclineRematchClicked);
            }
            case NONE -> {
                if (tourOpponent != null) {
                    attachTourControls(actions);
                } else {
                    addMenuButton(actions, I18n.t("matchend.rematch"), Theme.ORANGE, Theme.ON_ACCENT, this::onRematchClicked);
                }
            }
        }
    }

    /** World Tour result actions: NEXT (after a win, if there is a next opponent) or RETRY, then
     *  back to the ladder. */
    private void attachTourControls(Container actions) {
        Navigator nav = (Navigator) getApplication();
        TourOpponent next = playerWon ? nextInLadder(tourOpponent) : null;
        if (next != null) {
            addMenuButton(actions, I18n.t("matchend.tour_next", next.name().toUpperCase()), Theme.ORANGE, Theme.ON_ACCENT,
                    () -> nav.startTourMatch(next));
        } else {
            TourOpponent same = tourOpponent;
            addMenuButton(actions, I18n.t(playerWon ? "matchend.tour_play_again" : "matchend.tour_retry"), Theme.ORANGE, Theme.ON_ACCENT,
                    () -> nav.startTourMatch(same));
        }
        addMenuButton(actions, I18n.t("matchend.world_tour"), Theme.PANEL_HOVER, Theme.TEXT, nav::showWorldTour);
    }

    private void buildDimOverlay(float screenW, float screenH) {
        Container overlay = new Container();
        overlay.setBackground(new QuadBackgroundComponent(Theme.OVERLAY));
        overlay.setPreferredSize(new Vector3f(screenW, screenH, 0));
        overlay.setLocalTranslation(0, screenH, 0);
        uiRoot.attachChild(overlay);
    }

    private void buildWinLayout(Navigator nav, float screenW, float screenH) {
        // Headline banner: bleeds off the left edge, orange fill, dark (ON_ACCENT) text.
        attachAngledQuad(-60, screenH - 84, 148, 0, 660, 660 * 0.86f, 0, Theme.ORANGE, 1);
        String headline = localVersusWinner == 1 ? I18n.t("matchend.p1_wins")
                : localVersusWinner == 2 ? I18n.t("matchend.p2_wins") : I18n.t("matchend.you_win");
        attachText(36, screenH - 124, headline, 56, Theme.ON_ACCENT);

        // Scoreboard tile: winner's number bright, loser's dim.
        attachAngledQuad(40, screenH - 268, 128, 0, 460, 460, 24, Theme.PANEL_HOVER, 1);
        // Winner's number bright: Player 2's is the right-hand one when they won a local match.
        boolean rightWon = localVersusWinner == 2;
        attachScoreboard(40, 460, screenH - 268, 128, playerScore, opponentScore,
                rightWon ? Theme.TEXT_DIM : Theme.TEXT, rightWon ? Theme.TEXT : Theme.TEXT_DIM);

        // Reward chip (local versus pays nothing, so it has none).
        if (localVersusWinner == 0) {
            attachAngledQuad(528, screenH - 316, 60, 14, 232, 232, 0, Theme.GREEN, 1);
            attachText(550, screenH - 335, I18n.t("matchend.credits_reward", rewardEarned), 20, Theme.ON_ACCENT);
        }

        if (ranked) {
            // Below the scoreboard tile (screenH - 268 .. screenH - 396), not on top of it.
            attachText(40, screenH - 408, rankLineText(), 15, rankLineColor());
        }
        if (extraNotice != null) {
            attachText(40, screenH - (ranked ? 430 : 408), extraNotice, 13, Theme.TEXT_DIM);
        }

        // Actions: left-anchored, matching the banner's asymmetric composition.
        Container actions = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        attachRematchControls(actions);
        addMenuButton(actions, I18n.t("matchend.store"), Theme.PANEL_HOVER, Theme.TEXT, nav::showStore);
        addMenuButton(actions, I18n.t("matchend.main_menu"), Theme.PANEL_HOVER, Theme.TEXT, nav::showMainMenu);
        actions.setLocalTranslation(40, screenH - 460, 2);
        uiRoot.attachChild(actions);
    }

    private void buildDefeatLayout(Navigator nav, float screenW, float screenH) {
        float blockHeight = 84 + 24 + 108 + 20 + 24 + 24 + 96;
        float topY = (screenH + blockHeight) / 2f;
        float centerX = screenW / 2f;

        float bannerWidth = 340;
        attachAngledQuad(centerX - bannerWidth / 2f, topY, 84, 12, bannerWidth, bannerWidth, 0, Theme.PANEL_HOVER, 1);
        attachText(centerX - bannerWidth / 2f + 44, topY - 30, I18n.t("matchend.defeat"), 36, Theme.TEXT);

        float scoreY = topY - 84 - 24;
        float scoreWidth = 320;
        attachAngledQuad(centerX - scoreWidth / 2f, scoreY, 108, 0, scoreWidth, scoreWidth, 18, Theme.PANEL, 1);
        attachScoreboard(centerX - scoreWidth / 2f, scoreWidth, scoreY, 108, playerScore, opponentScore, Theme.TEXT_DIM, Theme.TEXT);

        float consolationY = scoreY - 108 - 20;
        String consolation = I18n.t("matchend.try_again", winScore);
        if (rewardEarned > 0) {
            consolation += "   " + I18n.t("matchend.consolation_reward", rewardEarned);
        }
        attachCenteredText(centerX, consolationY, consolation, 16, Theme.TEXT_DIM);

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
        attachRematchControls(actions);
        addMenuButton(actions, I18n.t("matchend.main_menu"), Theme.PANEL_HOVER, Theme.TEXT, nav::showMainMenu);
        Vector3f actionsSize = actions.getPreferredSize();
        actions.setLocalTranslation(centerX - actionsSize.x / 2f, actionsY, 2);
        uiRoot.attachChild(actions);
    }

    /** "Silver II (65 LP, +18) - PROMOTED!" style summary of the rank change from this match, or
     *  a placeholder while the background lookup/report call is still in flight or failed. */
    private String rankLineText() {
        if (!rankLookupDone) {
            return I18n.t("matchend.updating_rank");
        }
        RankState rank = rankResult.get();
        if (rank == null) {
            return I18n.t("matchend.rank_unavailable");
        }
        // Spelled out rather than a "+"/"-" sign: at this HUD font's small size a "+" glyph is
        // easy to misread as a dash, which would silently flip the apparent meaning.
        int lpChange = rank.getLpChange();
        String delta = lpChange == 0 ? I18n.t("matchend.rank_no_change")
                : lpChange > 0 ? I18n.t("matchend.rank_gained", lpChange) : I18n.t("matchend.rank_lost", -lpChange);
        String suffix = switch (rank.getPromoSeriesResult() == null ? "" : rank.getPromoSeriesResult()) {
            case "started" -> I18n.t("matchend.promo_started");
            case "ongoing" -> I18n.t("matchend.promo_ongoing");
            case "won" -> "";
            case "lost" -> I18n.t("matchend.promo_lost");
            default -> "";
        };
        if (rank.wasPromoted()) {
            suffix = I18n.t("matchend.promoted");
        } else if (rank.wasDemoted()) {
            suffix = I18n.t("matchend.demoted");
        }
        return I18n.t("matchend.rank_line", rank.formatLabel(), rank.getLp(), delta, suffix);
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

        attachCenteredText(centerX, tileTopY - tileHeight + 22, I18n.t("matchend.final_score"), 11, Theme.TEXT_DIM);
    }

    private void addMenuButton(Container menu, String label, ColorRGBA bg, ColorRGBA fg, Runnable action) {
        Button button = menu.addChild(new Button(label));
        button.setInsets(new Insets3f(6, 0, 6, 0));
        button.setBackground(new QuadBackgroundComponent(bg));
        button.setColor(fg);
        button.setFontSize(16);
        button.setPreferredSize(new Vector3f(BUTTON_WIDTH, 46, 0));
        button.addClickCommands(source -> {
            ((PlayerContext) getApplication()).getAudioManager().playSfx("button_click.ogg");
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
        rebuild((Navigator) getApplication());
        ((SimpleApplication) getApplication()).getGuiNode().attachChild(uiRoot);
        getApplication().getInputManager().setCursorVisible(true);
    }

    @Override
    protected void onDisable() {
        uiRoot.removeFromParent();
    }
}

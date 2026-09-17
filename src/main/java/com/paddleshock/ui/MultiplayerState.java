package com.paddleshock.ui;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.Insets3f;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.Panel;
import com.simsilica.lemur.TextField;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.component.SpringGridLayout;

import com.paddleshock.GameConstants;
import com.paddleshock.app.Navigator;
import com.paddleshock.app.PlayerContext;
import com.paddleshock.data.Friend;
import com.paddleshock.i18n.I18n;
import com.paddleshock.net.InviteClient;
import com.paddleshock.net.NetClient;
import com.paddleshock.net.NetHost;
import com.paddleshock.net.RankClient;
import com.paddleshock.net.RankState;
import com.paddleshock.net.StunClient;

/**
 * Multiplayer screen: pick HOST (bind a UDP port, show this machine's LAN address AND an AWS
 * lobby code for internet play, wait for a joiner) or JOIN (type the host's LAN IP:port, or enter
 * a lobby code instead). Matches {@link LoadoutState}'s full-screen dark-panel layout for visual
 * consistency.
 *
 * <p>The lobby code (see {@code aws/README.md}) brokers address exchange via AWS and drives
 * active UDP hole-punching (confirmed working across genuinely different networks - see the
 * README's Phase E section) - this doesn't guarantee traversal of every NAT (notably symmetric
 * NATs), so the JOIN side gives up with a clear error after {@link #JOIN_TIMEOUT_SECONDS} rather
 * than retrying forever, and the HOST side shows when its internet code has expired unused.
 */
public class MultiplayerState extends BaseAppState {

    private enum View { CHOICE, HOSTING, JOINING }

    /** Width of the card's inner content column (buttons, fields, divider) - the card itself is
     *  this plus the panel's left/right insets, landing in the ~440-480px range other redesigned
     *  screens use for a centered card. */
    private static final float CARD_CONTENT_WIDTH = 380f;

    private final Node uiRoot = new Node("multiplayerUi");
    private View view = View.CHOICE;

    private NetHost netHost;
    private NetClient netClient;
    private Label statusLabel;
    private TextField addressField;
    private String joinError;

    // Joiner-side only: whether the pending CONNECT should join read-only as a spectator instead
    // of as a real player - toggled on the JOINING view, reused by both the address and
    // lobby-code resolution paths (attemptConnect routes to whichever one applies either way).
    // Resets to false whenever the screen is freshly entered, same as unranked below.
    private boolean joinAsSpectator = false;

    // Host-side only: the host's own choice of whether this match counts toward the ranked
    // ladder, made before hosting starts. Authoritative - communicated to the joiner via the
    // WELCOME handshake (see NetProtocol/NetHost/NetClient) so both sides agree on the same
    // match-end path without a separate negotiation. Persists across a CANCEL/re-host within this
    // screen visit, but resets to ranked (false) whenever the screen is freshly entered.
    private boolean unranked = false;

    // Host-side only: a pre-match "your current rank" snapshot (and, if applicable, promotion
    // series callout) shown on the HOSTING screen once fetched - see beginHosting()/fetchPreMatchRank().
    // Purely informational flavor text; a failed/slow fetch just means it never appears, nothing
    // else waits on it.
    private final AtomicReference<RankState> preMatchRank = new AtomicReference<>();
    private volatile boolean preMatchRankPending = false;
    private boolean preMatchRankShown = false;

    // Host-side only: the live spectator count last shown on the HOSTING view - re-rebuilds the
    // screen whenever it changes (see update()), same "poll a background/host-owned value and
    // rebuild on change" pattern the lobby code/pre-match rank fields above already use. -1 so the
    // very first (possibly zero) reading still triggers a rebuild once hosting begins.
    private int lastShownSpectatorCount = -1;

    // Host-side: AWS lobby code registration, run off the render thread. lobbyGeneration guards
    // against a stale background result (from a cancelled/replaced hosting attempt) overwriting
    // a newer one - see beginHosting().
    private final AtomicInteger lobbyGeneration = new AtomicInteger(0);
    private final AtomicReference<String> lobbyCode = new AtomicReference<>();
    private final AtomicReference<String> lobbyError = new AtomicReference<>();
    private volatile boolean lobbyPending = false;
    private boolean lobbyResultShown = false;

    // Joiner-side: resolving a lobby code to a NetClient, also off the render thread.
    // lobbyJoinGeneration serves the same purpose as lobbyGeneration above.
    private final AtomicInteger lobbyJoinGeneration = new AtomicInteger(0);
    private final AtomicReference<NetClient> pendingCodeClient = new AtomicReference<>();
    private final AtomicReference<String> pendingCodeError = new AtomicReference<>();

    // Joiner-side: while waiting on the handshake, keep resending HELLO instead of the original
    // single send-at-construction - needed for Phase D active punching, since the host may only
    // just now be opening its own NAT path and an early one-shot HELLO would already be long
    // gone by then. Reset to 0 whenever netClient is freshly assigned (see connectByAddress and
    // the pendingCodeClient pickup in update()).
    private static final float HELLO_RETRY_INTERVAL_SECONDS = 0.3f;
    private float helloRetryTimer;

    // Joiner-side: give up on a code/address connect attempt after this long instead of retrying
    // forever with no feedback - reset to 0 whenever netClient is freshly assigned.
    private static final float JOIN_TIMEOUT_SECONDS = 20f;
    private float joinTimeoutTimer;

    // Host-side: whether the "internet code expired" message has already been shown, so it's
    // only rebuilt once (mirrors lobbyResultShown above).
    private boolean lobbyTimeoutShown = false;

    @Override
    protected void initialize(Application application) {
        // Built fresh in rebuild() every time the screen is shown, or the view changes.
    }

    private void rebuild() {
        uiRoot.detachAllChildren();
        PlayerContext app = (PlayerContext) getApplication();
        SimpleApplication simpleApp = (SimpleApplication) getApplication();
        float screenW = simpleApp.getCamera().getWidth();
        float screenH = simpleApp.getCamera().getHeight();

        Container background = new Container();
        background.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));
        background.setPreferredSize(new Vector3f(screenW, screenH, 0));
        background.setLocalTranslation(0, screenH, 0);
        uiRoot.attachChild(background);

        Container panel = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        panel.setBackground(new QuadBackgroundComponent(Theme.PANEL));
        panel.setInsets(new Insets3f(24, 32, 24, 32));

        switch (view) {
            case CHOICE -> buildChoice(app, panel);
            case HOSTING -> buildHosting(app, panel);
            case JOINING -> buildJoining(app, panel);
        }

        // Card treatment (PANEL bg + a hairline PANEL_LINE border) matching the other redesigned
        // screens - a thin outer container just slightly bigger than the panel stands in for a
        // border, since Lemur has no dedicated border component.
        Container card = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        card.setBackground(new QuadBackgroundComponent(Theme.PANEL_LINE));
        card.setInsets(new Insets3f(2, 2, 2, 2));
        card.addChild(panel);

        Vector3f cardSize = card.getPreferredSize();
        float cardTopY = (screenH + cardSize.y) / 2f;
        card.setLocalTranslation((screenW - cardSize.x) / 2f, cardTopY, 1);
        uiRoot.attachChild(card);

        if (view == View.CHOICE) {
            // Per the redesign, BACK sits outside/below the card rather than as just another row
            // inside it - same click behavior as before, just relocated and restyled to match
            // other screens' standalone BACK buttons.
            Button back = new Button(I18n.t("multiplayer.back"));
            styleButton(back, Theme.PANEL_HOVER, Theme.TEXT, 14);
            back.addClickCommands(source -> {
                app.getAudioManager().playSfx("button_click.ogg");
                ((Navigator) getApplication()).showMainMenu();
            });
            Vector3f backSize = back.getPreferredSize();
            float cardBottomY = cardTopY - cardSize.y;
            back.setLocalTranslation((screenW - backSize.x) / 2f, cardBottomY - 18, 1);
            uiRoot.attachChild(back);
        }
    }

    private void buildChoice(PlayerContext app, Container panel) {
        Label title = panel.addChild(new Label(I18n.t("multiplayer.title")));
        title.setFontSize(26);
        title.setColor(Theme.ORANGE);
        title.setInsets(new Insets3f(0, 0, 4, 0));

        Label sub = panel.addChild(new Label(I18n.t("multiplayer.subtitle")));
        sub.setFontSize(12);
        sub.setColor(Theme.TEXT_DIM);
        sub.setInsets(new Insets3f(0, 0, 18, 0));

        // Ranked/unranked row: label + description on the left, a toggle-switch visual on the
        // right, in place of the old single "RANKED (tap for unranked)" style button. Exact same
        // click behavior/state field (unranked) as before - only the visual changed.
        Container toggleRow = panel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        toggleRow.setInsets(new Insets3f(0, 0, 18, 0));

        Container toggleText = toggleRow.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        Label toggleLabel = toggleText.addChild(new Label(unranked ? I18n.t("multiplayer.unranked") : I18n.t("multiplayer.ranked")));
        toggleLabel.setFontSize(15);
        toggleLabel.setColor(Theme.TEXT);
        Label toggleHint = toggleText.addChild(new Label(unranked
                ? I18n.t("multiplayer.unranked_hint")
                : I18n.t("multiplayer.ranked_hint")));
        toggleHint.setFontSize(11);
        toggleHint.setColor(Theme.TEXT_DIM);

        Button toggleSwitch = toggleRow.addChild(buildToggleSwitch(app));
        toggleSwitch.setInsets(new Insets3f(6, 16, 0, 0));

        Panel divider = panel.addChild(new Panel());
        divider.setBackground(new QuadBackgroundComponent(Theme.PANEL_LINE));
        divider.setPreferredSize(new Vector3f(CARD_CONTENT_WIDTH, 1, 0));
        divider.setInsets(new Insets3f(0, 0, 18, 0));

        Button hostButton = panel.addChild(new Button(I18n.t("multiplayer.host_match")));
        styleButton(hostButton, Theme.ORANGE, Theme.ON_ACCENT, 18);
        hostButton.setPreferredSize(new Vector3f(CARD_CONTENT_WIDTH, 50, 0));
        hostButton.setInsets(new Insets3f(0, 0, 22, 0));
        hostButton.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            beginHosting(app);
        });

        // NOTE: the actual address/lobby-code field and CONNECT button live on the JOINING view
        // (buildJoining, below) exactly as before - that view/state-machine transition is
        // untouched. This button is just the restyled entry point into that same flow, now
        // presented as part of the card rather than a separate loose button.
        Button joinButton = panel.addChild(new Button(I18n.t("multiplayer.join_match")));
        styleButton(joinButton, Theme.PANEL_HOVER, Theme.TEXT, 16);
        joinButton.setPreferredSize(new Vector3f(CARD_CONTENT_WIDTH, 46, 0));
        joinButton.setInsets(new Insets3f(0, 0, 22, 0));
        joinButton.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            view = View.JOINING;
            joinError = null;
            rebuild();
        });

        Button tournamentButton = panel.addChild(new Button(I18n.t("multiplayer.tournament_button")));
        styleButton(tournamentButton, Theme.BLUE, Theme.ON_ACCENT, 16);
        tournamentButton.setPreferredSize(new Vector3f(CARD_CONTENT_WIDTH, 46, 0));
        tournamentButton.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            ((Navigator) getApplication()).showTournament();
        });
    }

    /** Small pill-shaped toggle-switch visual: a single Button whose background/text flips
     *  between the two states, the same Button-driven pattern every other control on this screen
     *  already uses reliably (a separate sliding "knob" panel attached as a raw scene-graph child
     *  of the Button turned out not to composite visibly - Lemur's GuiControl-managed layout
     *  apparently doesn't extend to non-managed children the way a plain Node's would, and wasn't
     *  worth chasing further for a cosmetic flourish). Reuses the same click command as the old
     *  toggle button, so the {@code unranked} field and its effect on hosting are unchanged. */
    private Button buildToggleSwitch(PlayerContext app) {
        Button toggle = new Button(unranked ? I18n.t("common.off") : I18n.t("common.on"));
        toggle.setBackground(new QuadBackgroundComponent(unranked ? Theme.PANEL_HOVER : Theme.GREEN));
        toggle.setColor(unranked ? Theme.TEXT_DIM : Theme.ON_ACCENT);
        toggle.setFontSize(13);
        toggle.setTextHAlignment(com.simsilica.lemur.HAlignment.Center);
        toggle.setPreferredSize(new Vector3f(56f, 26f, 0));
        toggle.setInsets(new Insets3f(0, 0, 0, 0));
        toggle.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            unranked = !unranked;
            rebuild();
        });
        return toggle;
    }

    /** Same pill-shaped toggle-switch visual as {@link #buildToggleSwitch}, wired to
     *  {@link #joinAsSpectator} instead of {@link #unranked}. */
    private Button buildSpectateToggle(PlayerContext app) {
        Button toggle = new Button(joinAsSpectator ? I18n.t("common.on") : I18n.t("common.off"));
        toggle.setBackground(new QuadBackgroundComponent(joinAsSpectator ? Theme.BLUE : Theme.PANEL_HOVER));
        toggle.setColor(joinAsSpectator ? Theme.ON_ACCENT : Theme.TEXT_DIM);
        toggle.setFontSize(13);
        toggle.setTextHAlignment(com.simsilica.lemur.HAlignment.Center);
        toggle.setPreferredSize(new Vector3f(56f, 26f, 0));
        toggle.setInsets(new Insets3f(0, 0, 0, 0));
        toggle.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            joinAsSpectator = !joinAsSpectator;
            rebuild();
        });
        return toggle;
    }

    private void beginHosting(PlayerContext app) {
        boolean ranked = !unranked;
        try {
            netHost = app.startHostMatch(GameConstants.MULTIPLAYER_DEFAULT_PORT, ranked);
        } catch (SocketException e) {
            // Default port already in use (e.g. a previous instance still shutting down) -
            // fall back to an OS-assigned free port rather than dead-ending the flow.
            try {
                netHost = app.startHostMatch(0, ranked);
            } catch (SocketException e2) {
                joinError = I18n.t("multiplayer.error_udp_port", e2.getMessage());
                view = View.CHOICE;
                rebuild();
                return;
            }
        }
        view = View.HOSTING;
        lastShownSpectatorCount = -1;
        rebuild();
        beginLobbyRegistration();
        if (ranked) {
            fetchPreMatchRank(app);
        }
    }

    /** Kicks off (off the render thread) a fetch of this player's current rank, purely to show a
     *  "your rank" / promotion-series callout on the HOSTING screen before the match starts - see
     *  {@link #buildHosting}. Never blocks hosting/joining; a failed fetch just shows nothing. */
    private void fetchPreMatchRank(PlayerContext app) {
        preMatchRank.set(null);
        preMatchRankShown = false;
        preMatchRankPending = true;
        String playerId = app.getProfile().getPlayerId();
        Thread thread = new Thread(() -> {
            RankState rank;
            try {
                rank = app.getRankService().getRank(playerId);
            } catch (IOException e) {
                rank = null;
            }
            preMatchRank.set(rank);
            preMatchRankPending = false;
        }, "pre-match-rank");
        thread.setDaemon(true);
        thread.start();
    }

    /** Kicks off (on a background thread - it's a blocking HTTPS call) registering this host
     *  with the AWS lobby broker, so players on a different network can join via a short code
     *  instead of needing the LAN address. Purely additive: if it fails (no internet, STUN
     *  blocked, Lambda unreachable) LAN-only hosting still works exactly as before. */
    private void beginLobbyRegistration() {
        int myGeneration = lobbyGeneration.incrementAndGet();
        lobbyCode.set(null);
        lobbyError.set(null);
        lobbyResultShown = false;
        lobbyTimeoutShown = false;
        NetHost hostRef = netHost;
        if (hostRef.getPublicAddress() == null) {
            lobbyPending = false;
            return;
        }
        if (hostRef.isSymmetricNatSuspected()) {
            // Direct/hole-punched internet play is unlikely to work from behind a symmetric NAT -
            // surface a clear reason instead of silently registering a code that will just hang
            // for whoever tries it. LAN-only hosting (the address label above) is unaffected.
            lobbyPending = false;
            lobbyError.set(StunClient.SYMMETRIC_NAT_MESSAGE);
            return;
        }
        lobbyPending = true;
        Thread thread = new Thread(() -> {
            String code = null;
            String error = null;
            try {
                code = hostRef.registerLobby();
            } catch (IOException e) {
                error = e.getMessage();
            }
            if (lobbyGeneration.get() != myGeneration) {
                return; // superseded by a newer hosting attempt (or the screen was left) - discard
            }
            lobbyCode.set(code);
            lobbyError.set(error);
            lobbyPending = false;
            if (code != null) {
                // Continues on this same background thread after the code is already shown to
                // the user - polls the lobby for a joiner and actively punches once one appears
                // (Phase D). Long-running; NetHost.close() (cancelHosting()/onDisable()) stops it.
                hostRef.pollAndPunchUntilJoined(code);
            }
        }, "lobby-register");
        thread.setDaemon(true);
        thread.start();
    }

    private void buildHosting(PlayerContext app, Container panel) {
        boolean hostingUnranked = netHost != null && !netHost.isRanked();
        Label title = panel.addChild(new Label(hostingUnranked ? I18n.t("multiplayer.hosting_unranked") : I18n.t("multiplayer.hosting_title")));
        title.setFontSize(26);
        title.setColor(hostingUnranked ? Theme.TEXT_DIM : Theme.ORANGE);
        title.setInsets(new Insets3f(0, 0, 4, 0));

        if (hostingUnranked) {
            Label unrankedBadge = panel.addChild(new Label(I18n.t("multiplayer.hosting_unranked_hint")));
            unrankedBadge.setFontSize(13);
            unrankedBadge.setColor(Theme.GREEN);
            unrankedBadge.setInsets(new Insets3f(0, 0, 8, 0));
        } else {
            RankState rank = preMatchRank.get();
            if (rank != null) {
                Label rankLabel = panel.addChild(new Label(I18n.t("multiplayer.your_rank", rank.formatLabel(), rank.getLp())));
                rankLabel.setFontSize(12);
                rankLabel.setColor(Theme.TEXT_DIM);
                rankLabel.setInsets(new Insets3f(0, 0, 4, 0));

                if (rank.isInPromoSeries()) {
                    String nextLabel = rank.nextPromoLabel();
                    String promoText = nextLabel != null
                            ? I18n.t("multiplayer.promo_next", nextLabel)
                            : I18n.t("multiplayer.promo_generic");
                    Label promoLabel = panel.addChild(new Label(promoText));
                    promoLabel.setFontSize(13);
                    promoLabel.setColor(Theme.ORANGE);
                    promoLabel.setInsets(new Insets3f(0, 0, 8, 0));
                }
            }
        }

        Label addressLabel = panel.addChild(new Label(getLocalIpAddress() + " : " + netHost.getPort()));
        addressLabel.setFontSize(22);
        addressLabel.setColor(Theme.TEXT);
        addressLabel.setInsets(new Insets3f(0, 0, 4, 0));

        Label hint = panel.addChild(new Label(I18n.t("multiplayer.same_network_hint")));
        hint.setFontSize(12);
        hint.setColor(Theme.TEXT_DIM);
        hint.setInsets(new Insets3f(0, 0, 10, 0));

        String code = lobbyCode.get();
        String error = lobbyError.get();
        NetHost hostRef = netHost;
        boolean punchExpired = hostRef != null && hostRef.isLobbyAttemptFinished() && !hostRef.hasJoiner();

        if (lobbyPending) {
            Label pending = panel.addChild(new Label(I18n.t("multiplayer.looking_up_code")));
            pending.setFontSize(12);
            pending.setColor(Theme.TEXT_DIM);
            pending.setInsets(new Insets3f(0, 0, 14, 0));
        } else if (code != null && punchExpired) {
            Label expiredLabel = panel.addChild(new Label(I18n.t("multiplayer.code_expired", code)));
            expiredLabel.setFontSize(13);
            expiredLabel.setColor(Theme.TEXT_DIM);
            expiredLabel.setInsets(new Insets3f(0, 0, 14, 0));
        } else if (code != null) {
            Container codeRow = panel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
            codeRow.setInsets(new Insets3f(0, 0, 4, 0));
            Label codeLabel = codeRow.addChild(new Label(I18n.t("multiplayer.internet_code", code)));
            codeLabel.setFontSize(20);
            codeLabel.setColor(Theme.BLUE);
            Button copyButton = codeRow.addChild(new Button(I18n.t("multiplayer.copy")));
            copyButton.setInsets(new Insets3f(0, 12, 0, 0));
            copyButton.setBackground(new QuadBackgroundComponent(Theme.PANEL_HOVER));
            copyButton.setColor(Theme.TEXT);
            copyButton.setFontSize(13);
            copyButton.addClickCommands(source -> {
                app.getAudioManager().playSfx("button_click.ogg");
                copyToClipboard(code);
            });
            Label codeHint = panel.addChild(new Label(I18n.t("multiplayer.diff_network_hint")));
            codeHint.setFontSize(12);
            codeHint.setColor(Theme.TEXT_DIM);
            codeHint.setInsets(new Insets3f(0, 0, 14, 0));

            buildInviteFriendsRow(app, panel, code);
        } else if (StunClient.SYMMETRIC_NAT_MESSAGE.equals(error)) {
            Label errorLabel = panel.addChild(new Label(error));
            errorLabel.setFontSize(12);
            errorLabel.setColor(Theme.ORANGE);
            errorLabel.setInsets(new Insets3f(0, 0, 14, 0));
        } else if (error != null) {
            Label errorLabel = panel.addChild(new Label(I18n.t("multiplayer.code_unavailable", error)));
            errorLabel.setFontSize(11);
            errorLabel.setColor(Theme.TEXT_DIM);
            errorLabel.setInsets(new Insets3f(0, 0, 14, 0));
        }

        int spectatorCount = hostRef == null ? 0 : hostRef.getSpectatorCount();

        statusLabel = panel.addChild(new Label(I18n.t("multiplayer.waiting_for_opponent")));
        statusLabel.setFontSize(16);
        statusLabel.setColor(Theme.TEXT);
        statusLabel.setInsets(new Insets3f(0, 0, spectatorCount > 0 ? 4 : 18, 0));

        if (spectatorCount > 0) {
            Label spectatorLabel = panel.addChild(new Label(spectatorCount == 1
                    ? I18n.t("multiplayer.spectator_watching_one", spectatorCount)
                    : I18n.t("multiplayer.spectator_watching_many", spectatorCount)));
            spectatorLabel.setFontSize(11);
            spectatorLabel.setColor(Theme.TEXT_DIM);
            spectatorLabel.setInsets(new Insets3f(0, 0, 18, 0));
        }

        Button cancel = panel.addChild(new Button(I18n.t("multiplayer.cancel")));
        styleButton(cancel, Theme.PANEL_HOVER, Theme.TEXT, 14);
        cancel.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            cancelHosting();
        });
    }

    /** One "INVITE" button per local friend (see {@code PlayerProfile#getFriends()}) - only shown
     *  once a real lobby code exists. No friends -> nothing shown, no error/empty-state needed
     *  here specifically (the Friends screen already covers that). Each button fires a
     *  best-effort background {@code sendInvite} call; a failure just leaves the button's label
     *  unchanged rather than blocking/interrupting hosting. */
    private void buildInviteFriendsRow(PlayerContext app, Container panel, String code) {
        List<Friend> friendsList = app.getProfile().getFriends();
        if (friendsList.isEmpty()) {
            return;
        }
        Label title = panel.addChild(new Label(I18n.t("multiplayer.invite_a_friend")));
        title.setFontSize(12);
        title.setColor(Theme.TEXT_DIM);
        title.setInsets(new Insets3f(0, 0, 6, 0));

        for (Friend friend : friendsList) {
            Container row = panel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
            row.setInsets(new Insets3f(2, 0, 2, 0));

            Label nameLabel = row.addChild(new Label(friend.getNickname()));
            nameLabel.setFontSize(13);
            nameLabel.setColor(Theme.TEXT);
            nameLabel.setPreferredSize(new Vector3f(CARD_CONTENT_WIDTH - 90, nameLabel.getPreferredSize().y, 0));

            Button invite = row.addChild(new Button(I18n.t("multiplayer.invite")));
            invite.setBackground(new QuadBackgroundComponent(Theme.BLUE));
            invite.setColor(Theme.ON_ACCENT);
            invite.setFontSize(12);
            invite.addClickCommands(source -> {
                app.getAudioManager().playSfx("button_click.ogg");
                invite.setText(I18n.t("multiplayer.sent"));
                invite.setEnabled(false);
                String selfId = app.getProfile().getPlayerId();
                String selfName = app.getSteamManager().getPersonaName().orElseGet(() -> app.getProfile().getDisplayName());
                Thread thread = new Thread(() -> {
                    try {
                        app.getInviteService().sendInvite(selfId, selfName, friend.getPlayerId(), code);
                    } catch (java.io.IOException e) {
                        // Best-effort - inviting a friend is a convenience on top of the lobby
                        // code, which is still shown/copyable regardless of whether this succeeds.
                    }
                }, "send-invite");
                thread.setDaemon(true);
                thread.start();
            });
        }
    }

    private void cancelHosting() {
        if (netHost != null) {
            netHost.close();
            netHost = null;
        }
        preMatchRank.set(null);
        preMatchRankShown = false;
        view = View.CHOICE;
        rebuild();
    }

    private void buildJoining(PlayerContext app, Container panel) {
        Label title = panel.addChild(new Label(I18n.t("multiplayer.join_match_title")));
        title.setFontSize(26);
        title.setColor(Theme.BLUE);
        title.setInsets(new Insets3f(0, 0, 4, 0));

        Label hint = panel.addChild(new Label(I18n.t("multiplayer.join_hint")));
        hint.setFontSize(12);
        hint.setColor(Theme.TEXT_DIM);
        hint.setInsets(new Insets3f(0, 0, 8, 0));

        addressField = panel.addChild(new TextField("127.0.0.1:" + GameConstants.MULTIPLAYER_DEFAULT_PORT));
        addressField.setFontSize(16);
        addressField.setColor(Theme.TEXT);
        addressField.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND_2));
        addressField.setPreferredWidth(CARD_CONTENT_WIDTH);
        addressField.setInsets(new Insets3f(8, 10, 8, 10));

        // Same address/lobby-code field and resolution logic either way - this toggle only
        // changes which HELLO role attemptConnect sends once CONNECT is clicked (see
        // connectByAddress/connectByLobbyCode).
        Container spectateRow = panel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        spectateRow.setInsets(new Insets3f(8, 0, 4, 0));
        Container spectateText = spectateRow.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        Label spectateLabel = spectateText.addChild(new Label(I18n.t("multiplayer.join_as_spectator")));
        spectateLabel.setFontSize(13);
        spectateLabel.setColor(Theme.TEXT);
        Label spectateHint = spectateText.addChild(new Label(I18n.t("multiplayer.spectator_hint")));
        spectateHint.setFontSize(11);
        spectateHint.setColor(Theme.TEXT_DIM);
        Button spectateToggle = spectateRow.addChild(buildSpectateToggle(app));
        spectateToggle.setInsets(new Insets3f(6, 16, 0, 0));

        statusLabel = panel.addChild(new Label(joinError != null ? joinError : ""));
        statusLabel.setFontSize(13);
        statusLabel.setColor(Theme.ORANGE);
        statusLabel.setInsets(new Insets3f(10, 0, 10, 0));

        Button connect = panel.addChild(new Button(I18n.t("multiplayer.connect")));
        styleButton(connect, Theme.PANEL_HOVER, Theme.TEXT, 18);
        connect.setPreferredSize(new Vector3f(CARD_CONTENT_WIDTH, 46, 0));
        connect.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            attemptConnect(app);
        });

        Button back = panel.addChild(new Button(I18n.t("multiplayer.back")));
        styleButton(back, Theme.PANEL_HOVER, Theme.TEXT, 14);
        back.setInsets(new Insets3f(8, 0, 0, 0));
        back.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            if (netClient != null) {
                netClient.close();
                netClient = null;
            }
            view = View.CHOICE;
            rebuild();
        });
    }

    private void attemptConnect(PlayerContext app) {
        String raw = addressField.getText().trim();
        if (raw.isEmpty()) {
            joinError = I18n.t("multiplayer.error_enter_address");
            statusLabel.setText(joinError);
            return;
        }
        if (raw.contains(":")) {
            connectByAddress(app, raw);
        } else {
            connectByLobbyCode(app, raw.toUpperCase(java.util.Locale.ROOT));
        }
    }

    /** Called by {@code PlayerContext.acceptInvite} once this screen has just been enabled:
     *  jumps straight to the JOINING view with {@code lobbyCode} pre-filled and immediately
     *  attempts to connect, reusing {@link #connectByLobbyCode} exactly - the same code path a
     *  player typing a code in by hand would take. */
    public void acceptInviteAndConnect(String lobbyCode) {
        PlayerContext app = (PlayerContext) getApplication();
        view = View.JOINING;
        joinError = null;
        rebuild();
        String normalized = lobbyCode == null ? "" : lobbyCode.trim().toUpperCase(java.util.Locale.ROOT);
        if (addressField != null) {
            addressField.setText(normalized);
        }
        if (!normalized.isEmpty()) {
            connectByLobbyCode(app, normalized);
        }
    }

    private void connectByAddress(PlayerContext app, String raw) {
        int colon = raw.lastIndexOf(':');
        if (colon <= 0 || colon == raw.length() - 1) {
            joinError = I18n.t("multiplayer.error_address_format", GameConstants.MULTIPLAYER_DEFAULT_PORT);
            statusLabel.setText(joinError);
            return;
        }
        String host = raw.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(raw.substring(colon + 1));
        } catch (NumberFormatException e) {
            joinError = I18n.t("multiplayer.error_bad_port");
            statusLabel.setText(joinError);
            return;
        }

        try {
            if (netClient != null) {
                netClient.close();
            }
            netClient = app.joinMatch(host, port, joinAsSpectator);
        } catch (IOException e) {
            joinError = I18n.t("multiplayer.error_connect_failed", raw, e.getMessage());
            statusLabel.setText(joinError);
            return;
        }
        helloRetryTimer = 0f;
        joinTimeoutTimer = 0f;
        joinError = null;
        statusLabel.setColor(Theme.TEXT);
        statusLabel.setText(I18n.t("multiplayer.connecting"));
    }

    /** Resolves a lobby code to a host address via AWS (blocking HTTPS calls), off the render
     *  thread - the result is picked up in {@link #update}. {@code lobbyJoinGeneration} lets a
     *  newer attempt (another click, or leaving the screen) supersede an older one still in
     *  flight; the superseded thread closes its own result instead of leaking it. */
    private void connectByLobbyCode(PlayerContext app, String code) {
        if (netClient != null) {
            netClient.close();
            netClient = null;
        }
        joinError = null;
        statusLabel.setColor(Theme.TEXT);
        statusLabel.setText(I18n.t("multiplayer.looking_up_lobby_code"));

        int myGeneration = lobbyJoinGeneration.incrementAndGet();
        pendingCodeClient.set(null);
        pendingCodeError.set(null);
        boolean spectator = joinAsSpectator;
        Thread thread = new Thread(() -> {
            try {
                NetClient client = app.joinMatchByLobbyCode(code, spectator);
                if (lobbyJoinGeneration.get() == myGeneration) {
                    pendingCodeClient.set(client);
                } else {
                    client.close();
                }
            } catch (IOException e) {
                if (lobbyJoinGeneration.get() == myGeneration) {
                    pendingCodeError.set(e.getMessage());
                }
            }
        }, "lobby-join");
        thread.setDaemon(true);
        thread.start();
    }

    private void copyToClipboard(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        } catch (Exception e) {
            // Clipboard access can fail in some sandboxed/headless environments - not worth
            // surfacing an error for a convenience feature; the code is still shown on screen.
        }
    }

    private void styleButton(Button button, com.jme3.math.ColorRGBA bg, com.jme3.math.ColorRGBA fg, int fontSize) {
        button.setInsets(new Insets3f(6, 0, 6, 0));
        button.setBackground(new QuadBackgroundComponent(bg));
        button.setColor(fg);
        button.setFontSize(fontSize);
        button.setPreferredSize(new Vector3f(340, 46, 0));
    }

    /** First non-loopback IPv4 address on an up interface - what the joiner should type in. */
    private String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            // fall through to loopback below
        }
        return "127.0.0.1";
    }

    @Override
    public void update(float tpf) {
        PlayerContext app = (PlayerContext) getApplication();

        if (view == View.HOSTING && !lobbyResultShown && !lobbyPending
                && (lobbyCode.get() != null || lobbyError.get() != null)) {
            lobbyResultShown = true;
            rebuild();
        }
        if (view == View.HOSTING && !lobbyTimeoutShown && netHost != null
                && netHost.isLobbyAttemptFinished() && !netHost.hasJoiner()) {
            lobbyTimeoutShown = true;
            rebuild();
        }
        if (view == View.HOSTING && !preMatchRankShown && !preMatchRankPending && preMatchRank.get() != null) {
            preMatchRankShown = true;
            rebuild();
        }
        if (view == View.HOSTING && netHost != null) {
            int spectatorCount = netHost.getSpectatorCount();
            if (spectatorCount != lastShownSpectatorCount) {
                lastShownSpectatorCount = spectatorCount;
                rebuild();
            }
        }

        if (view == View.JOINING && netClient == null) {
            NetClient resolved = pendingCodeClient.getAndSet(null);
            if (resolved != null) {
                netClient = resolved;
                helloRetryTimer = 0f;
                joinTimeoutTimer = 0f;
                statusLabel.setColor(Theme.TEXT);
                statusLabel.setText(I18n.t("multiplayer.connecting"));
            } else {
                String error = pendingCodeError.getAndSet(null);
                if (error != null) {
                    // "lobby already has a joiner" is the losing side of a join race (two players
                    // entering the same code at nearly the same time) - a different message than
                    // a bad/expired code, since retrying with a fresh code from the host is the
                    // right next step here, not re-typing the same one. A symmetric-NAT diagnosis
                    // is already a complete, clear message on its own - shown as-is rather than
                    // wrapped in the generic "could not find that code" framing.
                    if (StunClient.SYMMETRIC_NAT_MESSAGE.equals(error)) {
                        joinError = error;
                    } else if (error.contains("already has a joiner")) {
                        joinError = I18n.t("multiplayer.error_code_has_joiner");
                    } else {
                        joinError = I18n.t("multiplayer.error_code_not_found", error);
                    }
                    statusLabel.setColor(Theme.ORANGE);
                    statusLabel.setText(joinError);
                }
            }
        }

        if (view == View.HOSTING && netHost != null && netHost.hasJoiner()) {
            // Null the field BEFORE handing off: enterHostedMatch() disables this state, which
            // synchronously fires onDisable() - that must not see (and close) the socket we just
            // handed to GameplayAppState.
            NetHost handoff = netHost;
            netHost = null;
            app.enterHostedMatch(handoff);
        } else if (view == View.JOINING && netClient != null) {
            if (netClient.isConnected()) {
                NetClient handoff = netClient;
                netClient = null;
                app.enterJoinedMatch(handoff);
            } else if (netClient.isRejected()) {
                joinError = I18n.t("multiplayer.error_host_full");
                statusLabel.setColor(Theme.ORANGE);
                statusLabel.setText(joinError);
                netClient.close();
                netClient = null;
            } else {
                joinTimeoutTimer += tpf;
                if (joinTimeoutTimer >= JOIN_TIMEOUT_SECONDS) {
                    // Give up instead of retrying forever with no feedback - the host may be
                    // offline, the code may be stale, or this network's NAT may not be
                    // traversable even with active punching (Phase D has no guarantee against a
                    // symmetric NAT on either side).
                    joinError = I18n.t("multiplayer.error_connect_timeout");
                    statusLabel.setColor(Theme.ORANGE);
                    statusLabel.setText(joinError);
                    netClient.close();
                    netClient = null;
                } else {
                    // Keep resending HELLO instead of the original single send-at-construction:
                    // for internet play (Phase D) the host may still be actively punching its own
                    // NAT open when the first HELLO went out, so it needs a retry to land once
                    // that finishes rather than only ever getting the one early attempt.
                    helloRetryTimer += tpf;
                    if (helloRetryTimer >= HELLO_RETRY_INTERVAL_SECONDS) {
                        helloRetryTimer = 0f;
                        netClient.sendHello();
                    }
                    if (joinTimeoutTimer >= 4f && joinTimeoutTimer - tpf < 4f) {
                        statusLabel.setText(I18n.t("multiplayer.still_trying"));
                    }
                }
            }
        }
    }

    @Override
    protected void cleanup(Application application) {
        // uiRoot is detached in onDisable(); net resources are handed off to GameplayAppState on
        // success, or closed explicitly on cancel/back - nothing else owns native resources here.
    }

    @Override
    protected void onEnable() {
        view = View.CHOICE;
        joinError = null;
        unranked = false;
        joinAsSpectator = false;
        preMatchRank.set(null);
        preMatchRankShown = false;
        lastShownSpectatorCount = -1;
        rebuild();
        ((SimpleApplication) getApplication()).getGuiNode().attachChild(uiRoot);
        getApplication().getInputManager().setCursorVisible(true);
    }

    @Override
    protected void onDisable() {
        uiRoot.removeFromParent();
        // If the player navigates away mid-flow (e.g. via a state switch outside this class'
        // own BACK/CANCEL buttons) without a match having started, don't leak the socket/thread.
        if (netHost != null) {
            netHost.close();
            netHost = null;
        }
        if (netClient != null) {
            netClient.close();
            netClient = null;
        }
        // Supersede any in-flight background lobby lookups so a late result closes itself
        // instead of leaking (see connectByLobbyCode/beginLobbyRegistration), and close any
        // result that already arrived but was never consumed.
        lobbyGeneration.incrementAndGet();
        lobbyJoinGeneration.incrementAndGet();
        NetClient orphaned = pendingCodeClient.getAndSet(null);
        if (orphaned != null) {
            orphaned.close();
        }
    }
}

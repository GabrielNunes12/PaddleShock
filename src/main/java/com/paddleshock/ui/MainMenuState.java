package com.paddleshock.ui;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.FillMode;
import com.simsilica.lemur.HAlignment;
import com.simsilica.lemur.Insets3f;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.component.SpringGridLayout;

import com.paddleshock.app.Navigator;
import com.paddleshock.app.PlayerContext;
import com.paddleshock.i18n.I18n;
import com.paddleshock.net.InviteClient;

/**
 * Main menu: a two-column split - a left "hero" panel ({@link Theme#BACKGROUND_2}) carrying the
 * wordmark, tagline and the primary PLAY VS AI call-to-action, and a right panel
 * ({@link Theme#BACKGROUND}) listing the other six destinations as bordered "nav card" rows. See
 * the redesign spec this was built from for the full rationale; visually a reskin of the previous
 * single centered button stack, same destinations, same click targets.
 */
public class MainMenuState extends BaseAppState {

    /** Width of the hero-panel accent strip along its left edge. */
    private static final float ACCENT_WIDTH = 6f;

    // Pending-invite polling: a first check a few seconds after the menu is shown, then a repeat
    // check on a timer while it's up (same style of background-poll-then-rebuild TournamentState
    // already established for its own waiting-room polling, just a much longer interval here since
    // this is a convenience mailbox check, not a live match wait). Purely additive/non-blocking - a
    // failed/slow check never affects the menu itself, it just means no banner appears this time.
    private static final float INVITE_POLL_INITIAL_DELAY_SECONDS = 3f;
    private static final float INVITE_POLL_INTERVAL_SECONDS = 12f;

    private final Node uiRoot = new Node("mainMenuUi");
    private final Node inviteBannerRoot = new Node("inviteBannerUi");

    private final AtomicInteger invitePollGeneration = new AtomicInteger(0);
    private final AtomicReference<List<InviteClient.Invite>> pendingInvites = new AtomicReference<>();
    private final AtomicBoolean invitePollPending = new AtomicBoolean(false);
    private float invitePollTimer;
    private boolean inviteBannerShown;

    @Override
    protected void initialize(Application application) {
        // Built fresh in rebuild() every time the screen is shown.
    }

    private void rebuild(SimpleApplication simpleApp) {
        uiRoot.detachAllChildren();

        float screenW = simpleApp.getCamera().getWidth();
        float screenH = simpleApp.getCamera().getHeight();

        float leftWidth = screenW * 0.38f;
        float rightWidth = screenW - leftWidth;

        buildLeftHero(leftWidth, screenH);
        buildRightNav(leftWidth, rightWidth, screenH);
    }

    /** Left hero panel: wordmark, tagline, and the big PLAY VS AI CTA - everything a brand-new
     *  player needs to get into a match with zero extra clicks. */
    private void buildLeftHero(float leftWidth, float screenH) {
        Container heroPanel = new Container();
        heroPanel.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND_2));
        heroPanel.setPreferredSize(new Vector3f(leftWidth, screenH, 0));
        heroPanel.setLocalTranslation(0, screenH, 0);
        uiRoot.attachChild(heroPanel);

        Container accentBar = new Container();
        accentBar.setBackground(new QuadBackgroundComponent(Theme.ORANGE));
        accentBar.setPreferredSize(new Vector3f(ACCENT_WIDTH, screenH, 0));
        accentBar.setLocalTranslation(0, screenH, 1);
        uiRoot.attachChild(accentBar);

        Label paddleLabel = new Label(I18n.t("menu.wordmark.paddle"));
        paddleLabel.setFontSize(48);
        paddleLabel.setColor(Theme.TEXT);
        uiRoot.attachChild(paddleLabel);

        Label shockLabel = new Label(I18n.t("menu.wordmark.shock"));
        shockLabel.setFontSize(48);
        shockLabel.setColor(Theme.ORANGE);
        uiRoot.attachChild(shockLabel);

        float logoWidth = paddleLabel.getPreferredSize().x + shockLabel.getPreferredSize().x;
        float logoY = screenH * 0.78f;
        float logoX = (leftWidth - logoWidth) / 2f;
        paddleLabel.setLocalTranslation(logoX, logoY, 2);
        shockLabel.setLocalTranslation(logoX + paddleLabel.getPreferredSize().x, logoY, 2);

        Label tagline = new Label(I18n.t("menu.tagline"));
        tagline.setFontSize(13);
        tagline.setColor(Theme.TEXT_DIM);
        uiRoot.attachChild(tagline);
        float taglineY = logoY - paddleLabel.getPreferredSize().y - 14;
        tagline.setLocalTranslation((leftWidth - tagline.getPreferredSize().x) / 2f, taglineY, 2);

        float ctaWidth = leftWidth - 64;
        float ctaHeight = 54;
        Button playVsAi = new Button(I18n.t("menu.play_vs_ai"));
        playVsAi.setBackground(new QuadBackgroundComponent(Theme.ORANGE));
        playVsAi.setColor(Theme.ON_ACCENT);
        playVsAi.setFontSize(20);
        playVsAi.setPreferredSize(new Vector3f(ctaWidth, ctaHeight, 0));
        playVsAi.addClickCommands(source -> {
            ((PlayerContext) getApplication()).getAudioManager().playSfx("button_click.ogg");
            ((Navigator) getApplication()).showLoadout();
        });
        uiRoot.attachChild(playVsAi);
        playVsAi.setLocalTranslation(32, taglineY - 40, 2);
    }

    /** Right panel: the other six destinations as bordered nav-card rows. */
    private void buildRightNav(float leftWidth, float rightWidth, float screenH) {
        Container rightPanel = new Container();
        rightPanel.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));
        rightPanel.setPreferredSize(new Vector3f(rightWidth, screenH, 0));
        rightPanel.setLocalTranslation(leftWidth, screenH, 0);
        uiRoot.attachChild(rightPanel);

        float cardWidth = rightWidth - 96;

        Navigator nav1 = (Navigator) getApplication();
        Container nav = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        nav.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));
        addNavCard(nav, I18n.t("menu.multiplayer"), Theme.BLUE_DIM, cardWidth, nav1::showMultiplayer);
        addNavCard(nav, I18n.t("menu.leaderboard"), Theme.ORANGE_DIM, cardWidth, nav1::showLeaderboard);
        addNavCard(nav, I18n.t("menu.profile"), Theme.GREEN_DIM, cardWidth, nav1::showProfile);
        addNavCard(nav, I18n.t("menu.friends"), Theme.PANEL_HOVER, cardWidth, nav1::showFriends);
        addNavCard(nav, I18n.t("menu.store"), Theme.PANEL_HOVER, cardWidth, nav1::showStore);
        addNavCard(nav, I18n.t("menu.settings"), Theme.PANEL_HOVER, cardWidth, () -> nav1.showOptions(nav1::showMainMenu));
        addNavCard(nav, I18n.t("menu.how_to_play"), Theme.PANEL_HOVER, cardWidth, () -> nav1.showHowToPlay(nav1::showMainMenu));
        addNavCard(nav, I18n.t("menu.quit"), Theme.PANEL_HOVER, cardWidth, () -> getApplication().stop());

        Vector3f navSize = nav.getPreferredSize();
        nav.setLocalTranslation(leftWidth + 48, (screenH + navSize.y) / 2f, 1);
        uiRoot.attachChild(nav);
    }

    /** One nav-destination row: a {@link Theme#PANEL_LINE}-bordered, {@link Theme#PANEL}-filled
     *  card with a small colored icon-chip and a label button that carries both the text and the
     *  click behavior (and, since it's a stock Lemur {@link Button}, its built-in hover/press
     *  feedback too - no manual hover styling invented here). */
    private void addNavCard(Container column, String label, ColorRGBA chipColor, float cardWidth, Runnable action) {
        Container rowMargin = column.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        rowMargin.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));
        rowMargin.setInsets(new Insets3f(6, 0, 6, 0));

        Container border = rowMargin.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        border.setBackground(new QuadBackgroundComponent(Theme.PANEL_LINE));
        border.setInsets(new Insets3f(1, 1, 1, 1));

        Container card = border.addChild(new Container(
                new SpringGridLayout(Axis.X, Axis.Y, FillMode.Last, FillMode.None)));
        card.setBackground(new QuadBackgroundComponent(Theme.PANEL));
        card.setInsets(new Insets3f(10, 16, 10, 16));

        Container chip = card.addChild(new Container());
        chip.setBackground(new QuadBackgroundComponent(chipColor));
        chip.setPreferredSize(new Vector3f(28, 28, 0));
        chip.setInsets(new Insets3f(0, 0, 0, 14));

        Button navButton = card.addChild(new Button(label));
        navButton.setBackground(new QuadBackgroundComponent(Theme.PANEL));
        navButton.setColor(Theme.TEXT);
        navButton.setFontSize(16);
        navButton.addClickCommands(source -> {
            ((PlayerContext) getApplication()).getAudioManager().playSfx("button_click.ogg");
            action.run();
        });

        card.setPreferredSize(new Vector3f(cardWidth - 2, card.getPreferredSize().y, 0));
    }

    @Override
    public void update(float tpf) {
        invitePollTimer += tpf;
        if (!invitePollPending.get() && invitePollTimer >= INVITE_POLL_INITIAL_DELAY_SECONDS) {
            invitePollTimer = 0f;
            beginInvitePoll();
        }
        List<InviteClient.Invite> invites = pendingInvites.get();
        if (!inviteBannerShown && invites != null && !invites.isEmpty()) {
            inviteBannerShown = true;
            rebuildInviteBanner((SimpleApplication) getApplication(), invites);
        }
    }

    /** Kicks off (off the render thread) a poll of this player's pending invites - see
     *  {@code aws/README.md} "Direct invites". Purely additive: a failure/timeout here is
     *  indistinguishable from "no invites" and never affects the menu itself. */
    private void beginInvitePoll() {
        invitePollPending.set(true);
        int myGeneration = invitePollGeneration.incrementAndGet();
        PlayerContext ctx = (PlayerContext) getApplication();
        String playerId = ctx.getProfile().getPlayerId();
        Thread thread = new Thread(() -> {
            List<InviteClient.Invite> invites = null;
            try {
                invites = ctx.getInviteService().getInvites(playerId);
            } catch (IOException e) {
                // offline, or the invite service is unreachable - just means no banner this poll.
            }
            if (invitePollGeneration.get() != myGeneration) {
                return; // superseded (menu re-entered) - discard
            }
            pendingInvites.set(invites);
            invitePollPending.set(false);
        }, "invite-poll");
        thread.setDaemon(true);
        thread.start();
    }

    /** Small non-blocking banner across the top of the screen listing who invited you, with an
     *  ACCEPT per invite (jumps straight into Multiplayer's JOINING flow via
     *  {@code PaddleShockApp.acceptInvite}) and a single DISMISS that clears all of them (calls
     *  {@code dismissInvites}). Never interrupts/blocks the menu underneath it. */
    private void rebuildInviteBanner(SimpleApplication simpleApp, List<InviteClient.Invite> invites) {
        inviteBannerRoot.detachAllChildren();
        float screenW = simpleApp.getCamera().getWidth();
        float screenH = simpleApp.getCamera().getHeight();

        Container banner = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        banner.setBackground(new QuadBackgroundComponent(Theme.PANEL));
        banner.setInsets(new Insets3f(12, 18, 12, 18));

        Container headerRow = banner.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        Label header = headerRow.addChild(new Label(invites.size() == 1
                ? I18n.t("menu.invite.header_one") : I18n.t("menu.invite.header_many", invites.size())));
        header.setFontSize(14);
        header.setColor(Theme.ORANGE);

        Button dismissAll = headerRow.addChild(new Button(I18n.t("menu.dismiss")));
        dismissAll.setInsets(new Insets3f(0, 0, 0, 16));
        dismissAll.setBackground(new QuadBackgroundComponent(Theme.PANEL_HOVER));
        dismissAll.setColor(Theme.TEXT_DIM);
        dismissAll.setFontSize(12);
        dismissAll.addClickCommands(source -> {
            ((PlayerContext) getApplication()).getAudioManager().playSfx("button_click.ogg");
            dismissInvites();
        });

        for (InviteClient.Invite invite : invites) {
            Container row = banner.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
            row.setInsets(new Insets3f(4, 0, 0, 0));

            Label text = row.addChild(new Label(I18n.t("menu.invite.text", invite.displayName())));
            text.setFontSize(13);
            text.setColor(Theme.TEXT);
            text.setTextHAlignment(HAlignment.Left);
            text.setPreferredSize(new Vector3f(300, text.getPreferredSize().y, 0));

            Button accept = row.addChild(new Button(I18n.t("menu.accept")));
            accept.setInsets(new Insets3f(0, 0, 0, 8));
            accept.setBackground(new QuadBackgroundComponent(Theme.GREEN_DIM));
            accept.setColor(Theme.GREEN);
            accept.setFontSize(12);
            String lobbyCode = invite.getLobbyCode();
            accept.addClickCommands(source -> {
                ((PlayerContext) getApplication()).getAudioManager().playSfx("button_click.ogg");
                dismissInvitesFireAndForget();
                ((Navigator) getApplication()).acceptInvite(lobbyCode);
            });
        }

        Vector3f bannerSize = banner.getPreferredSize();
        banner.setLocalTranslation((screenW - bannerSize.x) / 2f, screenH - 16, 2);
        inviteBannerRoot.attachChild(banner);
    }

    /** DISMISS button on the banner: clears the banner immediately (optimistic - no need to wait
     *  on the network for a purely cosmetic dismissal) and calls {@code dismissInvites}
     *  best-effort in the background. */
    private void dismissInvites() {
        inviteBannerRoot.detachAllChildren();
        pendingInvites.set(null);
        dismissInvitesFireAndForget();
    }

    private void dismissInvitesFireAndForget() {
        PlayerContext ctx = (PlayerContext) getApplication();
        String playerId = ctx.getProfile().getPlayerId();
        Thread thread = new Thread(() -> {
            try {
                ctx.getInviteService().dismissInvites(playerId);
            } catch (IOException e) {
                // best-effort - worst case the same invites reappear on a later poll.
            }
        }, "invite-dismiss");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    protected void cleanup(Application application) {
        // uiRoot/inviteBannerRoot are detached in onDisable(); nothing else owns native resources here.
    }

    @Override
    protected void onEnable() {
        rebuild((SimpleApplication) getApplication());
        ((SimpleApplication) getApplication()).getGuiNode().attachChild(uiRoot);
        ((SimpleApplication) getApplication()).getGuiNode().attachChild(inviteBannerRoot);
        getApplication().getInputManager().setCursorVisible(true);
        invitePollTimer = 0f;
        inviteBannerShown = false;
        pendingInvites.set(null);
    }

    @Override
    protected void onDisable() {
        uiRoot.removeFromParent();
        inviteBannerRoot.removeFromParent();
        inviteBannerRoot.detachAllChildren();
        // Supersede any in-flight background poll so a late result discards itself instead of
        // leaking into a future onEnable() - see the analogous pattern in ProfileState/LeaderboardState.
        invitePollGeneration.incrementAndGet();
    }
}

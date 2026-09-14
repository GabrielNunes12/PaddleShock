package com.paddleshock.ui;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Optional;
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
import com.simsilica.lemur.HAlignment;
import com.simsilica.lemur.Insets3f;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.TextField;
import com.simsilica.lemur.VAlignment;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.component.SpringGridLayout;

import com.paddleshock.app.PaddleShockApp;
import com.paddleshock.data.MatchHistoryEntry;
import com.paddleshock.net.RankClient;
import com.paddleshock.net.RankState;

/**
 * Player profile screen: display name (Steam persona if available, else a locally editable
 * name), current ranked standing, and recent local match history. Follows {@link LeaderboardState}'s
 * structure/style closely - a sibling screen, not a new visual language.
 *
 * <p>Rank is fetched the same async, generation-guarded way {@link LeaderboardState} fetches the
 * leaderboard: a background thread, polled once per frame in {@link #update}. {@code getRank}
 * always returns a fresh (Copper IV/0 LP) record for a player who's never played ranked, so the
 * only real failure case here is the fetch itself failing (offline/unreachable service) - both
 * that and a genuinely fresh record are shown the same way: "Not yet ranked."
 *
 * <p>Layout is a two-column card split (redesign mockup): a fixed-width identity/rank card on the
 * left, recent-match row cards filling the remaining width on the right. This is a pure
 * reskin/relayout of the previous single-column stack - every control still does exactly what it
 * did before, just arranged and styled differently.
 */
public class ProfileState extends BaseAppState {

    /** The profile itself keeps up to 50 entries (see {@code PlayerProfile}'s match history cap);
     *  only the most recent few are worth showing on one screen. */
    private static final int HISTORY_DISPLAY_LIMIT = 15;

    private static final float LEFT_CARD_WIDTH = 310f;
    private static final float RIGHT_CARD_WIDTH = 440f;

    private enum RankView { LOADING, LOADED, ERROR }

    private final Node uiRoot = new Node("profileUi");
    private RankView rankView = RankView.LOADING;

    // Background fetch generation, mirroring LeaderboardState's fetchGeneration: guards against a
    // stale result (from the screen being left/re-entered) overwriting a newer one.
    private final AtomicInteger fetchGeneration = new AtomicInteger(0);
    private final AtomicReference<RankState> fetchResult = new AtomicReference<>();
    private final AtomicBoolean fetchPending = new AtomicBoolean(false);
    private volatile boolean fetchFailed = false;

    // Set only when Steam is unavailable and the local-name TextField is actually on screen -
    // see buildIdentity()/commitNameEdit().
    private TextField nameField;

    @Override
    protected void initialize(Application application) {
        // Built fresh in rebuild() every time the screen is shown, or the fetch completes.
    }

    private void beginFetch(PaddleShockApp app) {
        rankView = RankView.LOADING;
        fetchResult.set(null);
        fetchFailed = false;
        fetchPending.set(true);
        int myGeneration = fetchGeneration.incrementAndGet();
        String playerId = app.getProfile().getPlayerId();

        Thread thread = new Thread(() -> {
            RankState rank = null;
            boolean failed = false;
            try {
                rank = RankClient.getRank(playerId);
            } catch (IOException e) {
                failed = true;
            }
            if (fetchGeneration.get() != myGeneration) {
                return; // superseded by a newer fetch (screen re-entered) - discard
            }
            fetchResult.set(rank);
            fetchFailed = failed;
            fetchPending.set(false);
        }, "profile-rank-fetch");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void update(float tpf) {
        if (rankView == RankView.LOADING && !fetchPending.get()) {
            rankView = fetchFailed ? RankView.ERROR : RankView.LOADED;
            rebuild();
        }
    }

    private void rebuild() {
        uiRoot.detachAllChildren();
        PaddleShockApp app = (PaddleShockApp) getApplication();
        SimpleApplication simpleApp = (SimpleApplication) app;
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

        Label title = panel.addChild(new Label("PROFILE"));
        title.setFontSize(26);
        title.setColor(Theme.ORANGE);
        title.setInsets(new Insets3f(0, 0, 16, 0));

        Container columns = panel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        columns.setInsets(new Insets3f(0, 0, 18, 0));

        Container leftCard = addCard(columns, 20);
        buildIdentity(app, leftCard);
        buildDivider(leftCard);
        buildRank(leftCard);
        fixCardWidth(leftCard, LEFT_CARD_WIDTH);

        Container rightCard = addCard(columns, 0);
        buildHistory(app, rightCard);
        fixCardWidth(rightCard, RIGHT_CARD_WIDTH);

        Button back = panel.addChild(new Button("BACK"));
        styleButton(back, Theme.PANEL_HOVER, Theme.TEXT, 14);
        back.setInsets(new Insets3f(18, 0, 0, 0));
        back.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            commitNameEdit(app);
            app.showMainMenu();
        });

        Vector3f panelSize = panel.getPreferredSize();
        panel.setLocalTranslation((screenW - panelSize.x) / 2f, (screenH + panelSize.y) / 2f, 1);
        uiRoot.attachChild(panel);
    }

    /** Builds one bordered {@code Theme.PANEL} card (a {@code Theme.PANEL_LINE} hairline border
     *  around a padded content area) as a child of {@code parent}, and returns the inner content
     *  container callers should add their own children to. Call {@link #fixCardWidth} once all of
     *  a card's content has been added, to pin its width without clipping. */
    private Container addCard(Container parent, float marginRight) {
        Container wrapper = parent.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        wrapper.setInsets(new Insets3f(0, 0, 0, marginRight));

        Container border = wrapper.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        border.setBackground(new QuadBackgroundComponent(Theme.PANEL_LINE));
        border.setInsets(new Insets3f(2, 2, 2, 2));

        Container inner = border.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        inner.setBackground(new QuadBackgroundComponent(Theme.PANEL));
        inner.setInsets(new Insets3f(16, 18, 16, 18));
        return inner;
    }

    /** Pins a card's width to {@code width} while preserving the height its actual content
     *  already computed - must be called only AFTER all of the card's children are added; fixing
     *  the width before that would freeze the container at its (near-empty) preferred size and
     *  crash the layout once real content no longer fits it (SpringGridLayout computing a
     *  negative remaining size). Mirrors the fixed-column-width trick already used for match
     *  history row labels elsewhere in this class. */
    private void fixCardWidth(Container card, float width) {
        Vector3f current = card.getPreferredSize();
        card.setPreferredSize(new Vector3f(width, current.y, 0));
    }

    private void buildDivider(Container card) {
        Container divider = card.addChild(new Container());
        divider.setBackground(new QuadBackgroundComponent(Theme.PANEL_LINE));
        divider.setPreferredSize(new Vector3f(LEFT_CARD_WIDTH - 36, 2, 0));
        divider.setInsets(new Insets3f(12, 0, 14, 0));
    }

    /** Steam's persona name when available; otherwise the local, editable display name - only the
     *  local name is ever editable here, since overriding a real Steam identity makes no sense.
     *  Rendered as an avatar chip (name-initial letter) next to the name/edit controls. */
    private void buildIdentity(PaddleShockApp app, Container card) {
        nameField = null;
        Optional<String> steamName = app.getSteamManager().getPersonaName();
        String displayName = steamName.orElseGet(() -> app.getProfile().getDisplayName());

        Container identityRow = card.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));

        Label avatar = identityRow.addChild(new Label(initialFor(displayName)));
        avatar.setBackground(new QuadBackgroundComponent(Theme.ORANGE));
        avatar.setColor(Theme.ON_ACCENT);
        avatar.setFontSize(22);
        avatar.setTextHAlignment(HAlignment.Center);
        avatar.setTextVAlignment(VAlignment.Center);
        avatar.setPreferredSize(new Vector3f(48, 48, 0));
        avatar.setInsets(new Insets3f(0, 0, 0, 14));

        Container nameBlock = identityRow.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));

        if (steamName.isPresent()) {
            Label nameLabel = nameBlock.addChild(new Label(steamName.get()));
            nameLabel.setFontSize(18);
            nameLabel.setColor(Theme.TEXT);
            nameLabel.setInsets(new Insets3f(0, 0, 2, 0));

            Label steamHint = nameBlock.addChild(new Label("Steam display name"));
            steamHint.setFontSize(11);
            steamHint.setColor(Theme.TEXT_DIM);
            return;
        }

        Label hint = nameBlock.addChild(new Label("Display name (Steam not available - edit below):"));
        hint.setFontSize(11);
        hint.setColor(Theme.TEXT_DIM);
        hint.setInsets(new Insets3f(0, 0, 4, 0));

        Container nameRow = nameBlock.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));

        nameField = nameRow.addChild(new TextField(app.getProfile().getDisplayName()));
        nameField.setFontSize(15);
        nameField.setColor(Theme.TEXT);
        nameField.setBackground(new QuadBackgroundComponent(Theme.PANEL_HOVER));
        nameField.setPreferredWidth(150);
        nameField.setInsets(new Insets3f(6, 8, 6, 8));

        Button save = nameRow.addChild(new Button("SAVE"));
        save.setInsets(new Insets3f(0, 10, 0, 0));
        save.setBackground(new QuadBackgroundComponent(Theme.BLUE));
        save.setColor(Theme.ON_ACCENT);
        save.setFontSize(13);
        save.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            commitNameEdit(app);
            rebuild();
        });
    }

    /** Uppercase first character of the name to show on the avatar chip; "?" for a blank name. */
    private String initialFor(String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }
        return name.strip().substring(0, 1).toUpperCase();
    }

    private void commitNameEdit(PaddleShockApp app) {
        if (nameField == null) {
            return;
        }
        app.getProfile().setDisplayName(nameField.getText());
        app.saveProfile();
    }

    private void buildRank(Container card) {
        Label rankTitle = card.addChild(new Label("RANK"));
        rankTitle.setFontSize(12);
        rankTitle.setColor(Theme.TEXT_DIM);
        rankTitle.setInsets(new Insets3f(0, 0, 6, 0));

        if (rankView == RankView.LOADING) {
            Label loading = card.addChild(new Label("Loading..."));
            loading.setFontSize(15);
            loading.setColor(Theme.TEXT_DIM);
            return;
        }

        RankState rank = rankView == RankView.LOADED ? fetchResult.get() : null;
        if (rank == null) {
            Label unranked = card.addChild(new Label("Not yet ranked."));
            unranked.setFontSize(15);
            unranked.setColor(Theme.TEXT_DIM);
            return;
        }

        Label tierLabel = card.addChild(new Label(rank.formatLabel()));
        tierLabel.setFontSize(24);
        tierLabel.setColor(rank.getTier().getColor());
        tierLabel.setInsets(new Insets3f(0, 0, 4, 0));

        Label detailLabel = card.addChild(new Label(
                rank.getLp() + " LP  -  " + rank.getWins() + "W-" + rank.getLosses() + "L"));
        detailLabel.setFontSize(13);
        detailLabel.setColor(Theme.TEXT_DIM);
    }

    private void buildHistory(PaddleShockApp app, Container card) {
        Label historyTitle = card.addChild(new Label("RECENT MATCHES"));
        historyTitle.setFontSize(12);
        historyTitle.setColor(Theme.TEXT_DIM);
        historyTitle.setInsets(new Insets3f(0, 0, 10, 0));

        List<MatchHistoryEntry> history = app.getProfile().getMatchHistory();
        if (history.isEmpty()) {
            Label empty = card.addChild(new Label("No matches played yet."));
            empty.setFontSize(14);
            empty.setColor(Theme.TEXT_DIM);
            empty.setTextHAlignment(HAlignment.Center);
            empty.setPreferredSize(new Vector3f(RIGHT_CARD_WIDTH - 36, 60, 0));
            return;
        }

        Container rows = card.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));

        SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, HH:mm");
        int shown = Math.min(history.size(), HISTORY_DISPLAY_LIMIT);
        for (int i = 0; i < shown; i++) {
            MatchHistoryEntry entry = history.get(i);
            addHistoryRow(rows, dateFormat, entry);
        }
    }

    /** One row card for a single match: a win/loss icon chip, date/mode, score, a WIN/LOSS pill,
     *  and the LP change (blank for a non-ranked match) - restrained colors throughout, matching
     *  the existing muted palette rather than an alarming red for a loss. */
    private void addHistoryRow(Container rows, SimpleDateFormat dateFormat, MatchHistoryEntry entry) {
        boolean won = entry.isWon();
        ColorRGBA chipBg = won ? Theme.GREEN_DIM : Theme.PANEL_HOVER;

        Container row = rows.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        row.setInsets(new Insets3f(3, 0, 3, 0));

        Label icon = row.addChild(new Label(""));
        icon.setBackground(new QuadBackgroundComponent(chipBg));
        icon.setPreferredSize(new Vector3f(26, 26, 0));
        icon.setInsets(new Insets3f(0, 0, 0, 10));

        Label dateLabel = row.addChild(new Label(dateFormat.format(new Date(entry.getTimestamp()))));
        dateLabel.setFontSize(11);
        dateLabel.setColor(Theme.TEXT_DIM);
        dateLabel.setPreferredSize(new Vector3f(88, dateLabel.getPreferredSize().y, 0));

        Label modeLabel = row.addChild(new Label(entry.getMode()));
        modeLabel.setFontSize(13);
        modeLabel.setColor(Theme.TEXT);
        modeLabel.setPreferredSize(new Vector3f(100, modeLabel.getPreferredSize().y, 0));

        Label scoreLabel = row.addChild(new Label(entry.getPlayerScore() + "-" + entry.getOpponentScore()));
        scoreLabel.setFontSize(13);
        scoreLabel.setColor(Theme.TEXT_DIM);
        scoreLabel.setPreferredSize(new Vector3f(48, scoreLabel.getPreferredSize().y, 0));

        Label pill = row.addChild(new Label(won ? "WIN" : "LOSS"));
        pill.setFontSize(11);
        pill.setColor(won ? Theme.GREEN : Theme.TEXT_DIM);
        pill.setBackground(new QuadBackgroundComponent(chipBg));
        pill.setTextHAlignment(HAlignment.Center);
        pill.setInsets(new Insets3f(4, 6, 4, 6));
        pill.setPreferredSize(new Vector3f(56, pill.getPreferredSize().y, 0));

        String lpText = "";
        ColorRGBA lpColor = Theme.TEXT_DIM;
        if (entry.getLpChange() != 0) {
            int lpChange = entry.getLpChange();
            lpText = lpChange > 0 ? "+" + lpChange + " LP" : "-" + Math.abs(lpChange) + " LP";
            lpColor = lpChange > 0 ? Theme.GREEN : Theme.ORANGE;
        }
        Label lpLabel = row.addChild(new Label(lpText));
        lpLabel.setFontSize(12);
        lpLabel.setColor(lpColor);
        lpLabel.setTextHAlignment(HAlignment.Right);
        lpLabel.setInsets(new Insets3f(0, 10, 0, 0));
        lpLabel.setPreferredSize(new Vector3f(56, lpLabel.getPreferredSize().y, 0));
    }

    private void styleButton(Button button, com.jme3.math.ColorRGBA bg, com.jme3.math.ColorRGBA fg, int fontSize) {
        button.setInsets(new Insets3f(6, 0, 6, 0));
        button.setBackground(new QuadBackgroundComponent(bg));
        button.setColor(fg);
        button.setFontSize(fontSize);
        button.setPreferredSize(new Vector3f(340, 46, 0));
    }

    @Override
    protected void cleanup(Application application) {
        // uiRoot is detached in onDisable(); nothing else owns native resources here.
    }

    @Override
    protected void onEnable() {
        PaddleShockApp app = (PaddleShockApp) getApplication();
        beginFetch(app);
        rebuild();
        ((SimpleApplication) getApplication()).getGuiNode().attachChild(uiRoot);
        getApplication().getInputManager().setCursorVisible(true);
    }

    @Override
    protected void onDisable() {
        commitNameEdit((PaddleShockApp) getApplication());
        uiRoot.removeFromParent();
        // Supersede any in-flight background fetch so a late result discards itself instead of
        // leaking into a future onEnable() - see LeaderboardState's identical pattern.
        fetchGeneration.incrementAndGet();
    }
}

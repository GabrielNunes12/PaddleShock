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
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.Insets3f;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.TextField;
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
 */
public class ProfileState extends BaseAppState {

    /** The profile itself keeps up to 50 entries (see {@code PlayerProfile}'s match history cap);
     *  only the most recent few are worth showing on one screen. */
    private static final int HISTORY_DISPLAY_LIMIT = 15;

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

        buildIdentity(app, panel);
        buildRank(panel);
        buildHistory(app, panel);

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

    /** Steam's persona name when available; otherwise the local, editable display name - only the
     *  local name is ever editable here, since overriding a real Steam identity makes no sense. */
    private void buildIdentity(PaddleShockApp app, Container panel) {
        nameField = null;
        Optional<String> steamName = app.getSteamManager().getPersonaName();
        if (steamName.isPresent()) {
            Label nameLabel = panel.addChild(new Label(steamName.get()));
            nameLabel.setFontSize(20);
            nameLabel.setColor(Theme.TEXT);
            nameLabel.setInsets(new Insets3f(0, 0, 2, 0));

            Label steamHint = panel.addChild(new Label("Steam display name"));
            steamHint.setFontSize(11);
            steamHint.setColor(Theme.TEXT_DIM);
            steamHint.setInsets(new Insets3f(0, 0, 14, 0));
            return;
        }

        Label hint = panel.addChild(new Label("Display name (Steam not available - edit below):"));
        hint.setFontSize(11);
        hint.setColor(Theme.TEXT_DIM);
        hint.setInsets(new Insets3f(0, 0, 4, 0));

        Container nameRow = panel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        nameRow.setInsets(new Insets3f(0, 0, 14, 0));

        nameField = nameRow.addChild(new TextField(app.getProfile().getDisplayName()));
        nameField.setFontSize(16);
        nameField.setColor(Theme.TEXT);
        nameField.setBackground(new QuadBackgroundComponent(Theme.PANEL_HOVER));
        nameField.setPreferredWidth(240);
        nameField.setInsets(new Insets3f(6, 8, 6, 8));

        Button save = nameRow.addChild(new Button("SAVE"));
        save.setInsets(new Insets3f(0, 12, 0, 0));
        save.setBackground(new QuadBackgroundComponent(Theme.BLUE));
        save.setColor(Theme.ON_ACCENT);
        save.setFontSize(13);
        save.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            commitNameEdit(app);
            rebuild();
        });
    }

    private void commitNameEdit(PaddleShockApp app) {
        if (nameField == null) {
            return;
        }
        app.getProfile().setDisplayName(nameField.getText());
        app.saveProfile();
    }

    private void buildRank(Container panel) {
        Label rankTitle = panel.addChild(new Label("RANK"));
        rankTitle.setFontSize(14);
        rankTitle.setColor(Theme.TEXT_DIM);
        rankTitle.setInsets(new Insets3f(4, 0, 4, 0));

        if (rankView == RankView.LOADING) {
            Label loading = panel.addChild(new Label("Loading..."));
            loading.setFontSize(15);
            loading.setColor(Theme.TEXT_DIM);
            loading.setInsets(new Insets3f(0, 0, 14, 0));
            return;
        }

        RankState rank = rankView == RankView.LOADED ? fetchResult.get() : null;
        if (rank == null) {
            Label unranked = panel.addChild(new Label("Not yet ranked."));
            unranked.setFontSize(15);
            unranked.setColor(Theme.TEXT_DIM);
            unranked.setInsets(new Insets3f(0, 0, 14, 0));
            return;
        }

        Label rankLabel = panel.addChild(new Label(
                rank.formatLabel() + " - " + rank.getLp() + " LP (" + rank.getWins() + "W-" + rank.getLosses() + "L)"));
        rankLabel.setFontSize(16);
        rankLabel.setColor(rank.getTier().getColor());
        rankLabel.setInsets(new Insets3f(0, 0, 14, 0));
    }

    private void buildHistory(PaddleShockApp app, Container panel) {
        Label historyTitle = panel.addChild(new Label("RECENT MATCHES"));
        historyTitle.setFontSize(14);
        historyTitle.setColor(Theme.TEXT_DIM);
        historyTitle.setInsets(new Insets3f(4, 0, 4, 0));

        List<MatchHistoryEntry> history = app.getProfile().getMatchHistory();
        if (history.isEmpty()) {
            Label empty = panel.addChild(new Label("No matches played yet."));
            empty.setFontSize(14);
            empty.setColor(Theme.TEXT_DIM);
            empty.setInsets(new Insets3f(0, 0, 10, 0));
            return;
        }

        Container rows = panel.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        rows.setInsets(new Insets3f(0, 0, 10, 0));

        SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, HH:mm");
        int shown = Math.min(history.size(), HISTORY_DISPLAY_LIMIT);
        for (int i = 0; i < shown; i++) {
            MatchHistoryEntry entry = history.get(i);
            Container row = rows.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
            row.setInsets(new Insets3f(2, 0, 2, 0));

            Label dateLabel = row.addChild(new Label(dateFormat.format(new Date(entry.getTimestamp()))));
            dateLabel.setFontSize(12);
            dateLabel.setColor(Theme.TEXT_DIM);
            dateLabel.setPreferredSize(new Vector3f(120, dateLabel.getPreferredSize().y, 0));

            Label modeLabel = row.addChild(new Label(entry.getMode()));
            modeLabel.setFontSize(12);
            modeLabel.setColor(Theme.TEXT_DIM);
            modeLabel.setPreferredSize(new Vector3f(90, modeLabel.getPreferredSize().y, 0));

            Label resultLabel = row.addChild(new Label(entry.formatResult()));
            resultLabel.setFontSize(13);
            resultLabel.setColor(entry.isWon() ? Theme.GREEN : Theme.ORANGE);
        }
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

package com.paddleshock.ui;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.component.SpringGridLayout;

import com.paddleshock.app.Navigator;
import com.paddleshock.app.PlayerContext;
import com.paddleshock.i18n.I18n;
import com.paddleshock.net.RankClient;
import com.paddleshock.net.RankClient.LeaderboardEntry;

/**
 * Ranked ladder standings screen: the top {@link #LIMIT} players, best-to-worst, fetched from the
 * same Lambda backend as {@link RankClient#getRank}/{@code reportMatchResult}. A single static
 * list with no pagination/scrolling - fine for a v1 top-N leaderboard (see the class docs on
 * {@code RankClient.LeaderboardEntry} for why entries show a shortened player id rather than a
 * real display name - there is no username system anywhere in this codebase yet).
 *
 * <p>Follows {@link MultiplayerState}'s async pattern: the blocking HTTPS call runs on a
 * background thread, and {@link #update} polls for its result once so the screen never freezes
 * the render thread waiting on the network - including the case where the backend action isn't
 * deployed yet (or the request times out), which just surfaces as the same "couldn't load"
 * error/RETRY state as any other network failure.
 */
public class LeaderboardState extends BaseAppState {

    private static final int LIMIT = 20;

    private enum View { LOADING, LOADED, ERROR }

    private final Node uiRoot = new Node("leaderboardUi");
    private View view = View.LOADING;

    // Background fetch generation, mirroring MultiplayerState's lobbyGeneration: guards against a
    // stale result (from a superseded retry, or the screen being left) overwriting a newer one.
    private final java.util.concurrent.atomic.AtomicInteger fetchGeneration = new java.util.concurrent.atomic.AtomicInteger(0);
    private final AtomicReference<List<LeaderboardEntry>> fetchResult = new AtomicReference<>();
    private final AtomicReference<String> fetchError = new AtomicReference<>();
    private final AtomicBoolean fetchPending = new AtomicBoolean(false);

    @Override
    protected void initialize(Application application) {
        // Built fresh in rebuild() every time the screen is shown, or the fetch completes.
    }

    private void beginFetch() {
        view = View.LOADING;
        fetchResult.set(null);
        fetchError.set(null);
        fetchPending.set(true);
        int myGeneration = fetchGeneration.incrementAndGet();

        PlayerContext app = (PlayerContext) getApplication();
        Thread thread = new Thread(() -> {
            List<LeaderboardEntry> entries = null;
            String error = null;
            try {
                entries = app.getRankService().getLeaderboard(LIMIT);
            } catch (IOException e) {
                error = e.getMessage() == null ? I18n.t("leaderboard.error_unreachable") : e.getMessage();
            }
            if (fetchGeneration.get() != myGeneration) {
                return; // superseded by a newer fetch (a retry, or the screen was left) - discard
            }
            fetchResult.set(entries);
            fetchError.set(error);
            fetchPending.set(false);
        }, "leaderboard-fetch");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void update(float tpf) {
        if (view == View.LOADING && !fetchPending.get()) {
            List<LeaderboardEntry> entries = fetchResult.get();
            view = entries != null ? View.LOADED : View.ERROR;
            rebuild();
        }
    }

    private void rebuild() {
        uiRoot.detachAllChildren();
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

        Label title = panel.addChild(new Label(I18n.t("leaderboard.title")));
        title.setFontSize(26);
        title.setColor(Theme.ORANGE);
        title.setInsets(new Insets3f(0, 0, 4, 0));

        Label sub = panel.addChild(new Label(I18n.t("leaderboard.subtitle", LIMIT)));
        sub.setFontSize(12);
        sub.setColor(Theme.TEXT_DIM);
        sub.setInsets(new Insets3f(0, 0, 16, 0));

        switch (view) {
            case LOADING -> buildLoading(panel);
            case LOADED -> buildLoaded(panel);
            case ERROR -> buildError(panel);
        }

        Button back = panel.addChild(new Button(I18n.t("leaderboard.back")));
        styleButton(back, Theme.PANEL_HOVER, Theme.TEXT, 14);
        back.setInsets(new Insets3f(18, 0, 0, 0));
        back.addClickCommands(source -> {
            ((PlayerContext) getApplication()).getAudioManager().playSfx("button_click.ogg");
            ((Navigator) getApplication()).showMainMenu();
        });

        Vector3f panelSize = panel.getPreferredSize();
        panel.setLocalTranslation((screenW - panelSize.x) / 2f, (screenH + panelSize.y) / 2f, 1);
        uiRoot.attachChild(panel);
    }

    private void buildLoading(Container panel) {
        Label loading = panel.addChild(new Label(I18n.t("leaderboard.loading")));
        loading.setFontSize(16);
        loading.setColor(Theme.TEXT);
        loading.setInsets(new Insets3f(20, 0, 20, 0));
    }

    private void buildError(Container panel) {
        String reason = fetchError.get();
        Label errorLabel = panel.addChild(new Label(I18n.t("leaderboard.error")));
        errorLabel.setFontSize(16);
        errorLabel.setColor(Theme.ORANGE);
        errorLabel.setInsets(new Insets3f(10, 0, 4, 0));

        Label detail = panel.addChild(new Label(
                I18n.t("leaderboard.error_detail", reason == null ? I18n.t("leaderboard.unknown_error") : reason)));
        detail.setFontSize(11);
        detail.setColor(Theme.TEXT_DIM);
        detail.setInsets(new Insets3f(0, 0, 16, 0));

        Button retry = panel.addChild(new Button(I18n.t("leaderboard.retry")));
        styleButton(retry, Theme.BLUE, Theme.ON_ACCENT, 15);
        retry.addClickCommands(source -> {
            ((PlayerContext) getApplication()).getAudioManager().playSfx("button_click.ogg");
            beginFetch();
            rebuild();
        });
    }

    private void buildLoaded(Container panel) {
        List<LeaderboardEntry> entries = fetchResult.get();
        if (entries == null || entries.isEmpty()) {
            Label empty = panel.addChild(new Label(I18n.t("leaderboard.empty")));
            empty.setFontSize(14);
            empty.setColor(Theme.TEXT_DIM);
            empty.setInsets(new Insets3f(10, 0, 16, 0));
            return;
        }

        Container rows = panel.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        rows.setInsets(new Insets3f(0, 0, 10, 0));
        for (int i = 0; i < entries.size(); i++) {
            LeaderboardEntry entry = entries.get(i);
            Container row = rows.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
            row.setInsets(new Insets3f(2, 0, 2, 0));

            Label rank = row.addChild(new Label(String.format("#%-3d", i + 1)));
            rank.setFontSize(14);
            rank.setColor(Theme.TEXT_DIM);
            rank.setPreferredSize(new Vector3f(46, rank.getPreferredSize().y, 0));

            Label entryLabel = row.addChild(new Label(entry.formatLabel()));
            entryLabel.setFontSize(14);
            entryLabel.setColor(entry.getTier().getColor());
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
        beginFetch();
        rebuild();
        ((SimpleApplication) getApplication()).getGuiNode().attachChild(uiRoot);
        getApplication().getInputManager().setCursorVisible(true);
    }

    @Override
    protected void onDisable() {
        uiRoot.removeFromParent();
        // Supersede any in-flight background fetch so a late result (from leaving the screen
        // mid-request) discards itself instead of leaking into a future onEnable().
        fetchGeneration.incrementAndGet();
    }
}

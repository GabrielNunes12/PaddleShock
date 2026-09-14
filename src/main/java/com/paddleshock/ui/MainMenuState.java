package com.paddleshock.ui;

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
import com.simsilica.lemur.Insets3f;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.component.SpringGridLayout;

import com.paddleshock.app.PaddleShockApp;

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

    private final Node uiRoot = new Node("mainMenuUi");

    @Override
    protected void initialize(Application application) {
        // Built fresh in rebuild() every time the screen is shown.
    }

    private void rebuild(PaddleShockApp app) {
        uiRoot.detachAllChildren();

        SimpleApplication simpleApp = (SimpleApplication) app;
        float screenW = simpleApp.getCamera().getWidth();
        float screenH = simpleApp.getCamera().getHeight();

        float leftWidth = screenW * 0.38f;
        float rightWidth = screenW - leftWidth;

        buildLeftHero(app, leftWidth, screenH);
        buildRightNav(app, leftWidth, rightWidth, screenH);
    }

    /** Left hero panel: wordmark, tagline, and the big PLAY VS AI CTA - everything a brand-new
     *  player needs to get into a match with zero extra clicks. */
    private void buildLeftHero(PaddleShockApp app, float leftWidth, float screenH) {
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

        Label paddleLabel = new Label("PADDLE");
        paddleLabel.setFontSize(48);
        paddleLabel.setColor(Theme.TEXT);
        uiRoot.attachChild(paddleLabel);

        Label shockLabel = new Label("SHOCK");
        shockLabel.setFontSize(48);
        shockLabel.setColor(Theme.ORANGE);
        uiRoot.attachChild(shockLabel);

        float logoWidth = paddleLabel.getPreferredSize().x + shockLabel.getPreferredSize().x;
        float logoY = screenH * 0.78f;
        float logoX = (leftWidth - logoWidth) / 2f;
        paddleLabel.setLocalTranslation(logoX, logoY, 2);
        shockLabel.setLocalTranslation(logoX + paddleLabel.getPreferredSize().x, logoY, 2);

        Label tagline = new Label("Arcade ping-pong with ranked online play.");
        tagline.setFontSize(13);
        tagline.setColor(Theme.TEXT_DIM);
        uiRoot.attachChild(tagline);
        float taglineY = logoY - paddleLabel.getPreferredSize().y - 14;
        tagline.setLocalTranslation((leftWidth - tagline.getPreferredSize().x) / 2f, taglineY, 2);

        float ctaWidth = leftWidth - 64;
        float ctaHeight = 54;
        Button playVsAi = new Button("PLAY VS AI");
        playVsAi.setBackground(new QuadBackgroundComponent(Theme.ORANGE));
        playVsAi.setColor(Theme.ON_ACCENT);
        playVsAi.setFontSize(20);
        playVsAi.setPreferredSize(new Vector3f(ctaWidth, ctaHeight, 0));
        playVsAi.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            app.showLoadout();
        });
        uiRoot.attachChild(playVsAi);
        playVsAi.setLocalTranslation(32, taglineY - 40, 2);
    }

    /** Right panel: the other six destinations as bordered nav-card rows. */
    private void buildRightNav(PaddleShockApp app, float leftWidth, float rightWidth, float screenH) {
        Container rightPanel = new Container();
        rightPanel.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));
        rightPanel.setPreferredSize(new Vector3f(rightWidth, screenH, 0));
        rightPanel.setLocalTranslation(leftWidth, screenH, 0);
        uiRoot.attachChild(rightPanel);

        float cardWidth = rightWidth - 96;

        Container nav = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        nav.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));
        addNavCard(nav, "MULTIPLAYER", Theme.BLUE_DIM, cardWidth, app::showMultiplayer);
        addNavCard(nav, "LEADERBOARD", Theme.ORANGE_DIM, cardWidth, app::showLeaderboard);
        addNavCard(nav, "PROFILE", Theme.GREEN_DIM, cardWidth, app::showProfile);
        addNavCard(nav, "STORE", Theme.PANEL_HOVER, cardWidth, app::showStore);
        addNavCard(nav, "SETTINGS", Theme.PANEL_HOVER, cardWidth, () -> app.showOptions(app::showMainMenu));
        addNavCard(nav, "HOW TO PLAY", Theme.PANEL_HOVER, cardWidth, () -> app.showHowToPlay(app::showMainMenu));
        addNavCard(nav, "QUIT", Theme.PANEL_HOVER, cardWidth, app::stop);

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
            ((PaddleShockApp) getApplication()).getAudioManager().playSfx("button_click.ogg");
            action.run();
        });

        card.setPreferredSize(new Vector3f(cardWidth - 2, card.getPreferredSize().y, 0));
    }

    @Override
    public void update(float tpf) {
        // No per-frame work needed on the main menu itself.
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

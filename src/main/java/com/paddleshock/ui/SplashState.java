package com.paddleshock.ui;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.input.InputManager;
import com.jme3.input.MouseInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.MouseButtonTrigger;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.Container;

import com.paddleshock.app.PaddleShockApp;
import com.paddleshock.i18n.I18n;

/** Studio splash: shows the MentorHub Gaming mark, then advances to the main menu. */
public class SplashState extends BaseAppState implements ActionListener {

    private static final String ACTION_SKIP = "PS_SkipSplash";
    private static final float DISPLAY_SECONDS = 2.5f;

    private final Node uiRoot = new Node("splashUi");
    private float remaining;
    private boolean advancing;
    private boolean firstFrameSeen;

    @Override
    protected void initialize(Application application) {
        // Built fresh in rebuild() every time the screen is shown.
    }

    private void rebuild(PaddleShockApp app) {
        uiRoot.detachAllChildren();

        SimpleApplication simpleApp = (SimpleApplication) app;
        float screenW = simpleApp.getCamera().getWidth();
        float screenH = simpleApp.getCamera().getHeight();

        Container background = new Container();
        background.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));
        background.setPreferredSize(new Vector3f(screenW, screenH, 0));
        background.setLocalTranslation(0, screenH, 0);
        uiRoot.attachChild(background);

        Label presents = new Label(I18n.t("splash.presents"));
        presents.setFontSize(14);
        presents.setColor(Theme.TEXT_DIM);
        Vector3f presentsSize = presents.getPreferredSize();
        presents.setLocalTranslation((screenW - presentsSize.x) / 2f, screenH * 0.68f, 1);
        uiRoot.attachChild(presents);

        float markSize = 108f;
        float markTopY = screenH * 0.62f;
        Container mark = new Container();
        mark.setBackground(new QuadBackgroundComponent(
                simpleApp.getAssetManager().loadTexture("Textures/UI/mentorhub_mark.png")));
        mark.setPreferredSize(new Vector3f(markSize, markSize, 0));
        mark.setLocalTranslation(screenW / 2f - markSize / 2f, markTopY, 1);
        uiRoot.attachChild(mark);

        Label mentorLabel = new Label(I18n.t("splash.wordmark.mentorhub"));
        mentorLabel.setFontSize(30);
        mentorLabel.setColor(Theme.TEXT);
        Label gamingLabel = new Label(" " + I18n.t("splash.wordmark.gaming"));
        gamingLabel.setFontSize(30);
        gamingLabel.setColor(Theme.ORANGE);

        float wordmarkWidth = mentorLabel.getPreferredSize().x + gamingLabel.getPreferredSize().x;
        float wordmarkY = markTopY - markSize - 28f;
        mentorLabel.setLocalTranslation((screenW - wordmarkWidth) / 2f, wordmarkY, 1);
        gamingLabel.setLocalTranslation((screenW - wordmarkWidth) / 2f + mentorLabel.getPreferredSize().x, wordmarkY, 1);
        uiRoot.attachChild(mentorLabel);
        uiRoot.attachChild(gamingLabel);

        Label tagBuild = new Label(I18n.t("splash.tag.build"));
        tagBuild.setFontSize(14);
        tagBuild.setColor(new com.jme3.math.ColorRGBA(0.545f, 0.902f, 0.682f, 1f));
        Label tagAnd = new Label(" " + I18n.t("splash.tag.and") + " ");
        tagAnd.setFontSize(14);
        tagAnd.setColor(Theme.TEXT_DIM);
        Label tagDeliver = new Label(I18n.t("splash.tag.deliver"));
        tagDeliver.setFontSize(14);
        tagDeliver.setColor(Theme.ORANGE);

        float tagWidth = tagBuild.getPreferredSize().x + tagAnd.getPreferredSize().x + tagDeliver.getPreferredSize().x;
        float tagY = screenH * 0.18f;
        float tagX = (screenW - tagWidth) / 2f;
        tagBuild.setLocalTranslation(tagX, tagY, 1);
        tagAnd.setLocalTranslation(tagX + tagBuild.getPreferredSize().x, tagY, 1);
        tagDeliver.setLocalTranslation(tagX + tagBuild.getPreferredSize().x + tagAnd.getPreferredSize().x, tagY, 1);
        uiRoot.attachChild(tagBuild);
        uiRoot.attachChild(tagAnd);
        uiRoot.attachChild(tagDeliver);
    }

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        if (ACTION_SKIP.equals(name) && isPressed) {
            advanceNow();
        }
    }

    @Override
    public void update(float tpf) {
        // jME's very first frame reports a huge tpf covering engine/asset startup time,
        // which would otherwise eat most of the splash's intended on-screen duration.
        if (!firstFrameSeen) {
            firstFrameSeen = true;
            return;
        }
        remaining -= tpf;
        if (remaining <= 0f) {
            advanceNow();
        }
    }

    private void advanceNow() {
        if (advancing) {
            return;
        }
        advancing = true;
        PaddleShockApp app = (PaddleShockApp) getApplication();
        if (app.getProfile().hasSeenTutorial()) {
            app.showMainMenu();
        } else {
            // First launch (or a save that predates the tutorial flag) - show it once before the
            // main menu instead of dropping the player straight into a 5-button menu with zero
            // explanation of controls/power-ups.
            app.showHowToPlay(app::showMainMenu);
        }
    }

    @Override
    protected void cleanup(Application application) {
        // uiRoot is detached in onDisable(); nothing else owns native resources here.
    }

    @Override
    protected void onEnable() {
        PaddleShockApp app = (PaddleShockApp) getApplication();
        remaining = DISPLAY_SECONDS;
        advancing = false;
        firstFrameSeen = false;
        rebuild(app);
        ((SimpleApplication) app).getGuiNode().attachChild(uiRoot);

        InputManager inputManager = app.getInputManager();
        inputManager.setCursorVisible(true);
        inputManager.addMapping(ACTION_SKIP, new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
        inputManager.addListener(this, ACTION_SKIP);
    }

    @Override
    protected void onDisable() {
        uiRoot.removeFromParent();
        InputManager inputManager = getApplication().getInputManager();
        inputManager.deleteMapping(ACTION_SKIP);
        inputManager.removeListener(this);
    }
}

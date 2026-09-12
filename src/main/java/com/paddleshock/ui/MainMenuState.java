package com.paddleshock.ui;

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

import com.paddleshock.app.PaddleShockApp;

public class MainMenuState extends BaseAppState {

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

        Container background = new Container();
        background.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));
        background.setPreferredSize(new Vector3f(screenW, screenH, 0));
        background.setLocalTranslation(0, screenH, 0);
        uiRoot.attachChild(background);

        Label paddleLabel = new Label("PADDLE");
        paddleLabel.setFontSize(48);
        paddleLabel.setColor(Theme.TEXT);
        uiRoot.attachChild(paddleLabel);

        Label shockLabel = new Label("SHOCK");
        shockLabel.setFontSize(48);
        shockLabel.setColor(Theme.ORANGE);
        uiRoot.attachChild(shockLabel);

        float logoWidth = paddleLabel.getPreferredSize().x + shockLabel.getPreferredSize().x;
        float logoY = screenH * 0.68f;
        paddleLabel.setLocalTranslation((screenW - logoWidth) / 2f, logoY, 1);
        shockLabel.setLocalTranslation((screenW - logoWidth) / 2f + paddleLabel.getPreferredSize().x, logoY, 1);

        Container menu = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        addMenuButton(menu, "PLAY VS AI", Theme.ORANGE, Theme.ON_ACCENT, app::startMatchVsAI);
        addMenuButton(menu, "STORE", Theme.PANEL_HOVER, Theme.TEXT, app::showStore);
        addMenuButton(menu, "OPTIONS", Theme.PANEL_HOVER, Theme.TEXT, () -> app.showOptions(app::showMainMenu));
        addMenuButton(menu, "QUIT", Theme.PANEL_HOVER, Theme.TEXT, app::stop);

        Vector3f menuSize = menu.getPreferredSize();
        menu.setLocalTranslation((screenW - menuSize.x) / 2f, screenH * 0.5f, 1);
        uiRoot.attachChild(menu);
    }

    private void addMenuButton(Container menu, String label, com.jme3.math.ColorRGBA bg,
            com.jme3.math.ColorRGBA fg, Runnable action) {
        Button button = menu.addChild(new Button(label));
        button.setInsets(new Insets3f(6, 0, 6, 0));
        button.setBackground(new QuadBackgroundComponent(bg));
        button.setColor(fg);
        button.setFontSize(18);
        button.setPreferredSize(new Vector3f(280, 48, 0));
        button.addClickCommands(source -> action.run());
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

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
import com.simsilica.lemur.Insets3f;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.component.SpringGridLayout;

import com.paddleshock.app.PaddleShockApp;
import com.paddleshock.i18n.I18n;

public class PauseState extends BaseAppState {

    private final Node uiRoot = new Node("pauseUi");

    @Override
    protected void initialize(Application application) {
        // Built fresh in rebuild() every time the screen is shown.
    }

    private void rebuild(PaddleShockApp app) {
        uiRoot.detachAllChildren();

        SimpleApplication simpleApp = (SimpleApplication) app;
        float screenW = simpleApp.getCamera().getWidth();
        float screenH = simpleApp.getCamera().getHeight();

        Container overlay = new Container();
        QuadBackgroundComponent overlayBg = new QuadBackgroundComponent(Theme.BACKGROUND);
        overlayBg.setAlpha(0.82f);
        overlay.setBackground(overlayBg);
        overlay.setPreferredSize(new Vector3f(screenW, screenH, 0));
        overlay.setLocalTranslation(0, screenH, 0);
        uiRoot.attachChild(overlay);

        Container panel = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        panel.setBackground(new QuadBackgroundComponent(Theme.PANEL));
        panel.setInsets(new Insets3f(24, 32, 24, 32));

        Label title = panel.addChild(new Label(I18n.t("pause.title")));
        title.setFontSize(28);
        title.setColor(Theme.TEXT);
        title.setInsets(new Insets3f(0, 0, 16, 0));

        addMenuButton(panel, I18n.t("pause.resume"), Theme.ORANGE, Theme.ON_ACCENT, app::resumeMatch);
        addMenuButton(panel, I18n.t("pause.options"), Theme.PANEL_HOVER, Theme.TEXT, () -> app.showOptions(app::showPause));
        addMenuButton(panel, I18n.t("pause.quit_to_menu"), Theme.PANEL_HOVER, Theme.TEXT, app::quitToMainMenu);

        Vector3f panelSize = panel.getPreferredSize();
        panel.setLocalTranslation((screenW - panelSize.x) / 2f, (screenH + panelSize.y) / 2f, 1);
        uiRoot.attachChild(panel);
    }

    private void addMenuButton(Container menu, String label, ColorRGBA bg, ColorRGBA fg, Runnable action) {
        Button button = menu.addChild(new Button(label));
        button.setInsets(new Insets3f(6, 0, 6, 0));
        button.setBackground(new QuadBackgroundComponent(bg));
        button.setColor(fg);
        button.setFontSize(16);
        button.setPreferredSize(new Vector3f(260, 44, 0));
        button.addClickCommands(source -> {
            ((PaddleShockApp) getApplication()).getAudioManager().playSfx("button_click.ogg");
            action.run();
        });
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

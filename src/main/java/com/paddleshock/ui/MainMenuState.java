package com.paddleshock.ui;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.texture.Texture;
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
        Texture bgTexture = simpleApp.getAssetManager().loadTexture("Textures/Asphalt/Asphalt_Color.jpg");
        bgTexture.setWrap(Texture.WrapMode.Repeat);
        QuadBackgroundComponent bgComponent = new QuadBackgroundComponent(bgTexture);
        bgComponent.setTextureCoordinateScale(new Vector2f(4f, 2.3f));
        bgComponent.setColor(new ColorRGBA(0.5f, 0.55f, 0.65f, 1f));
        background.setBackground(bgComponent);
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
        float logoY = screenH * 0.78f;
        paddleLabel.setLocalTranslation((screenW - logoWidth) / 2f, logoY, 1);
        shockLabel.setLocalTranslation((screenW - logoWidth) / 2f + paddleLabel.getPreferredSize().x, logoY, 1);

        Container menu = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        addMenuButton(menu, "PLAY VS AI", Theme.ORANGE, Theme.ON_ACCENT, app::showLoadout);
        addMenuButton(menu, "MULTIPLAYER", Theme.PANEL_HOVER, Theme.TEXT, app::showMultiplayer);
        addMenuButton(menu, "STORE", Theme.PANEL_HOVER, Theme.TEXT, app::showStore);
        addMenuButton(menu, "SETTINGS", Theme.PANEL_HOVER, Theme.TEXT, () -> app.showOptions(app::showMainMenu));
        addMenuButton(menu, "QUIT", Theme.PANEL_HOVER, Theme.TEXT, app::stop);

        Vector3f menuSize = menu.getPreferredSize();
        menu.setLocalTranslation((screenW - menuSize.x) / 2f, screenH * 0.56f, 1);
        uiRoot.attachChild(menu);
    }

    private void addMenuButton(Container menu, String label, ColorRGBA bg, ColorRGBA fg, Runnable action) {
        Button button = menu.addChild(new Button(label));
        button.setInsets(new Insets3f(6, 0, 6, 0));
        button.setBackground(new QuadBackgroundComponent(bg));
        button.setColor(fg);
        button.setFontSize(18);
        button.setPreferredSize(new Vector3f(280, 48, 0));
        button.addClickCommands(source -> {
            ((PaddleShockApp) getApplication()).getAudioManager().playSfx("button_click.ogg");
            action.run();
        });
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

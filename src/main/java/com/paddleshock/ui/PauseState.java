package com.paddleshock.ui;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.Label;

import com.paddleshock.app.PaddleShockApp;

public class PauseState extends BaseAppState {

    private Container window;

    @Override
    protected void initialize(Application application) {
        PaddleShockApp app = (PaddleShockApp) application;

        window = new Container();
        window.addChild(new Label("Paused"));

        Button resume = window.addChild(new Button("Resume"));
        resume.addClickCommands(source -> app.resumeMatch());

        Button options = window.addChild(new Button("Options"));
        options.addClickCommands(source -> app.showOptions(app::showPause));

        Button quit = window.addChild(new Button("Quit to Menu"));
        quit.addClickCommands(source -> app.quitToMainMenu());

        centerWindow((SimpleApplication) application);
    }

    private void centerWindow(SimpleApplication app) {
        window.setLocalTranslation(
                app.getCamera().getWidth() / 2f - 100,
                app.getCamera().getHeight() / 2f + 100,
                0);
    }

    @Override
    protected void cleanup(Application application) {
        // Container is detached in onDisable(); nothing else owns native resources here.
    }

    @Override
    protected void onEnable() {
        ((SimpleApplication) getApplication()).getGuiNode().attachChild(window);
        getApplication().getInputManager().setCursorVisible(true);
    }

    @Override
    protected void onDisable() {
        window.removeFromParent();
    }
}

package com.paddleshock.ui;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.component.SpringGridLayout;

import com.paddleshock.app.PaddleShockApp;
import com.paddleshock.settings.GameSettings;
import com.paddleshock.settings.VideoQuality;

public class OptionsState extends BaseAppState {

    private Container window;
    private Label mouseSensitivityLabel;
    private Label brightnessLabel;
    private Label soundVolumeLabel;
    private Label videoQualityLabel;

    private Runnable backAction = () -> {
    };

    public void setBackAction(Runnable backAction) {
        this.backAction = backAction;
    }

    @Override
    protected void initialize(Application application) {
        PaddleShockApp app = (PaddleShockApp) application;

        window = new Container();
        window.addChild(new Label("Options"));

        mouseSensitivityLabel = addStepperRow(window, "Mouse sensitivity",
                () -> adjustMouseSensitivity(app, -0.1f),
                () -> adjustMouseSensitivity(app, 0.1f));

        brightnessLabel = addStepperRow(window, "Brightness",
                () -> adjustBrightness(app, -0.1f),
                () -> adjustBrightness(app, 0.1f));

        soundVolumeLabel = addStepperRow(window, "Sound volume",
                () -> adjustSoundVolume(app, -0.1f),
                () -> adjustSoundVolume(app, 0.1f));

        videoQualityLabel = addStepperRow(window, "Video quality",
                () -> cycleVideoQuality(app, -1),
                () -> cycleVideoQuality(app, 1));

        Button back = window.addChild(new Button("Back"));
        back.addClickCommands(source -> {
            setEnabled(false);
            backAction.run();
        });

        refreshLabels(app.getGameSettings());
        centerWindow((SimpleApplication) application);
    }

    private Label addStepperRow(Container parent, String name, Runnable onDecrease, Runnable onIncrease) {
        Container row = parent.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        row.addChild(new Label(name));
        Button minus = row.addChild(new Button("-"));
        minus.addClickCommands(source -> onDecrease.run());
        Label valueLabel = row.addChild(new Label(""));
        Button plus = row.addChild(new Button("+"));
        plus.addClickCommands(source -> onIncrease.run());
        return valueLabel;
    }

    private void adjustMouseSensitivity(PaddleShockApp app, float delta) {
        GameSettings settings = app.getGameSettings();
        settings.setMouseSensitivity(settings.getMouseSensitivity() + delta);
        app.saveGameSettings();
        refreshLabels(settings);
    }

    private void adjustBrightness(PaddleShockApp app, float delta) {
        GameSettings settings = app.getGameSettings();
        settings.setBrightness(settings.getBrightness() + delta);
        app.saveGameSettings();
        refreshLabels(settings);
    }

    private void adjustSoundVolume(PaddleShockApp app, float delta) {
        GameSettings settings = app.getGameSettings();
        settings.setSoundVolume(settings.getSoundVolume() + delta);
        app.saveGameSettings();
        refreshLabels(settings);
    }

    private void cycleVideoQuality(PaddleShockApp app, int direction) {
        VideoQuality[] values = VideoQuality.values();
        GameSettings settings = app.getGameSettings();
        int nextIndex = Math.floorMod(settings.getVideoQuality().ordinal() + direction, values.length);
        settings.setVideoQuality(values[nextIndex]);
        app.saveGameSettings();
        app.applyVideoQuality(values[nextIndex]);
        refreshLabels(settings);
    }

    private void refreshLabels(GameSettings settings) {
        mouseSensitivityLabel.setText(String.format("%.1f", settings.getMouseSensitivity()));
        brightnessLabel.setText(String.format("%.1f", settings.getBrightness()));
        soundVolumeLabel.setText(String.format("%.1f", settings.getSoundVolume()));
        videoQualityLabel.setText(settings.getVideoQuality().name());
    }

    private void centerWindow(SimpleApplication app) {
        window.setLocalTranslation(
                app.getCamera().getWidth() / 2f - 160,
                app.getCamera().getHeight() / 2f + 140,
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
        refreshLabels(((PaddleShockApp) getApplication()).getGameSettings());
    }

    @Override
    protected void onDisable() {
        window.removeFromParent();
    }
}

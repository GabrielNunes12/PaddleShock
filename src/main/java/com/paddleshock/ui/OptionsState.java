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
import com.paddleshock.settings.GameSettings;
import com.paddleshock.settings.Resolution;
import com.paddleshock.settings.VideoQuality;

public class OptionsState extends BaseAppState {

    private final Node uiRoot = new Node("optionsUi");

    private Label mouseSensitivityLabel;
    private Label brightnessLabel;
    private Label soundVolumeLabel;
    private Label musicVolumeLabel;
    private Label videoQualityLabel;
    private Label fullscreenLabel;
    private Label resolutionLabel;

    private Runnable backAction = () -> {
    };

    public void setBackAction(Runnable backAction) {
        this.backAction = backAction;
    }

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

        Container panel = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        panel.setBackground(new QuadBackgroundComponent(Theme.PANEL));
        panel.setInsets(new Insets3f(24, 32, 24, 32));

        Label title = panel.addChild(new Label("OPTIONS"));
        title.setFontSize(28);
        title.setColor(Theme.ORANGE);
        title.setInsets(new Insets3f(0, 0, 16, 0));

        mouseSensitivityLabel = addStepperRow(panel, "MOUSE SENSITIVITY",
                () -> adjustMouseSensitivity(app, -0.1f), () -> adjustMouseSensitivity(app, 0.1f));
        brightnessLabel = addStepperRow(panel, "BRIGHTNESS",
                () -> adjustBrightness(app, -0.1f), () -> adjustBrightness(app, 0.1f));
        soundVolumeLabel = addStepperRow(panel, "SOUND VOLUME",
                () -> adjustSoundVolume(app, -0.1f), () -> adjustSoundVolume(app, 0.1f));
        musicVolumeLabel = addStepperRow(panel, "MUSIC VOLUME",
                () -> adjustMusicVolume(app, -0.1f), () -> adjustMusicVolume(app, 0.1f));
        videoQualityLabel = addStepperRow(panel, "VIDEO QUALITY",
                () -> cycleVideoQuality(app, -1), () -> cycleVideoQuality(app, 1));
        resolutionLabel = addStepperRow(panel, "RESOLUTION",
                () -> cycleResolution(app, -1), () -> cycleResolution(app, 1));
        fullscreenLabel = addStepperRow(panel, "FULLSCREEN",
                () -> toggleFullscreen(app), () -> toggleFullscreen(app));

        Button back = panel.addChild(new Button("BACK"));
        back.setInsets(new Insets3f(16, 0, 0, 0));
        back.setBackground(new QuadBackgroundComponent(Theme.ORANGE));
        back.setColor(Theme.ON_ACCENT);
        back.setFontSize(16);
        back.setPreferredSize(new Vector3f(260, 44, 0));
        back.addClickCommands(source -> {
            playClick();
            setEnabled(false);
            backAction.run();
        });

        refreshLabels(app.getGameSettings());

        Vector3f panelSize = panel.getPreferredSize();
        panel.setLocalTranslation((screenW - panelSize.x) / 2f, (screenH + panelSize.y) / 2f, 1);
        uiRoot.attachChild(panel);
    }

    private Label addStepperRow(Container parent, String name, Runnable onDecrease, Runnable onIncrease) {
        Container row = parent.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        row.setInsets(new Insets3f(4, 0, 4, 0));

        Label nameLabel = row.addChild(new Label(name));
        nameLabel.setColor(Theme.TEXT_DIM);
        nameLabel.setFontSize(14);
        nameLabel.setPreferredSize(new Vector3f(220, 30, 0));

        Button minus = row.addChild(new Button("-"));
        minus.setBackground(new QuadBackgroundComponent(Theme.PANEL_HOVER));
        minus.setColor(Theme.TEXT);
        minus.setPreferredSize(new Vector3f(36, 30, 0));
        minus.addClickCommands(source -> {
            playClick();
            onDecrease.run();
        });

        Label valueLabel = row.addChild(new Label(""));
        valueLabel.setColor(Theme.TEXT);
        valueLabel.setFontSize(14);
        valueLabel.setPreferredSize(new Vector3f(70, 30, 0));
        valueLabel.setTextHAlignment(com.simsilica.lemur.HAlignment.Center);

        Button plus = row.addChild(new Button("+"));
        plus.setBackground(new QuadBackgroundComponent(Theme.PANEL_HOVER));
        plus.setColor(Theme.TEXT);
        plus.setPreferredSize(new Vector3f(36, 30, 0));
        plus.addClickCommands(source -> {
            playClick();
            onIncrease.run();
        });

        return valueLabel;
    }

    private void playClick() {
        ((PaddleShockApp) getApplication()).getAudioManager().playSfx("button_click.ogg");
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

    private void adjustMusicVolume(PaddleShockApp app, float delta) {
        GameSettings settings = app.getGameSettings();
        settings.setMusicVolume(settings.getMusicVolume() + delta);
        app.saveGameSettings();
        app.getAudioManager().refreshMusicVolume();
        refreshLabels(settings);
    }

    private void cycleVideoQuality(PaddleShockApp app, int direction) {
        VideoQuality[] values = VideoQuality.values();
        GameSettings settings = app.getGameSettings();
        int nextIndex = Math.floorMod(settings.getVideoQuality().ordinal() + direction, values.length);
        settings.setVideoQuality(values[nextIndex]);
        app.saveGameSettings();
        app.applyDisplaySettings();
        refreshLabels(settings);
    }

    private void toggleFullscreen(PaddleShockApp app) {
        GameSettings settings = app.getGameSettings();
        settings.setFullscreen(!settings.isFullscreen());
        app.saveGameSettings();
        app.applyDisplaySettings();
        refreshLabels(settings);
    }

    private void cycleResolution(PaddleShockApp app, int direction) {
        Resolution[] values = Resolution.values();
        GameSettings settings = app.getGameSettings();
        int nextIndex = Math.floorMod(settings.getResolution().ordinal() + direction, values.length);
        settings.setResolution(values[nextIndex]);
        app.saveGameSettings();
        if (!settings.isFullscreen()) {
            app.applyDisplaySettings();
        }
        refreshLabels(settings);
    }

    private void refreshLabels(GameSettings settings) {
        mouseSensitivityLabel.setText(String.format("%.1f", settings.getMouseSensitivity()));
        brightnessLabel.setText(String.format("%.1f", settings.getBrightness()));
        soundVolumeLabel.setText(String.format("%.1f", settings.getSoundVolume()));
        musicVolumeLabel.setText(String.format("%.1f", settings.getMusicVolume()));
        videoQualityLabel.setText(settings.getVideoQuality().name());
        resolutionLabel.setText(settings.getResolution().toString());
        fullscreenLabel.setText(settings.isFullscreen() ? "ON" : "OFF");
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

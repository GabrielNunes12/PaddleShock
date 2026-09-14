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

/**
 * Settings screen: the same 7 stepper-controlled settings as before, now grouped into 3
 * side-by-side cards (AUDIO/VIDEO/CONTROLS) instead of one flat vertical list - a pure
 * reskin/relayout (redesign mockup). Every row keeps its exact {@code -}/value/{@code +} stepper
 * mechanism and the same {@link GameSettings} calls it always made; only which card a row lives
 * in, and how the row itself is styled, changed.
 */
public class OptionsState extends BaseAppState {

    private static final float CARD_WIDTH = 288f;

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

        Container columns = panel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        columns.setInsets(new Insets3f(0, 0, 18, 0));

        Container audioCard = addCard(columns, "AUDIO", 16);
        soundVolumeLabel = addStepperRow(audioCard, "SOUND VOLUME",
                () -> adjustSoundVolume(app, -0.1f), () -> adjustSoundVolume(app, 0.1f));
        musicVolumeLabel = addStepperRow(audioCard, "MUSIC VOLUME",
                () -> adjustMusicVolume(app, -0.1f), () -> adjustMusicVolume(app, 0.1f));
        fixCardWidth(audioCard);

        Container videoCard = addCard(columns, "VIDEO", 16);
        brightnessLabel = addStepperRow(videoCard, "BRIGHTNESS",
                () -> adjustBrightness(app, -0.1f), () -> adjustBrightness(app, 0.1f));
        videoQualityLabel = addStepperRow(videoCard, "VIDEO QUALITY",
                () -> cycleVideoQuality(app, -1), () -> cycleVideoQuality(app, 1));
        fullscreenLabel = addStepperRow(videoCard, "FULLSCREEN",
                () -> toggleFullscreen(app), () -> toggleFullscreen(app));
        fixCardWidth(videoCard);

        Container controlsCard = addCard(columns, "CONTROLS", 0);
        mouseSensitivityLabel = addStepperRow(controlsCard, "MOUSE SENS.",
                () -> adjustMouseSensitivity(app, -0.1f), () -> adjustMouseSensitivity(app, 0.1f));
        resolutionLabel = addStepperRow(controlsCard, "RESOLUTION",
                () -> cycleResolution(app, -1), () -> cycleResolution(app, 1));
        fixCardWidth(controlsCard);

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

    /** Builds one bordered {@code Theme.PANEL} card (a {@code Theme.PANEL_LINE} hairline border
     *  around a padded content area) with a small dim section header, as a child of {@code
     *  parent}. Returns the inner content container callers should add their stepper rows to;
     *  call {@link #fixCardWidth} once all of a card's rows have been added. */
    private Container addCard(Container parent, String headerText, float marginRight) {
        Container wrapper = parent.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        wrapper.setInsets(new Insets3f(0, 0, 0, marginRight));

        Container border = wrapper.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        border.setBackground(new QuadBackgroundComponent(Theme.PANEL_LINE));
        border.setInsets(new Insets3f(2, 2, 2, 2));

        Container inner = border.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        inner.setBackground(new QuadBackgroundComponent(Theme.PANEL));
        inner.setInsets(new Insets3f(14, 16, 14, 16));

        Label header = inner.addChild(new Label(headerText));
        header.setFontSize(12);
        header.setColor(Theme.TEXT_DIM);
        header.setInsets(new Insets3f(0, 0, 10, 0));

        return inner;
    }

    /** Pins a card's width to {@link #CARD_WIDTH} while preserving the height its actual rows
     *  already computed - must be called only AFTER all of a card's rows are added; fixing the
     *  width before that would freeze the container at its (near-empty) preferred size and crash
     *  the layout once the real rows no longer fit it (SpringGridLayout computing a negative
     *  remaining size). */
    private void fixCardWidth(Container card) {
        Vector3f current = card.getPreferredSize();
        card.setPreferredSize(new Vector3f(CARD_WIDTH, current.y, 0));
    }

    private Label addStepperRow(Container parent, String name, Runnable onDecrease, Runnable onIncrease) {
        Container row = parent.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        row.setInsets(new Insets3f(4, 0, 4, 0));

        Label nameLabel = row.addChild(new Label(name));
        nameLabel.setColor(Theme.TEXT_DIM);
        nameLabel.setFontSize(12);
        nameLabel.setPreferredSize(new Vector3f(108, 28, 0));

        Button minus = row.addChild(new Button("-"));
        minus.setBackground(new QuadBackgroundComponent(Theme.PANEL_HOVER));
        minus.setColor(Theme.TEXT);
        minus.setPreferredSize(new Vector3f(28, 28, 0));
        minus.addClickCommands(source -> {
            playClick();
            onDecrease.run();
        });

        // Wide enough for the longest value shown by any row here ("1280x720") without wrapping -
        // a narrower label was clipping/wrapping the RESOLUTION row's value onto two lines.
        Label valueLabel = row.addChild(new Label(""));
        valueLabel.setColor(Theme.TEXT);
        valueLabel.setFontSize(12);
        valueLabel.setPreferredSize(new Vector3f(82, 28, 0));
        valueLabel.setTextHAlignment(com.simsilica.lemur.HAlignment.Center);

        Button plus = row.addChild(new Button("+"));
        plus.setBackground(new QuadBackgroundComponent(Theme.PANEL_HOVER));
        plus.setColor(Theme.TEXT);
        plus.setPreferredSize(new Vector3f(28, 28, 0));
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

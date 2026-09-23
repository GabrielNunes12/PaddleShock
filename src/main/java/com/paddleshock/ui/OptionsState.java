package com.paddleshock.ui;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.DefaultRangedValueModel;
import com.simsilica.lemur.FillMode;
import com.simsilica.lemur.HAlignment;
import com.simsilica.lemur.Insets3f;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.Panel;
import com.simsilica.lemur.Slider;
import com.simsilica.lemur.VAlignment;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.component.SpringGridLayout;
import com.simsilica.lemur.core.GuiControl;
import com.simsilica.lemur.core.VersionedReference;

import com.paddleshock.app.PlayerContext;
import com.paddleshock.i18n.I18n;
import com.paddleshock.settings.GameSettings;
import com.paddleshock.settings.Lang;
import com.paddleshock.settings.Resolution;
import com.paddleshock.settings.VideoQuality;

/**
 * Settings screen: 8 settings grouped into 3 side-by-side cards (AUDIO/VIDEO/CONTROLS). Discrete
 * settings (brightness, video quality, fullscreen, screen shake, resolution) keep their original
 * {@code -}/value/{@code +} stepper mechanism. Continuous ones a player wants to drag straight to
 * a value (sound volume, music volume, mouse sensitivity) are real {@link Slider}s instead - see
 * {@link #addSliderRow} and {@link #update(float)} for how those apply live and persist.
 */
public class OptionsState extends BaseAppState {

    private static final float CARD_WIDTH = 288f;

    private final Node uiRoot = new Node("optionsUi");
    private final SaveDebounce saveDebounce = new SaveDebounce();

    private Label brightnessLabel;
    private Label videoQualityLabel;
    private Label fullscreenLabel;
    private Label screenShakeLabel;
    private Label resolutionLabel;

    private Label mouseSensitivityLabel;
    private Label soundVolumeLabel;
    private Label musicVolumeLabel;
    private Slider mouseSensitivitySlider;
    private Slider soundVolumeSlider;
    private Slider musicVolumeSlider;
    private VersionedReference<Double> mouseSensitivityRef;
    private VersionedReference<Double> soundVolumeRef;
    private VersionedReference<Double> musicVolumeRef;

    private Button enLangButton;
    private Button ptBrLangButton;

    private Runnable backAction = () -> {
    };

    public void setBackAction(Runnable backAction) {
        this.backAction = backAction;
    }

    @Override
    protected void initialize(Application application) {
        // Built fresh in rebuild() every time the screen is shown.
    }

    private void rebuild(PlayerContext app) {
        uiRoot.detachAllChildren();

        GameSettings settings = app.getGameSettings();
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

        Label title = panel.addChild(new Label(I18n.t("options.title")));
        title.setFontSize(28);
        title.setColor(Theme.ORANGE);
        title.setInsets(new Insets3f(0, 0, 16, 0));

        Container columns = panel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        columns.setInsets(new Insets3f(0, 0, 18, 0));

        Container audioCard = addCard(columns, I18n.t("options.audio"), 16);
        SliderRow soundVolumeRow = addSliderRow(audioCard, I18n.t("options.sound_volume"),
                0.0, 1.0, settings.getSoundVolume(), 0.1);
        soundVolumeSlider = soundVolumeRow.slider();
        soundVolumeLabel = soundVolumeRow.valueLabel();
        soundVolumeRef = soundVolumeSlider.getModel().createReference();

        SliderRow musicVolumeRow = addSliderRow(audioCard, I18n.t("options.music_volume"),
                0.0, 1.0, settings.getMusicVolume(), 0.1);
        musicVolumeSlider = musicVolumeRow.slider();
        musicVolumeLabel = musicVolumeRow.valueLabel();
        musicVolumeRef = musicVolumeSlider.getModel().createReference();
        fixCardWidth(audioCard);

        Container videoCard = addCard(columns, I18n.t("options.video"), 16);
        brightnessLabel = addStepperRow(videoCard, I18n.t("options.brightness"),
                () -> adjustBrightness(app, -0.1f), () -> adjustBrightness(app, 0.1f));
        videoQualityLabel = addStepperRow(videoCard, I18n.t("options.video_quality"),
                () -> cycleVideoQuality(app, -1), () -> cycleVideoQuality(app, 1));
        fullscreenLabel = addStepperRow(videoCard, I18n.t("options.fullscreen"),
                () -> toggleFullscreen(app), () -> toggleFullscreen(app));
        screenShakeLabel = addStepperRow(videoCard, I18n.t("options.screen_shake"),
                () -> toggleScreenShake(app), () -> toggleScreenShake(app));
        fixCardWidth(videoCard);

        Container controlsCard = addCard(columns, I18n.t("options.controls"), 16);
        SliderRow mouseSensitivityRow = addSliderRow(controlsCard, I18n.t("options.mouse_sens"),
                0.1, 5.0, settings.getMouseSensitivity(), 0.1);
        mouseSensitivitySlider = mouseSensitivityRow.slider();
        mouseSensitivityLabel = mouseSensitivityRow.valueLabel();
        mouseSensitivityRef = mouseSensitivitySlider.getModel().createReference();

        resolutionLabel = addStepperRow(controlsCard, I18n.t("options.resolution"),
                () -> cycleResolution(app, -1), () -> cycleResolution(app, 1));
        fixCardWidth(controlsCard);

        Container languageCard = addLanguageCard(columns, app);
        fixCardWidth(languageCard);

        Button back = panel.addChild(new Button(I18n.t("options.back")));
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

        // FillMode.None on the main (row-stacking) axis matters here: the 4 cards sit side by side
        // in a row that stretches every card to match the tallest one's height (VIDEO, with its 4
        // rows) - without this, the default Even fill would then redistribute THAT extra height
        // across AUDIO/CONTROLS's own (fewer) rows, inflating each row well past its own preferred
        // height. That's what was stretching every row's contents - stepper buttons included, just
        // less obviously than the sliders - into tall slivers; any leftover height now just becomes
        // blank space at the bottom of the shorter cards instead.
        Container inner = border.addChild(
                new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.Even)));
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

    /** Builds the LANGUAGE card (approved mockup Option A): two flag rows, English and
     *  Português (Brasil), the active one highlighted green with a checkmark. Picking the
     *  inactive one saves it, reloads {@link I18n}, and rebuilds this whole screen in the new
     *  language immediately - every UI state already rebuilds its text on {@code onEnable()}, so
     *  no restart is needed for screens visited after the switch either. */
    private Container addLanguageCard(Container parent, PlayerContext app) {
        Container wrapper = parent.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));

        Container border = wrapper.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        border.setBackground(new QuadBackgroundComponent(Theme.PANEL_LINE));
        border.setInsets(new Insets3f(2, 2, 2, 2));

        Container inner = border.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        inner.setBackground(new QuadBackgroundComponent(Theme.PANEL));
        inner.setInsets(new Insets3f(14, 16, 14, 16));

        Label header = inner.addChild(new Label(I18n.t("options.language")));
        header.setFontSize(12);
        header.setColor(Theme.TEXT_DIM);
        header.setInsets(new Insets3f(0, 0, 10, 0));

        Lang active = app.getGameSettings().getLanguage();

        enLangButton = inner.addChild(new Button(langButtonText(Lang.EN)));
        enLangButton.setIcon(FlagIcons.usFlag());
        styleLanguageButton(enLangButton, active == Lang.EN);
        enLangButton.addClickCommands(source -> {
            playClick();
            applyLanguage(app, Lang.EN);
        });

        ptBrLangButton = inner.addChild(new Button(langButtonText(Lang.PT_BR)));
        ptBrLangButton.setIcon(FlagIcons.brFlag());
        styleLanguageButton(ptBrLangButton, active == Lang.PT_BR);
        ptBrLangButton.setInsets(new Insets3f(6, 0, 0, 0));
        ptBrLangButton.addClickCommands(source -> {
            playClick();
            applyLanguage(app, Lang.PT_BR);
        });

        return inner;
    }

    private String langButtonText(Lang lang) {
        String key = lang == Lang.EN ? "lang.en" : "lang.pt_br";
        return "  " + I18n.t(key + ".name") + "\n  " + I18n.t(key + ".region");
    }

    private void styleLanguageButton(Button button, boolean active) {
        button.setBackground(new QuadBackgroundComponent(active ? Theme.GREEN_DIM : Theme.PANEL_HOVER));
        button.setColor(active ? Theme.TEXT : Theme.TEXT_DIM);
        button.setFontSize(12);
        button.setTextHAlignment(HAlignment.Left);
        button.setPreferredSize(new Vector3f(CARD_WIDTH - 32, 44, 0));
    }

    private void applyLanguage(PlayerContext app, Lang lang) {
        GameSettings settings = app.getGameSettings();
        if (settings.getLanguage() == lang) {
            return;
        }
        settings.setLanguage(lang);
        app.saveGameSettings();
        I18n.setLanguage(lang);
        rebuild(app);
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
        valueLabel.setTextHAlignment(HAlignment.Center);

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

    /** A slider row's two live-updated parts, returned to the caller so it can keep the
     *  {@code Slider} (for its model/reference, polled in {@link #update(float)}) and the value
     *  label (for {@link #refreshLabels}) in dedicated fields. */
    private record SliderRow(Slider slider, Label valueLabel) {
    }

    /** Card content width (matches {@link #addCard}'s 16px left/right insets on {@link
     *  #CARD_WIDTH}) - the budget {@link #addSliderRow} divides between its name/slider/value
     *  columns so the slider can be as wide as the card allows. */
    private static final float CARD_CONTENT_WIDTH = CARD_WIDTH - 32f;
    // Matches SLIDER_ARROW_SIZE exactly (with the slider's own 2px top+bottom style insets, its
    // preferred height is also 24) so the row's minor-axis fill has nothing to stretch: every cell
    // (name label, slider, value label) already wants the same height, so the arrow buttons render
    // at their real 22x22 instead of being inflated to match a taller sibling cell.
    private static final float SLIDER_ROW_HEIGHT = 24f;
    private static final float SLIDER_ARROW_SIZE = 22f;
    private static final float SLIDER_NAME_WIDTH = 100f;
    private static final float SLIDER_VALUE_WIDTH = 36f;
    /** Everything left over for the track between the two arrow buttons. */
    private static final float SLIDER_TRACK_WIDTH =
            CARD_CONTENT_WIDTH - SLIDER_NAME_WIDTH - SLIDER_VALUE_WIDTH - 2 * SLIDER_ARROW_SIZE;

    /**
     * Builds a NAME / {@code <--0-->} / value row: a real drag-and-arrow-button {@link Slider}
     * (see {@link UiStyle}'s {@code "slider.*"} overrides for how its look is pulled away from
     * Lemur's default teal "glass" gradient), sized to use all of the card's content width, plus
     * a label mirroring the slider's current value. The slider's arrow buttons get the same click
     * sound as the stepper rows'; dragging the thumb stays silent. Reading the live value back out
     * of the model and persisting it is the caller's job (see {@link #update(float)}) - a
     * {@code Slider} only fires click commands for its arrow buttons, never for a drag, so nothing
     * here can just be an {@code addClickCommands} callback.
     */
    private SliderRow addSliderRow(Container parent, String name, double min, double max, double value,
            double delta) {
        Container row = parent.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        row.setInsets(new Insets3f(4, 0, 4, 0));

        Label nameLabel = row.addChild(new Label(name));
        nameLabel.setColor(Theme.TEXT_DIM);
        nameLabel.setFontSize(12);
        nameLabel.setPreferredSize(new Vector3f(SLIDER_NAME_WIDTH, SLIDER_ROW_HEIGHT, 0));
        nameLabel.setTextVAlignment(VAlignment.Center);

        Slider slider = row.addChild(new Slider(new DefaultRangedValueModel(min, max, value), Axis.X));
        slider.setDelta(delta);

        // Rather than fight the Slider container's own preferred-size recompute (it's managed by
        // its OWN internal BorderLayout, which recalculates the container's size from its children
        // every pass - a plain setPreferredSize on the Slider itself doesn't stick), size the
        // pieces that actually determine that computed size: the two arrow buttons (their preferred
        // size sets both their own on-screen size AND the slider's overall height, since
        // BorderLayout stretches the center "range" region to whatever height the East/West
        // buttons need) and the range/track panel (its preferred width is the main driver of the
        // slider's overall width).
        Vector3f arrowSize = new Vector3f(SLIDER_ARROW_SIZE, SLIDER_ARROW_SIZE, 0);
        for (Button arrow : new Button[] {slider.getDecrementButton(), slider.getIncrementButton()}) {
            arrow.setPreferredSize(arrowSize);
            arrow.setTextHAlignment(HAlignment.Center);
            arrow.setTextVAlignment(VAlignment.Center);
            arrow.addClickCommands(source -> playClick());
        }

        // The default track (Lemur calls it the "range" panel) is the same Theme.PANEL as the
        // card behind it, so it was invisible - the knob looked like it floated in empty space.
        // QuadBackgroundComponent's margin insets the drawn quad without affecting layout size, so
        // this reads as a thin PANEL_LINE bar vertically centered in the row instead of a big block.
        Panel range = slider.getRangePanel();
        range.setPreferredSize(new Vector3f(SLIDER_TRACK_WIDTH, SLIDER_ARROW_SIZE, 0));
        QuadBackgroundComponent trackBackground = new QuadBackgroundComponent(Theme.PANEL_LINE);
        trackBackground.setMargin(0, (SLIDER_ARROW_SIZE - 4f) / 2f);
        range.setBackground(trackBackground);

        // The thumb button isn't managed by the slider's own BorderLayout (it's positioned by hand
        // in Slider.resetStateView, based on its CURRENT size, not its preferred one), so a plain
        // setPreferredSize wouldn't actually resize the rendered knob - set its GuiControl size
        // directly to get a knob wide enough to see and grab.
        Vector3f thumbSize = new Vector3f(14, SLIDER_ARROW_SIZE, 0);
        slider.getThumbButton().setPreferredSize(thumbSize);
        slider.getThumbButton().getControl(GuiControl.class).setSize(thumbSize.clone());

        Label valueLabel = row.addChild(new Label(""));
        valueLabel.setColor(Theme.TEXT);
        valueLabel.setFontSize(12);
        valueLabel.setPreferredSize(new Vector3f(SLIDER_VALUE_WIDTH, SLIDER_ROW_HEIGHT, 0));
        valueLabel.setTextHAlignment(HAlignment.Center);
        valueLabel.setTextVAlignment(VAlignment.Center);

        return new SliderRow(slider, valueLabel);
    }

    private void playClick() {
        ((PlayerContext) getApplication()).getAudioManager().playSfx("button_click.ogg");
    }

    private void adjustBrightness(PlayerContext app, float delta) {
        GameSettings settings = app.getGameSettings();
        settings.setBrightness(settings.getBrightness() + delta);
        app.saveGameSettings();
        refreshLabels(settings);
    }

    private void cycleVideoQuality(PlayerContext app, int direction) {
        VideoQuality[] values = VideoQuality.values();
        GameSettings settings = app.getGameSettings();
        int nextIndex = Math.floorMod(settings.getVideoQuality().ordinal() + direction, values.length);
        settings.setVideoQuality(values[nextIndex]);
        app.saveGameSettings();
        app.applyDisplaySettings();
        refreshLabels(settings);
    }

    private void toggleFullscreen(PlayerContext app) {
        GameSettings settings = app.getGameSettings();
        settings.setFullscreen(!settings.isFullscreen());
        app.saveGameSettings();
        app.applyDisplaySettings();
        refreshLabels(settings);
    }

    private void toggleScreenShake(PlayerContext app) {
        GameSettings settings = app.getGameSettings();
        settings.setScreenShake(!settings.isScreenShake());
        app.saveGameSettings();
        refreshLabels(settings);
    }

    private void cycleResolution(PlayerContext app, int direction) {
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
        mouseSensitivityLabel.setText(OptionsSliderFormat.multiplier(settings.getMouseSensitivity()));
        brightnessLabel.setText(String.format(java.util.Locale.ROOT, "%.1f", settings.getBrightness()));
        soundVolumeLabel.setText(OptionsSliderFormat.percent(settings.getSoundVolume()));
        musicVolumeLabel.setText(OptionsSliderFormat.percent(settings.getMusicVolume()));
        videoQualityLabel.setText(settings.getVideoQuality().name());
        resolutionLabel.setText(settings.getResolution().toString());
        fullscreenLabel.setText(settings.isFullscreen() ? I18n.t("common.on") : I18n.t("common.off"));
        screenShakeLabel.setText(settings.isScreenShake() ? I18n.t("common.on") : I18n.t("common.off"));
    }

    /**
     * Polls the 3 slider models for changes every frame - dragging a thumb (unlike clicking an
     * arrow button, or any stepper) never fires a click command, so this is the only way to notice
     * it. Each changed slider is applied to {@link GameSettings} immediately (so sound/music
     * volume and mouse sensitivity take effect on the very next SFX/frame), but the settings file
     * itself is only written after {@link #saveDebounce} has seen {@link SaveDebounce#DELAY_SECONDS}
     * of quiet - see that class for why.
     */
    @Override
    public void update(float tpf) {
        PlayerContext app = (PlayerContext) getApplication();
        GameSettings settings = app.getGameSettings();
        boolean changed = false;

        if (soundVolumeRef != null && soundVolumeRef.update()) {
            settings.setSoundVolume((float) soundVolumeSlider.getModel().getValue());
            soundVolumeLabel.setText(OptionsSliderFormat.percent(settings.getSoundVolume()));
            changed = true;
        }
        if (musicVolumeRef != null && musicVolumeRef.update()) {
            settings.setMusicVolume((float) musicVolumeSlider.getModel().getValue());
            app.getAudioManager().refreshMusicVolume();
            musicVolumeLabel.setText(OptionsSliderFormat.percent(settings.getMusicVolume()));
            changed = true;
        }
        if (mouseSensitivityRef != null && mouseSensitivityRef.update()) {
            settings.setMouseSensitivity((float) mouseSensitivitySlider.getModel().getValue());
            mouseSensitivityLabel.setText(OptionsSliderFormat.multiplier(settings.getMouseSensitivity()));
            changed = true;
        }

        if (changed) {
            saveDebounce.markChanged();
        }
        if (saveDebounce.update(tpf)) {
            app.saveGameSettings();
        }
    }

    @Override
    protected void cleanup(Application application) {
        // uiRoot is detached in onDisable(); nothing else owns native resources here.
    }

    @Override
    protected void onEnable() {
        rebuild((PlayerContext) getApplication());
        ((SimpleApplication) getApplication()).getGuiNode().attachChild(uiRoot);
        getApplication().getInputManager().setCursorVisible(true);
    }

    @Override
    protected void onDisable() {
        uiRoot.removeFromParent();
        // A drag right before backing out shouldn't be lost just because it hadn't been quiet for
        // DELAY_SECONDS yet.
        if (saveDebounce.flush()) {
            ((PlayerContext) getApplication()).saveGameSettings();
        }
    }
}

package com.paddleshock.ui;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

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
import com.paddleshock.data.BallDefinition;
import com.paddleshock.data.Catalog;
import com.paddleshock.data.LevelDefinition;
import com.paddleshock.data.PaddleDefinition;
import com.paddleshock.data.PlayerProfile;
import com.paddleshock.data.PowerUpDefinition;
import com.paddleshock.data.TableDefinition;
import com.paddleshock.settings.AiDifficulty;

/**
 * Shown before every match (fresh start or rematch): confirms/lets the player change their
 * equipped paddle/table/ball and power-up loadout, buying more from a store modal without ever
 * navigating away to the main store screen, before actually starting the match.
 */
public class LoadoutState extends BaseAppState {

    private final Node uiRoot = new Node("loadoutUi");

    /** True while the store modal is open on top of this screen, so its own buttons go inert. */
    private boolean modalOpen = false;

    private Label currencyLabel;
    private Label paddleLabel;
    private Label tableLabel;
    private Label ballLabel;
    private Button[] levelButtons;
    private final Label[] powerUpLabels = new Label[3];
    private Label aiDifficultyLabel;

    @Override
    protected void initialize(Application application) {
        // Built fresh in rebuild() every time the screen is shown.
    }

    private void rebuild(PaddleShockApp app) {
        uiRoot.detachAllChildren();
        modalOpen = false;

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

        Label title = panel.addChild(new Label("MATCH SETUP"));
        title.setFontSize(26);
        title.setColor(Theme.ORANGE);
        title.setInsets(new Insets3f(0, 0, 4, 0));

        currencyLabel = panel.addChild(new Label(""));
        currencyLabel.setFontSize(14);
        currencyLabel.setColor(Theme.TEXT_DIM);
        currencyLabel.setInsets(new Insets3f(0, 0, 18, 0));

        Container equipRow = panel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        equipRow.setInsets(new Insets3f(0, 0, 8, 0));
        paddleLabel = addTile(equipRow, "PADDLE");
        tableLabel = addTile(equipRow, "TABLE");
        ballLabel = addTile(equipRow, "BALL");

        // Levels are free and picked right here - no store trip needed, unlike the gear above.
        Label levelTitle = panel.addChild(new Label("LEVEL"));
        levelTitle.setFontSize(12);
        levelTitle.setColor(Theme.TEXT_DIM);
        levelTitle.setInsets(new Insets3f(14, 0, 6, 0));

        Container levelRow = panel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        levelRow.setInsets(new Insets3f(0, 0, 8, 0));
        levelButtons = new Button[Catalog.LEVELS.size()];
        for (int i = 0; i < Catalog.LEVELS.size(); i++) {
            LevelDefinition levelDef = Catalog.LEVELS.get(i);
            Button levelButton = levelRow.addChild(new Button(levelDef.getDisplayName().toUpperCase()));
            levelButton.setInsets(new Insets3f(4, 6, 4, 6));
            levelButton.setFontSize(13);
            levelButton.setPreferredSize(new Vector3f(160, 40, 0));
            levelButton.addClickCommands(source -> {
                if (modalOpen) {
                    return;
                }
                app.getAudioManager().playSfx("button_click.ogg");
                app.getProfile().equip("level", levelDef.getId());
                app.saveProfile();
                refreshLevelButtons(app.getProfile());
            });
            levelButtons[i] = levelButton;
        }

        Label aiTitle = panel.addChild(new Label("AI DIFFICULTY"));
        aiTitle.setFontSize(12);
        aiTitle.setColor(Theme.TEXT_DIM);
        aiTitle.setInsets(new Insets3f(14, 0, 6, 0));

        Container aiRow = panel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        aiRow.setInsets(new Insets3f(0, 0, 8, 0));
        Button aiMinus = aiRow.addChild(new Button("-"));
        aiMinus.setBackground(new QuadBackgroundComponent(Theme.PANEL_HOVER));
        aiMinus.setColor(Theme.TEXT);
        aiMinus.setPreferredSize(new Vector3f(40, 40, 0));
        aiMinus.addClickCommands(source -> {
            if (modalOpen) {
                return;
            }
            app.getAudioManager().playSfx("button_click.ogg");
            cycleAiDifficulty(app, -1);
        });

        aiDifficultyLabel = aiRow.addChild(new Label(""));
        aiDifficultyLabel.setFontSize(14);
        aiDifficultyLabel.setColor(Theme.TEXT);
        aiDifficultyLabel.setPreferredSize(new Vector3f(140, 40, 0));
        aiDifficultyLabel.setTextHAlignment(com.simsilica.lemur.HAlignment.Center);

        Button aiPlus = aiRow.addChild(new Button("+"));
        aiPlus.setBackground(new QuadBackgroundComponent(Theme.PANEL_HOVER));
        aiPlus.setColor(Theme.TEXT);
        aiPlus.setPreferredSize(new Vector3f(40, 40, 0));
        aiPlus.addClickCommands(source -> {
            if (modalOpen) {
                return;
            }
            app.getAudioManager().playSfx("button_click.ogg");
            cycleAiDifficulty(app, 1);
        });

        Label powerUpTitle = panel.addChild(new Label("POWER-UPS"));
        powerUpTitle.setFontSize(12);
        powerUpTitle.setColor(Theme.TEXT_DIM);
        powerUpTitle.setInsets(new Insets3f(14, 0, 6, 0));

        Container powerUpRow = panel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        powerUpRow.setInsets(new Insets3f(0, 0, 8, 0));
        for (int i = 0; i < 3; i++) {
            powerUpLabels[i] = addTile(powerUpRow, "KEY " + (i + 1));
        }

        Button storeButton = panel.addChild(new Button("OPEN STORE"));
        storeButton.setInsets(new Insets3f(16, 0, 8, 0));
        storeButton.setBackground(new QuadBackgroundComponent(Theme.BLUE));
        storeButton.setColor(Theme.ON_ACCENT);
        storeButton.setFontSize(16);
        storeButton.setPreferredSize(new Vector3f(340, 46, 0));
        storeButton.addClickCommands(source -> {
            if (modalOpen) {
                return;
            }
            app.getAudioManager().playSfx("button_click.ogg");
            modalOpen = true;
            app.showStoreModal(() -> {
                modalOpen = false;
                refreshLabels(app.getProfile());
            });
        });

        Button startButton = panel.addChild(new Button("START MATCH"));
        startButton.setInsets(new Insets3f(8, 0, 6, 0));
        startButton.setBackground(new QuadBackgroundComponent(Theme.ORANGE));
        startButton.setColor(Theme.ON_ACCENT);
        startButton.setFontSize(18);
        startButton.setPreferredSize(new Vector3f(340, 50, 0));
        startButton.addClickCommands(source -> {
            if (modalOpen) {
                return;
            }
            app.getAudioManager().playSfx("button_confirm.ogg");
            app.startMatchVsAI();
        });

        Button back = panel.addChild(new Button("BACK"));
        back.setInsets(new Insets3f(6, 0, 0, 0));
        back.setBackground(new QuadBackgroundComponent(Theme.PANEL_HOVER));
        back.setColor(Theme.TEXT);
        back.setFontSize(14);
        back.setPreferredSize(new Vector3f(340, 40, 0));
        back.addClickCommands(source -> {
            if (modalOpen) {
                return;
            }
            app.getAudioManager().playSfx("button_click.ogg");
            app.showMainMenu();
        });

        refreshLabels(app.getProfile());

        Vector3f panelSize = panel.getPreferredSize();
        panel.setLocalTranslation((screenW - panelSize.x) / 2f, (screenH + panelSize.y) / 2f, 1);
        uiRoot.attachChild(panel);
    }

    /** A small readout tile: a caption above a value, matching the store card's muted styling. */
    private Label addTile(Container row, String caption) {
        Container tile = row.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        tile.setInsets(new Insets3f(0, 8, 0, 8));
        tile.setBackground(new QuadBackgroundComponent(Theme.PANEL_HOVER));
        tile.setPreferredSize(new Vector3f(170, 64, 0));

        Label captionLabel = tile.addChild(new Label(caption));
        captionLabel.setFontSize(11);
        captionLabel.setColor(Theme.TEXT_DIM);
        captionLabel.setInsets(new Insets3f(8, 10, 2, 10));

        Label valueLabel = tile.addChild(new Label(""));
        valueLabel.setFontSize(14);
        valueLabel.setColor(Theme.TEXT);
        valueLabel.setInsets(new Insets3f(0, 10, 8, 10));
        return valueLabel;
    }

    private void refreshLabels(PlayerProfile profile) {
        currencyLabel.setText(profile.getCurrency() + " credits");
        aiDifficultyLabel.setText(((PaddleShockApp) getApplication()).getGameSettings().getAiDifficulty().getDisplayName());

        paddleLabel.setText(nameOf(Catalog.findPaddle(profile.getEquippedId("paddle")), PaddleDefinition::getDisplayName));
        tableLabel.setText(nameOf(Catalog.findTable(profile.getEquippedId("table")), TableDefinition::getDisplayName));
        ballLabel.setText(nameOf(Catalog.findBall(profile.getEquippedId("ball")), BallDefinition::getDisplayName));
        refreshLevelButtons(profile);

        List<String> loadout = profile.getLoadout();
        for (int i = 0; i < powerUpLabels.length; i++) {
            String id = loadout.get(i);
            if (id.isEmpty()) {
                powerUpLabels[i].setText("EMPTY");
                powerUpLabels[i].setColor(Theme.TEXT_DIM);
            } else {
                Optional<PowerUpDefinition> def = Catalog.findPowerUp(id);
                powerUpLabels[i].setText(def.map(PowerUpDefinition::getDisplayName).orElse("?").toUpperCase());
                powerUpLabels[i].setColor(Theme.TEXT);
            }
        }
    }

    private void cycleAiDifficulty(PaddleShockApp app, int direction) {
        AiDifficulty[] values = AiDifficulty.values();
        com.paddleshock.settings.GameSettings settings = app.getGameSettings();
        int nextIndex = Math.floorMod(settings.getAiDifficulty().ordinal() + direction, values.length);
        settings.setAiDifficulty(values[nextIndex]);
        app.saveGameSettings();
        aiDifficultyLabel.setText(settings.getAiDifficulty().getDisplayName());
    }

    private <T> String nameOf(Optional<T> item, Function<T, String> nameFn) {
        return item.map(nameFn).map(String::toUpperCase).orElse("NONE");
    }

    private void refreshLevelButtons(PlayerProfile profile) {
        String equippedId = profile.getEquippedId("level");
        for (int i = 0; i < levelButtons.length; i++) {
            boolean equipped = Catalog.LEVELS.get(i).getId().equals(equippedId);
            levelButtons[i].setBackground(new QuadBackgroundComponent(equipped ? Theme.GREEN : Theme.PANEL_HOVER));
            levelButtons[i].setColor(equipped ? Theme.ON_ACCENT : Theme.TEXT);
        }
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

package com.paddleshock.ui;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.HAlignment;
import com.simsilica.lemur.Insets3f;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.component.SpringGridLayout;

import com.paddleshock.app.Navigator;
import com.paddleshock.app.PlayerContext;
import com.paddleshock.data.BallDefinition;
import com.paddleshock.data.Catalog;
import com.paddleshock.data.LevelDefinition;
import com.paddleshock.data.PaddleDefinition;
import com.paddleshock.data.PlayerProfile;
import com.paddleshock.data.PowerUpDefinition;
import com.paddleshock.data.TableDefinition;
import com.paddleshock.i18n.I18n;
import com.paddleshock.settings.AiDifficulty;

/**
 * Shown before every match (fresh start or rematch): confirms/lets the player change their
 * equipped paddle/table/ball and power-up loadout, buying more from a store modal without ever
 * navigating away to the main store screen, before actually starting the match.
 */
public class LoadoutState extends BaseAppState {

    private static final float HEADER_HEIGHT = 92f;
    private static final float TILE_WIDTH = 176f;
    private static final float TILE_HEIGHT = 82f;
    private static final float SWATCH_STRIP_HEIGHT = 10f;

    private final Node uiRoot = new Node("loadoutUi");

    /** True while the store modal is open on top of this screen, so its own buttons go inert. */
    private boolean modalOpen = false;

    private Label creditsLabel;
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

    private void rebuild(Navigator nav, PlayerContext ctx) {
        uiRoot.detachAllChildren();
        modalOpen = false;

        SimpleApplication simpleApp = (SimpleApplication) getApplication();
        float screenW = simpleApp.getCamera().getWidth();
        float screenH = simpleApp.getCamera().getHeight();

        Container background = new Container();
        background.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));
        background.setPreferredSize(new Vector3f(screenW, screenH, 0));
        background.setLocalTranslation(0, screenH, 0);
        uiRoot.attachChild(background);

        buildHeader(nav, ctx, screenW, screenH);

        Container panel = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        panel.setBackground(new QuadBackgroundComponent(Theme.PANEL));
        panel.setInsets(new Insets3f(24, 32, 24, 32));

        Container equipRow = panel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        equipRow.setInsets(new Insets3f(0, 0, 8, 0));
        PlayerProfile profile = ctx.getProfile();
        PaddleDefinition equippedPaddle = Catalog.findPaddle(profile.getEquippedId("paddle")).orElse(null);
        TableDefinition equippedTable = Catalog.findTable(profile.getEquippedId("table")).orElse(null);
        BallDefinition equippedBall = Catalog.findBall(profile.getEquippedId("ball")).orElse(null);
        paddleLabel = addEquipTile(equipRow, I18n.t("loadout.paddle_caption"), equippedPaddle == null ? Theme.PANEL_LINE : equippedPaddle.getColor());
        tableLabel = addEquipTile(equipRow, I18n.t("loadout.table_caption"), equippedTable == null ? Theme.PANEL_LINE : equippedTable.getSurfaceColor());
        ballLabel = addEquipTile(equipRow, I18n.t("loadout.ball_caption"), equippedBall == null ? Theme.PANEL_LINE : equippedBall.getColor());

        // Levels are free and picked right here - no store trip needed, unlike the gear above.
        Label levelTitle = panel.addChild(new Label(I18n.t("loadout.level_title")));
        levelTitle.setFontSize(12);
        levelTitle.setColor(Theme.TEXT_DIM);
        levelTitle.setInsets(new Insets3f(14, 0, 6, 0));

        Container levelRow = panel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        levelRow.setInsets(new Insets3f(0, 0, 8, 0));
        levelButtons = new Button[Catalog.LEVELS.size()];
        for (int i = 0; i < Catalog.LEVELS.size(); i++) {
            LevelDefinition levelDef = Catalog.LEVELS.get(i);
            Button levelButton = addLevelCard(levelRow, levelDef);
            levelButton.addClickCommands(source -> {
                if (modalOpen) {
                    return;
                }
                ctx.getAudioManager().playSfx("button_click.ogg");
                ctx.getProfile().equip("level", levelDef.getId());
                ctx.saveProfile();
                refreshLevelButtons(ctx.getProfile());
            });
            levelButtons[i] = levelButton;
        }

        Label aiTitle = panel.addChild(new Label(I18n.t("loadout.ai_difficulty_title")));
        aiTitle.setFontSize(12);
        aiTitle.setColor(Theme.TEXT_DIM);
        aiTitle.setInsets(new Insets3f(14, 0, 6, 0));

        Container aiCard = newBorderedCard(panel);
        Container aiRow = aiCard.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        aiRow.setInsets(new Insets3f(10, 10, 10, 10));
        Button aiMinus = aiRow.addChild(new Button("-"));
        aiMinus.setBackground(new QuadBackgroundComponent(Theme.PANEL_HOVER));
        aiMinus.setColor(Theme.TEXT);
        aiMinus.setPreferredSize(new Vector3f(40, 40, 0));
        aiMinus.addClickCommands(source -> {
            if (modalOpen) {
                return;
            }
            ctx.getAudioManager().playSfx("button_click.ogg");
            cycleAiDifficulty(ctx, -1);
        });

        aiDifficultyLabel = aiRow.addChild(new Label(""));
        aiDifficultyLabel.setFontSize(14);
        aiDifficultyLabel.setColor(Theme.TEXT);
        aiDifficultyLabel.setPreferredSize(new Vector3f(140, 40, 0));
        aiDifficultyLabel.setTextHAlignment(HAlignment.Center);

        Button aiPlus = aiRow.addChild(new Button("+"));
        aiPlus.setBackground(new QuadBackgroundComponent(Theme.PANEL_HOVER));
        aiPlus.setColor(Theme.TEXT);
        aiPlus.setPreferredSize(new Vector3f(40, 40, 0));
        aiPlus.addClickCommands(source -> {
            if (modalOpen) {
                return;
            }
            ctx.getAudioManager().playSfx("button_click.ogg");
            cycleAiDifficulty(ctx, 1);
        });

        Label powerUpTitle = panel.addChild(new Label(I18n.t("loadout.powerups_title")));
        powerUpTitle.setFontSize(12);
        powerUpTitle.setColor(Theme.TEXT_DIM);
        powerUpTitle.setInsets(new Insets3f(14, 0, 6, 0));

        Container powerUpRow = panel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        powerUpRow.setInsets(new Insets3f(0, 0, 8, 0));
        for (int i = 0; i < 3; i++) {
            powerUpLabels[i] = addPowerUpSlotCard(powerUpRow, i + 1);
        }

        Button storeButton = panel.addChild(new Button(I18n.t("loadout.open_store")));
        storeButton.setInsets(new Insets3f(16, 0, 8, 0));
        storeButton.setBackground(new QuadBackgroundComponent(Theme.BLUE_DIM));
        storeButton.setColor(Theme.BLUE);
        storeButton.setFontSize(16);
        storeButton.setPreferredSize(new Vector3f(340, 46, 0));
        storeButton.addClickCommands(source -> {
            if (modalOpen) {
                return;
            }
            ctx.getAudioManager().playSfx("button_click.ogg");
            modalOpen = true;
            nav.showStoreModal(() -> {
                modalOpen = false;
                refreshLabels(ctx.getProfile());
            });
        });

        Button startButton = panel.addChild(new Button(I18n.t("loadout.start_match")));
        startButton.setInsets(new Insets3f(8, 0, 6, 0));
        startButton.setBackground(new QuadBackgroundComponent(Theme.ORANGE));
        startButton.setColor(Theme.ON_ACCENT);
        startButton.setFontSize(18);
        startButton.setPreferredSize(new Vector3f(340, 50, 0));
        startButton.addClickCommands(source -> {
            if (modalOpen) {
                return;
            }
            ctx.getAudioManager().playSfx("button_confirm.ogg");
            ctx.startMatchVsAI();
        });

        Button back = panel.addChild(new Button(I18n.t("loadout.back")));
        back.setInsets(new Insets3f(6, 0, 0, 0));
        back.setBackground(new QuadBackgroundComponent(Theme.PANEL_HOVER));
        back.setColor(Theme.TEXT);
        back.setFontSize(14);
        back.setPreferredSize(new Vector3f(340, 40, 0));
        back.addClickCommands(source -> {
            if (modalOpen) {
                return;
            }
            ctx.getAudioManager().playSfx("button_click.ogg");
            nav.showMainMenu();
        });

        refreshLabels(ctx.getProfile());

        Vector3f panelSize = panel.getPreferredSize();
        panel.setLocalTranslation((screenW - panelSize.x) / 2f, (screenH - HEADER_HEIGHT + panelSize.y) / 2f, 1);
        uiRoot.attachChild(panel);
    }

    /** Top bar mirroring the store screen's header: BACK, the screen title, and a credits pill
     *  in the top-right corner. */
    private void buildHeader(Navigator nav, PlayerContext ctx, float screenW, float screenH) {
        Container headerBar = new Container();
        headerBar.setBackground(new QuadBackgroundComponent(Theme.PANEL));
        headerBar.setPreferredSize(new Vector3f(screenW, HEADER_HEIGHT, 0));
        headerBar.setLocalTranslation(0, screenH, 1);
        uiRoot.attachChild(headerBar);

        Button back = new Button(I18n.t("loadout.back_header"));
        back.setBackground(new QuadBackgroundComponent(Theme.PANEL_HOVER));
        back.setColor(Theme.TEXT);
        back.setFontSize(15);
        back.setInsets(new Insets3f(8, 10, 8, 10));
        Vector3f backSize = back.getPreferredSize();
        back.setLocalTranslation(28, screenH - (HEADER_HEIGHT - backSize.y) / 2f, 2);
        back.addClickCommands(source -> {
            if (modalOpen) {
                return;
            }
            ctx.getAudioManager().playSfx("button_click.ogg");
            nav.showMainMenu();
        });
        uiRoot.attachChild(back);

        Label title = new Label(I18n.t("loadout.title"));
        title.setFontSize(24);
        title.setColor(Theme.ORANGE);
        Vector3f titleSize = title.getPreferredSize();
        title.setLocalTranslation(28 + backSize.x + 20, screenH - (HEADER_HEIGHT - titleSize.y) / 2f, 2);
        uiRoot.attachChild(title);

        Container pill = new Container(new SpringGridLayout(Axis.X, Axis.Y));
        pill.setBackground(new QuadBackgroundComponent(Theme.ORANGE_DIM));
        pill.setInsets(new Insets3f(8, 14, 8, 14));

        Container dot = pill.addChild(new Container());
        dot.setBackground(new QuadBackgroundComponent(Theme.ORANGE));
        dot.setPreferredSize(new Vector3f(10, 10, 0));
        dot.setInsets(new Insets3f(2, 0, 2, 8));

        creditsLabel = pill.addChild(new Label(""));
        creditsLabel.setColor(Theme.ORANGE);
        creditsLabel.setFontSize(16);

        Vector3f pillSize = pill.getPreferredSize();
        pill.setLocalTranslation(screenW - pillSize.x - 28, screenH - (HEADER_HEIGHT - pillSize.y) / 2f, 2);
        uiRoot.attachChild(pill);
    }

    /**
     * Wraps a new child of {@code parent} in a thin {@code Theme.PANEL_LINE} border, the same
     * "nested container with a small inset" trick used by the store screen's cards. Returns the
     * inner container (background {@code Theme.PANEL}) to populate.
     */
    private Container newBorderedCard(Container parent) {
        Container border = parent.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        border.setBackground(new QuadBackgroundComponent(Theme.PANEL_LINE));
        border.setInsets(new Insets3f(0, 8, 8, 8));

        Container card = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        card.setBackground(new QuadBackgroundComponent(Theme.PANEL));
        card.setInsets(new Insets3f(2, 2, 2, 2));
        border.addChild(card);
        return card;
    }

    /** A small equipment-slot card: a color swatch strip, a dim caption ("PADDLE"), and the
     *  equipped item's name below it - the value label is returned so callers can update it. */
    private Label addEquipTile(Container row, String caption, ColorRGBA swatchColor) {
        Container card = newBorderedCard(row);
        card.setPreferredSize(new Vector3f(TILE_WIDTH, TILE_HEIGHT, 0));

        Container swatch = card.addChild(new Container());
        swatch.setBackground(new QuadBackgroundComponent(swatchColor));
        swatch.setPreferredSize(new Vector3f(TILE_WIDTH, SWATCH_STRIP_HEIGHT, 0));

        Label captionLabel = card.addChild(new Label(caption));
        captionLabel.setFontSize(11);
        captionLabel.setColor(Theme.TEXT_DIM2);
        captionLabel.setInsets(new Insets3f(8, 10, 2, 10));

        Label valueLabel = card.addChild(new Label(""));
        valueLabel.setFontSize(14);
        valueLabel.setColor(Theme.TEXT);
        valueLabel.setInsets(new Insets3f(0, 10, 8, 10));
        return valueLabel;
    }

    /** A small preview card for one selectable level: a swatch (the level's sky color), the
     *  level's name, and (when selected) a green checkmark. Built as a {@link Button} so it stays
     *  directly clickable, matching the plain-tab button it replaces. */
    private Button addLevelCard(Container row, LevelDefinition levelDef) {
        Button card = row.addChild(new Button(levelDef.getDisplayName().toUpperCase()));
        card.setIcon(new QuadBackgroundComponent(levelDef.getSkyColor(), 6, 6));
        card.setInsets(new Insets3f(4, 6, 4, 6));
        card.setFontSize(13);
        card.setPreferredSize(new Vector3f(TILE_WIDTH, 44, 0));
        return card;
    }

    /** A numbered power-up slot card: a small "N" chip plus the assigned power-up's name, or a
     *  dim "Empty slot" label - the value label is returned so callers can update it. */
    private Label addPowerUpSlotCard(Container row, int slotNumber) {
        Container card = newBorderedCard(row);
        card.setPreferredSize(new Vector3f(TILE_WIDTH, TILE_HEIGHT, 0));

        Container chipRow = card.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        chipRow.setInsets(new Insets3f(8, 10, 4, 10));

        Container chip = chipRow.addChild(new Container());
        chip.setBackground(new QuadBackgroundComponent(Theme.PANEL_HOVER));
        chip.setPreferredSize(new Vector3f(22, 22, 0));
        Label chipLabel = chip.addChild(new Label(Integer.toString(slotNumber)));
        chipLabel.setFontSize(12);
        chipLabel.setColor(Theme.TEXT);
        chipLabel.setTextHAlignment(HAlignment.Center);
        chipLabel.setPreferredSize(new Vector3f(22, 22, 0));

        Label valueLabel = card.addChild(new Label(""));
        valueLabel.setFontSize(13);
        valueLabel.setInsets(new Insets3f(2, 10, 8, 10));
        return valueLabel;
    }

    private void refreshLabels(PlayerProfile profile) {
        creditsLabel.setText(I18n.t("loadout.credits", profile.getCurrency()));
        aiDifficultyLabel.setText(((PlayerContext) getApplication()).getGameSettings().getAiDifficulty().getDisplayName());

        paddleLabel.setText(nameOf(Catalog.findPaddle(profile.getEquippedId("paddle")), PaddleDefinition::getDisplayName));
        tableLabel.setText(nameOf(Catalog.findTable(profile.getEquippedId("table")), TableDefinition::getDisplayName));
        ballLabel.setText(nameOf(Catalog.findBall(profile.getEquippedId("ball")), BallDefinition::getDisplayName));
        refreshLevelButtons(profile);

        List<String> loadout = profile.getLoadout();
        for (int i = 0; i < powerUpLabels.length; i++) {
            String id = loadout.get(i);
            if (id.isEmpty()) {
                powerUpLabels[i].setText(I18n.t("loadout.empty_slot"));
                powerUpLabels[i].setColor(Theme.TEXT_DIM2);
            } else {
                Optional<PowerUpDefinition> def = Catalog.findPowerUp(id);
                powerUpLabels[i].setText(def.map(PowerUpDefinition::getDisplayName).orElse(I18n.t("loadout.unknown_powerup")).toUpperCase());
                powerUpLabels[i].setColor(Theme.TEXT);
            }
        }
    }

    private void cycleAiDifficulty(PlayerContext ctx, int direction) {
        AiDifficulty[] values = AiDifficulty.values();
        com.paddleshock.settings.GameSettings settings = ctx.getGameSettings();
        int nextIndex = Math.floorMod(settings.getAiDifficulty().ordinal() + direction, values.length);
        settings.setAiDifficulty(values[nextIndex]);
        ctx.saveGameSettings();
        aiDifficultyLabel.setText(settings.getAiDifficulty().getDisplayName());
    }

    private <T> String nameOf(Optional<T> item, Function<T, String> nameFn) {
        return item.map(nameFn).map(String::toUpperCase).orElse(I18n.t("loadout.none"));
    }

    private void refreshLevelButtons(PlayerProfile profile) {
        String equippedId = profile.getEquippedId("level");
        for (int i = 0; i < levelButtons.length; i++) {
            boolean equipped = Catalog.LEVELS.get(i).getId().equals(equippedId);
            String baseName = Catalog.LEVELS.get(i).getDisplayName().toUpperCase();
            levelButtons[i].setText(equipped ? "✓ " + baseName : baseName);
            levelButtons[i].setBackground(new QuadBackgroundComponent(equipped ? Theme.GREEN_DIM : Theme.PANEL_HOVER));
            levelButtons[i].setColor(equipped ? Theme.GREEN : Theme.TEXT);
        }
    }

    @Override
    protected void cleanup(Application application) {
        // uiRoot is detached in onDisable(); nothing else owns native resources here.
    }

    @Override
    protected void onEnable() {
        rebuild((Navigator) getApplication(), (PlayerContext) getApplication());
        ((SimpleApplication) getApplication()).getGuiNode().attachChild(uiRoot);
        getApplication().getInputManager().setCursorVisible(true);
    }

    @Override
    protected void onDisable() {
        uiRoot.removeFromParent();
    }
}

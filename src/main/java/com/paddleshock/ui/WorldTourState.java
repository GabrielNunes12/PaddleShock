package com.paddleshock.ui;

import java.util.Set;
import java.util.stream.Collectors;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.FillMode;
import com.simsilica.lemur.HAlignment;
import com.simsilica.lemur.Insets3f;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.VAlignment;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.component.SpringGridLayout;

import com.paddleshock.app.Navigator;
import com.paddleshock.app.PlayerContext;
import com.paddleshock.data.Catalog;
import com.paddleshock.data.ItemDefinition;
import com.paddleshock.data.LevelDefinition;
import com.paddleshock.i18n.I18n;
import com.paddleshock.tour.TourOpponent;
import com.paddleshock.tour.WorldTour;

/**
 * The World Tour ladder: one column per arena, three opponents each (the last is the boss), and
 * a detail panel for the selected opponent with START MATCH. Locked opponents are shown but not
 * selectable. Unlock/reward rules live in {@link WorldTour}; this class only lays them out.
 */
public class WorldTourState extends BaseAppState {

    private static final float ROW_HEIGHT = 58f;

    private final Node uiRoot = new Node("worldTourUi");

    /** The opponent shown in the detail panel; defaults to the next unbeaten one on open. */
    private TourOpponent selected;

    /** True while the gear store modal is open on top, so this screen's own buttons go inert. */
    private boolean modalOpen;

    @Override
    protected void initialize(Application application) {
        // Built fresh in rebuild() every time the screen is shown.
    }

    private void rebuild() {
        uiRoot.detachAllChildren();
        PlayerContext ctx = (PlayerContext) getApplication();
        Navigator nav = (Navigator) getApplication();
        Set<String> beaten = ctx.getProfile().getTourBeatenIds();

        SimpleApplication simpleApp = (SimpleApplication) getApplication();
        float screenW = simpleApp.getCamera().getWidth();
        float screenH = simpleApp.getCamera().getHeight();

        Container background = new Container();
        background.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));
        background.setPreferredSize(new Vector3f(screenW, screenH, 0));
        background.setLocalTranslation(0, screenH, 0);
        uiRoot.attachChild(background);

        float contentWidth = screenW * 0.9f;
        Container panel = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        panel.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));
        panel.setInsets(new Insets3f(20, 0, 20, 0));

        Container header = panel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y, FillMode.First, FillMode.None)));
        header.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));
        header.setInsets(new Insets3f(0, 0, 14, 0));
        Label title = header.addChild(new Label(I18n.t("tour.title")));
        title.setFontSize(28);
        title.setColor(Theme.ORANGE);
        Label progress = header.addChild(new Label(I18n.t("tour.progress", WorldTour.beatenCount(beaten), WorldTour.OPPONENTS.size())));
        progress.setFontSize(16);
        progress.setColor(Theme.TEXT_DIM);

        // Resolved before the rows are built - they read it to draw the selection frame.
        if (selected == null || !WorldTour.isUnlocked(beaten, selected)) {
            selected = WorldTour.nextOpponent(beaten).orElse(WorldTour.OPPONENTS.get(WorldTour.OPPONENTS.size() - 1));
        }

        float columnGap = 12f;
        float columnWidth = (contentWidth - columnGap * (Catalog.LEVELS.size() - 1)) / Catalog.LEVELS.size();
        Container columns = panel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        columns.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));
        for (int i = 0; i < Catalog.LEVELS.size(); i++) {
            addArenaColumn(columns, Catalog.LEVELS.get(i), beaten, columnWidth, i < Catalog.LEVELS.size() - 1 ? columnGap : 0);
        }

        addDetailPanel(panel, selected, beaten, contentWidth, nav, ctx);

        Vector3f size = panel.getPreferredSize();
        panel.setLocalTranslation((screenW - contentWidth) / 2f, (screenH + size.y) / 2f, 1);
        uiRoot.attachChild(panel);
    }

    private void addArenaColumn(Container columns, LevelDefinition level, Set<String> beaten, float width, float rightGap) {
        Container margin = columns.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        margin.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));
        margin.setInsets(new Insets3f(0, 0, 0, rightGap));

        Container column = margin.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.Even)));
        column.setBackground(new QuadBackgroundComponent(Theme.PANEL));
        column.setInsets(new Insets3f(12, 12, 12, 12));

        Container accent = column.addChild(new Container());
        accent.setBackground(new QuadBackgroundComponent(level.getAmbientTint()));
        accent.setPreferredSize(new Vector3f(width - 24, 4, 0));
        Label arenaName = column.addChild(new Label(level.getDisplayName().toUpperCase()));
        arenaName.setFontSize(16);
        arenaName.setColor(Theme.TEXT);
        arenaName.setInsets(new Insets3f(8, 0, 0, 0));
        Label quirk = column.addChild(new Label(level.getTagline()));
        quirk.setFontSize(11);
        quirk.setColor(Theme.TEXT_DIM);
        quirk.setInsets(new Insets3f(0, 0, 8, 0));

        for (TourOpponent opponent : WorldTour.forLevel(level.getId())) {
            addOpponentRow(column, opponent, beaten, width - 24);
        }
    }

    private void addOpponentRow(Container column, TourOpponent opponent, Set<String> beaten, float width) {
        boolean unlocked = WorldTour.isUnlocked(beaten, opponent);
        boolean isBeaten = beaten.contains(opponent.id());
        boolean isSelected = opponent.equals(selected);

        // Lemur draws a container's insets OUTSIDE its own background, so the frame is the
        // parent's background showing through the child's insets (orange = selected).
        Container gap = column.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        gap.setBackground(new QuadBackgroundComponent(Theme.PANEL));
        Container frame = gap.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        frame.setInsets(new Insets3f(6, 0, 0, 0));
        frame.setBackground(new QuadBackgroundComponent(isSelected ? Theme.ORANGE : Theme.PANEL_LINE));

        float frameWidth = isSelected ? 2 : 1;
        Container row = frame.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y, FillMode.Last, FillMode.None)));
        row.setBackground(new QuadBackgroundComponent(unlocked ? Theme.PANEL_HOVER : Theme.BACKGROUND_2));
        row.setInsets(new Insets3f(frameWidth, frameWidth, frameWidth, frameWidth));

        Container chipSlot = row.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.None)));
        chipSlot.setBackground(new QuadBackgroundComponent(unlocked ? Theme.PANEL_HOVER : Theme.BACKGROUND_2));
        Container chip = chipSlot.addChild(new Container());
        chip.setBackground(new QuadBackgroundComponent(unlocked ? opponent.paddleColor() : Theme.PANEL_LINE));
        // Preferred size includes insets, so the 14x14 swatch is padded out to center it.
        float chipTop = (ROW_HEIGHT - 14) / 2f - frameWidth;
        chip.setPreferredSize(new Vector3f(14 + 20, 14 + chipTop, 0));
        chip.setInsets(new Insets3f(chipTop, 10, 0, 10));

        String status = !unlocked ? I18n.t("tour.locked")
                : isBeaten ? I18n.t("tour.beaten")
                : I18n.t("tour.reward", opponent.firstWinReward());
        String name = unlocked ? opponent.name().toUpperCase() : "???";
        if (opponent.boss()) {
            name = I18n.t("tour.boss_prefix") + " " + name;
        }
        Button button = row.addChild(new Button(name + "\n" + status));
        button.setBackground(new QuadBackgroundComponent(unlocked ? Theme.PANEL_HOVER : Theme.BACKGROUND_2));
        button.setColor(!unlocked ? Theme.TEXT_DIM2 : isBeaten ? Theme.GREEN : opponent.boss() ? Theme.ORANGE : Theme.TEXT);
        button.setFontSize(13);
        button.setTextVAlignment(VAlignment.Center);
        if (unlocked) {
            button.addClickCommands(source -> {
                if (modalOpen) {
                    return;
                }
                ((PlayerContext) getApplication()).getAudioManager().playSfx("button_click.ogg");
                selected = opponent;
                rebuild();
            });
        }
        row.setPreferredSize(new Vector3f(width, ROW_HEIGHT, 0));
    }

    private void addDetailPanel(Container panel, TourOpponent opponent, Set<String> beaten, float width,
            Navigator nav, PlayerContext ctx) {
        Container margin = panel.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        margin.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));
        margin.setInsets(new Insets3f(14, 0, 0, 0));

        Container detail = margin.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y, FillMode.First, FillMode.None)));
        detail.setBackground(new QuadBackgroundComponent(Theme.PANEL));
        detail.setInsets(new Insets3f(14, 18, 14, 18));

        Container text = detail.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.Even)));
        text.setBackground(new QuadBackgroundComponent(Theme.PANEL));
        Label name = text.addChild(new Label(opponent.name().toUpperCase()));
        name.setFontSize(24);
        name.setColor(opponent.boss() ? Theme.ORANGE : Theme.TEXT);
        Label blurb = text.addChild(new Label(I18n.t(opponent.blurbKey())));
        blurb.setFontSize(13);
        blurb.setColor(Theme.TEXT_DIM);
        blurb.setInsets(new Insets3f(2, 0, 6, 0));

        String kit = opponent.powerUpIds().isEmpty() ? I18n.t("tour.kit_none")
                : opponent.powerUpIds().stream()
                        .map(id -> Catalog.findPowerUp(id).map(ItemDefinition::getDisplayName).orElse(id).toUpperCase())
                        .collect(Collectors.joining(", "));
        String reward = beaten.contains(opponent.id()) ? I18n.t("tour.detail_beaten")
                : I18n.t("tour.detail_reward", opponent.firstWinReward());
        Label facts = text.addChild(new Label(I18n.t("tour.detail_facts", opponent.winScore(), kit) + "   " + reward));
        facts.setFontSize(13);
        facts.setColor(Theme.TEXT);

        Container buttons = detail.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        buttons.setBackground(new QuadBackgroundComponent(Theme.PANEL));
        addButton(buttons, I18n.t("tour.start"), Theme.ORANGE, Theme.ON_ACCENT, () -> nav.startTourMatch(opponent), ctx);
        addButton(buttons, I18n.t("tour.gear"), Theme.PANEL_HOVER, Theme.TEXT, () -> {
            modalOpen = true;
            nav.showStoreModal(() -> {
                modalOpen = false;
                rebuild();
            });
        }, ctx);
        addButton(buttons, I18n.t("tour.back"), Theme.PANEL_HOVER, Theme.TEXT, nav::showMainMenu, ctx);

        detail.setPreferredSize(new Vector3f(width, detail.getPreferredSize().y, 0));
    }

    private void addButton(Container parent, String label, ColorRGBA bg, ColorRGBA fg, Runnable action, PlayerContext ctx) {
        Container margin = parent.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        margin.setBackground(new QuadBackgroundComponent(Theme.PANEL));
        margin.setInsets(new Insets3f(0, 0, 6, 0));
        Button button = margin.addChild(new Button(label));
        button.setBackground(new QuadBackgroundComponent(bg));
        button.setColor(fg);
        button.setFontSize(15);
        button.setTextHAlignment(HAlignment.Center);
        button.setTextVAlignment(VAlignment.Center);
        button.setPreferredSize(new Vector3f(220, 38, 0));
        button.addClickCommands(source -> {
            if (modalOpen) {
                return;
            }
            ctx.getAudioManager().playSfx("button_click.ogg");
            action.run();
        });
    }

    /** For tests/harnesses: which opponent the detail panel shows. */
    public TourOpponent getSelected() {
        return selected;
    }

    @Override
    public void update(float tpf) {
        // No per-frame work needed on this screen.
    }

    @Override
    protected void cleanup(Application application) {
        // uiRoot is detached in onDisable(); nothing else owns native resources here.
    }

    @Override
    protected void onEnable() {
        selected = null;
        modalOpen = false;
        rebuild();
        ((SimpleApplication) getApplication()).getGuiNode().attachChild(uiRoot);
        getApplication().getInputManager().setCursorVisible(true);
    }

    @Override
    protected void onDisable() {
        uiRoot.removeFromParent();
    }
}

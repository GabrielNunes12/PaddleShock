package com.paddleshock.ui;

import java.util.Set;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
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

import com.paddleshock.achievements.Achievement;
import com.paddleshock.achievements.AchievementTracker;
import com.paddleshock.app.PlayerContext;
import com.paddleshock.data.PlayerProfile;
import com.paddleshock.i18n.I18n;

/**
 * Lists every {@link Achievement} as a card - unlocked ones highlighted, locked ones dimmed with
 * their progress where there's a counter (e.g. "12 / 25"). Reached from the PROFILE screen.
 */
public class AchievementsState extends BaseAppState {

    private static final int COLUMNS = 3;
    private static final float CARD_HEIGHT = 84f;

    private final Node uiRoot = new Node("achievementsUi");

    private Runnable backAction = () -> {
    };

    public void setBackAction(Runnable backAction) {
        this.backAction = backAction;
    }

    @Override
    protected void initialize(Application application) {
        // Built fresh in rebuild() every time the screen is shown.
    }

    private void rebuild() {
        uiRoot.detachAllChildren();
        PlayerContext ctx = (PlayerContext) getApplication();
        PlayerProfile profile = ctx.getProfile();
        Set<String> unlocked = profile.getUnlockedAchievements();

        SimpleApplication simpleApp = (SimpleApplication) getApplication();
        float screenW = simpleApp.getCamera().getWidth();
        float screenH = simpleApp.getCamera().getHeight();

        Container background = new Container();
        background.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));
        background.setPreferredSize(new Vector3f(screenW, screenH, 0));
        background.setLocalTranslation(0, screenH, 0);
        uiRoot.attachChild(background);

        float contentWidth = screenW * 0.88f;
        Container panel = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        panel.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));

        Container header = panel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y, FillMode.First, FillMode.None)));
        header.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));
        header.setInsets(new Insets3f(0, 0, 14, 0));
        Label title = header.addChild(new Label(I18n.t("achievements.title")));
        title.setFontSize(28);
        title.setColor(Theme.ORANGE);
        long count = java.util.Arrays.stream(Achievement.values()).filter(a -> unlocked.contains(a.name())).count();
        Label progress = header.addChild(new Label(I18n.t("achievements.progress", count, Achievement.values().length)));
        progress.setFontSize(16);
        progress.setColor(Theme.TEXT_DIM);

        float gap = 10f;
        float cardWidth = (contentWidth - gap * (COLUMNS - 1)) / COLUMNS;
        Container grid = panel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        grid.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));
        Achievement[] all = Achievement.values();
        int perColumn = (all.length + COLUMNS - 1) / COLUMNS;
        for (int c = 0; c < COLUMNS; c++) {
            Container column = grid.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.Even)));
            column.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));
            column.setInsets(new Insets3f(0, 0, 0, c < COLUMNS - 1 ? gap : 0));
            for (int i = c * perColumn; i < Math.min(all.length, (c + 1) * perColumn); i++) {
                addCard(column, all[i], unlocked.contains(all[i].name()), profile, cardWidth);
            }
        }

        Container footer = panel.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        footer.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));
        footer.setInsets(new Insets3f(16, 0, 0, 0));
        Button back = footer.addChild(new Button(I18n.t("achievements.back")));
        back.setBackground(new QuadBackgroundComponent(Theme.ORANGE));
        back.setColor(Theme.ON_ACCENT);
        back.setFontSize(16);
        back.setTextHAlignment(HAlignment.Center);
        back.setTextVAlignment(VAlignment.Center);
        back.setPreferredSize(new Vector3f(300, 44, 0));
        back.addClickCommands(source -> {
            ctx.getAudioManager().playSfx("button_click.ogg");
            setEnabled(false);
            backAction.run();
        });

        Vector3f size = panel.getPreferredSize();
        panel.setLocalTranslation((screenW - contentWidth) / 2f, (screenH + size.y) / 2f, 1);
        uiRoot.attachChild(panel);
    }

    private void addCard(Container column, Achievement achievement, boolean isUnlocked, PlayerProfile profile, float width) {
        Container gap = column.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        gap.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));
        // Frame = the parent's background showing through the child's insets (Lemur draws insets
        // outside a container's own background).
        Container frame = gap.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        frame.setInsets(new Insets3f(0, 0, 8, 0));
        frame.setBackground(new QuadBackgroundComponent(isUnlocked ? Theme.GREEN : Theme.PANEL_LINE));
        Container card = frame.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.Even)));
        card.setBackground(new QuadBackgroundComponent(isUnlocked ? Theme.GREEN_DIM : Theme.PANEL));
        card.setInsets(new Insets3f(1, isUnlocked ? 4 : 1, 1, 1));

        Container content = card.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.Even)));
        content.setBackground(new QuadBackgroundComponent(isUnlocked ? Theme.GREEN_DIM : Theme.PANEL));
        content.setInsets(new Insets3f(10, 12, 8, 12));
        Label title = content.addChild(new Label(I18n.t(achievement.titleKey())));
        title.setFontSize(15);
        title.setColor(isUnlocked ? Theme.TEXT : Theme.TEXT_DIM);
        Label desc = content.addChild(new Label(I18n.t(achievement.descKey())));
        desc.setFontSize(12);
        desc.setColor(isUnlocked ? Theme.TEXT : Theme.TEXT_DIM2);
        String status = isUnlocked ? I18n.t("achievements.unlocked")
                : AchievementTracker.progress(profile, achievement).orElse(I18n.t("achievements.locked"));
        Label statusLabel = content.addChild(new Label(status));
        statusLabel.setFontSize(12);
        statusLabel.setColor(isUnlocked ? Theme.GREEN : Theme.TEXT_DIM);
        statusLabel.setInsets(new Insets3f(4, 0, 0, 0));

        card.setPreferredSize(new Vector3f(width, CARD_HEIGHT, 0));
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
        rebuild();
        ((SimpleApplication) getApplication()).getGuiNode().attachChild(uiRoot);
        getApplication().getInputManager().setCursorVisible(true);
    }

    @Override
    protected void onDisable() {
        uiRoot.removeFromParent();
    }
}

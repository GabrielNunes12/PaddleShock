package com.paddleshock.ui;

import java.util.List;

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

import com.paddleshock.app.PlayerContext;
import com.paddleshock.data.Credits;
import com.paddleshock.i18n.I18n;

/**
 * The CREDITS screen, reachable from the main menu: attribution for every third-party asset and
 * library shipped in the build (required by the CC-BY model/music licenses). Content comes from
 * {@link Credits}; this class only lays it out - two bordered cards side by side, 3D models on the
 * left and everything else on the right, matching {@link HowToPlayState}'s card style.
 */
public class CreditsState extends BaseAppState {

    private final Node uiRoot = new Node("creditsUi");

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

        SimpleApplication simpleApp = (SimpleApplication) getApplication();
        float screenW = simpleApp.getCamera().getWidth();
        float screenH = simpleApp.getCamera().getHeight();

        Container background = new Container();
        background.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));
        background.setPreferredSize(new Vector3f(screenW, screenH, 0));
        background.setLocalTranslation(0, screenH, 0);
        uiRoot.attachChild(background);

        Container panel = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        // Explicit background: a bare Container otherwise picks up Lemur's default gradient style.
        panel.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));
        panel.setInsets(new Insets3f(24, 32, 24, 32));

        Label title = panel.addChild(new Label(I18n.t("credits.title")));
        title.setFontSize(28);
        title.setColor(Theme.ORANGE);
        title.setInsets(new Insets3f(0, 0, 16, 0));

        float gridWidth = screenW * 0.86f;
        float leftWidth = gridWidth * 0.4f;
        float rightWidth = gridWidth - leftWidth - 12;

        Container columns = panel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        columns.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));
        addCard(columns, List.of(Credits.modelsSection()), leftWidth, 12);
        addCard(columns, Credits.otherSections(), rightWidth, 0);

        // The top gap lives on a wrapper, not the button's own insets - insets count against the
        // button's preferred size and would squash it.
        Container footer = panel.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        footer.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));
        footer.setInsets(new Insets3f(20, 0, 0, 0));
        Button back = footer.addChild(new Button(I18n.t("credits.back")));
        back.setTextHAlignment(HAlignment.Center);
        back.setTextVAlignment(VAlignment.Center);
        back.setBackground(new QuadBackgroundComponent(Theme.ORANGE));
        back.setColor(Theme.ON_ACCENT);
        back.setFontSize(16);
        back.setPreferredSize(new Vector3f(300, 46, 0));
        back.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            setEnabled(false);
            backAction.run();
        });

        Vector3f panelSize = panel.getPreferredSize();
        panel.setLocalTranslation((screenW - panelSize.x) / 2f, (screenH + panelSize.y) / 2f, 1);
        uiRoot.attachChild(panel);
    }

    /** One {@link Theme#PANEL_LINE}-bordered, {@link Theme#PANEL}-filled card holding the given
     *  sections top to bottom, each a {@link Theme#BLUE} heading followed by its lines. */
    private void addCard(Container row, List<Credits.Section> sections, float cardWidth, float rightGap) {
        Container rowMargin = row.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        rowMargin.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));
        rowMargin.setInsets(new Insets3f(0, 0, 0, rightGap));

        Container border = rowMargin.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        border.setBackground(new QuadBackgroundComponent(Theme.PANEL_LINE));
        border.setInsets(new Insets3f(1, 1, 1, 1));

        // FillMode.None: the shorter card is stretched to match the taller one's height, and the
        // default fill would spread that extra height between the lines instead of below them.
        Container card = border.addChild(new Container(
                new SpringGridLayout(Axis.Y, Axis.X, FillMode.None, FillMode.Even)));
        card.setBackground(new QuadBackgroundComponent(Theme.PANEL));
        card.setInsets(new Insets3f(14, 16, 14, 16));

        for (int s = 0; s < sections.size(); s++) {
            Credits.Section section = sections.get(s);
            Label heading = card.addChild(new Label(I18n.t(section.headingKey())));
            heading.setFontSize(14);
            heading.setColor(Theme.BLUE);
            heading.setInsets(new Insets3f(s == 0 ? 0 : 12, 0, 4, 0));

            for (Credits.Line line : section.lines()) {
                Label label = card.addChild(new Label(line.text()));
                label.setFontSize(13);
                label.setColor(line.dim() ? Theme.TEXT_DIM : Theme.TEXT);
                label.setInsets(new Insets3f(0, 0, 2, 0));
            }
        }

        card.setPreferredSize(new Vector3f(cardWidth - 2, card.getPreferredSize().y, 0));
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
        rebuild((PlayerContext) getApplication());
        ((SimpleApplication) getApplication()).getGuiNode().attachChild(uiRoot);
        getApplication().getInputManager().setCursorVisible(true);
    }

    @Override
    protected void onDisable() {
        uiRoot.removeFromParent();
    }
}

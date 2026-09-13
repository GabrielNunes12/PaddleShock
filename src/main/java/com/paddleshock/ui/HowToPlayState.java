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

/**
 * A single "HOW TO PLAY" screen: covers paddle movement, gamepad support, and power-up keys for a
 * brand-new player. Shown automatically once, right after the splash screen, on a save that has
 * never seen it ({@link com.paddleshock.data.PlayerProfile#hasSeenTutorial()}); also reachable any
 * time afterward from the main menu. Viewing it (either way) marks the profile as having seen it,
 * via {@link PaddleShockApp#showHowToPlay}.
 */
public class HowToPlayState extends BaseAppState {

    private final Node uiRoot = new Node("howToPlayUi");

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

        Label title = panel.addChild(new Label("HOW TO PLAY"));
        title.setFontSize(28);
        title.setColor(Theme.ORANGE);
        title.setInsets(new Insets3f(0, 0, 16, 0));

        addSection(panel, "MOVE YOUR PADDLE", new String[] {
                "Move the mouse to slide your paddle across the table - your paddle follows",
                "your cursor's motion. A connected gamepad's left stick works too."});
        addSection(panel, "AIM YOUR SHOTS", new String[] {
                "Where you're moving when the ball arrives changes the angle of your return -",
                "a moving paddle redirects the ball, a still one just bounces it back."});
        addSection(panel, "POWER-UPS", new String[] {
                "Keys 1, 2 and 3 activate the power-ups in your loadout (set them up from the",
                "STORE or MATCH SETUP screen). Each one goes on cooldown after use - watch the",
                "HUD boxes in the top-left during a match."});
        addSection(panel, "PAUSE", new String[] {"Press Esc any time during a match to pause."});

        Button back = panel.addChild(new Button("GOT IT"));
        back.setInsets(new Insets3f(16, 0, 0, 0));
        back.setBackground(new QuadBackgroundComponent(Theme.ORANGE));
        back.setColor(Theme.ON_ACCENT);
        back.setFontSize(16);
        back.setPreferredSize(new Vector3f(300, 46, 0));
        back.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            app.getProfile().setHasSeenTutorial(true);
            app.saveProfile();
            setEnabled(false);
            backAction.run();
        });

        Vector3f panelSize = panel.getPreferredSize();
        panel.setLocalTranslation((screenW - panelSize.x) / 2f, (screenH + panelSize.y) / 2f, 1);
        uiRoot.attachChild(panel);
    }

    private void addSection(Container panel, String heading, String[] bodyLines) {
        Label headingLabel = panel.addChild(new Label(heading));
        headingLabel.setFontSize(14);
        headingLabel.setColor(Theme.BLUE);
        headingLabel.setInsets(new Insets3f(8, 0, 2, 0));

        for (int i = 0; i < bodyLines.length; i++) {
            Label bodyLabel = panel.addChild(new Label(bodyLines[i]));
            bodyLabel.setFontSize(13);
            bodyLabel.setColor(Theme.TEXT);
            bodyLabel.setInsets(new Insets3f(0, 0, i == bodyLines.length - 1 ? 6 : 0, 0));
        }
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
        rebuild((PaddleShockApp) getApplication());
        ((SimpleApplication) getApplication()).getGuiNode().attachChild(uiRoot);
        getApplication().getInputManager().setCursorVisible(true);
    }

    @Override
    protected void onDisable() {
        uiRoot.removeFromParent();
    }
}

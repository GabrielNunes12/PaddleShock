package com.paddleshock.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.Insets3f;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.component.SpringGridLayout;

/**
 * Small timed notifications stacked in the top-right corner, drawn above every other screen
 * (e.g. "ACHIEVEMENT UNLOCKED"). Always attached and enabled; anything can call
 * {@link #show} from the render thread. At most {@link #MAX_VISIBLE} are on screen at once; the
 * rest wait their turn.
 */
public class ToastState extends BaseAppState {

    private static final float SECONDS_VISIBLE = 4f;
    private static final int MAX_VISIBLE = 3;
    private static final float WIDTH = 340f;
    /** Above every screen's own UI (those sit at z 0-2). */
    private static final float Z = 50f;

    private record Toast(String heading, String title, String body) {
    }

    private static final class Shown {
        final Container panel;
        float remaining = SECONDS_VISIBLE;

        Shown(Container panel) {
            this.panel = panel;
        }
    }

    private final Node uiRoot = new Node("toastUi");
    private final Deque<Toast> queue = new ArrayDeque<>();
    private final List<Shown> shown = new ArrayList<>();

    /** Queues a toast: a small accent heading, a bold title, and an optional dim body line. */
    public void show(String heading, String title, String body) {
        queue.add(new Toast(heading, title, body));
    }

    @Override
    protected void initialize(Application application) {
        // Nothing to build until a toast is shown.
    }

    @Override
    public void update(float tpf) {
        boolean changed = false;
        for (int i = shown.size() - 1; i >= 0; i--) {
            Shown s = shown.get(i);
            s.remaining -= tpf;
            if (s.remaining <= 0f) {
                s.panel.removeFromParent();
                shown.remove(i);
                changed = true;
            }
        }
        while (shown.size() < MAX_VISIBLE && !queue.isEmpty()) {
            Container panel = build(queue.poll());
            uiRoot.attachChild(panel);
            shown.add(new Shown(panel));
            changed = true;
        }
        if (changed) {
            layout();
        }
    }

    private Container build(Toast toast) {
        Container frame = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        frame.setBackground(new QuadBackgroundComponent(Theme.ORANGE));
        Container panel = frame.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        panel.setBackground(new QuadBackgroundComponent(Theme.PANEL));
        panel.setInsets(new Insets3f(0, 4, 0, 0));

        Container body = panel.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        body.setBackground(new QuadBackgroundComponent(Theme.PANEL));
        body.setInsets(new Insets3f(10, 14, 10, 14));
        Label heading = body.addChild(new Label(toast.heading()));
        heading.setFontSize(11);
        heading.setColor(Theme.ORANGE);
        Label title = body.addChild(new Label(toast.title()));
        title.setFontSize(17);
        title.setColor(Theme.TEXT);
        if (toast.body() != null && !toast.body().isBlank()) {
            Label text = body.addChild(new Label(toast.body()));
            text.setFontSize(12);
            text.setColor(Theme.TEXT_DIM);
        }
        frame.setPreferredSize(new Vector3f(WIDTH, frame.getPreferredSize().y, 0));
        return frame;
    }

    private void layout() {
        SimpleApplication app = (SimpleApplication) getApplication();
        float x = app.getCamera().getWidth() - WIDTH - 20;
        float y = app.getCamera().getHeight() - 20;
        for (Shown s : shown) {
            s.panel.setLocalTranslation(x, y, Z);
            y -= s.panel.getPreferredSize().y + 10;
        }
    }

    @Override
    protected void cleanup(Application application) {
        uiRoot.removeFromParent();
    }

    @Override
    protected void onEnable() {
        ((SimpleApplication) getApplication()).getGuiNode().attachChild(uiRoot);
    }

    @Override
    protected void onDisable() {
        uiRoot.removeFromParent();
    }
}

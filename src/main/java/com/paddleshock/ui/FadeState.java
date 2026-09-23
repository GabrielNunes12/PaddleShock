package com.paddleshock.ui;

import java.util.List;
import java.util.function.BooleanSupplier;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Quad;

/**
 * A quick fade-in whenever the visible screen changes (menu -> store, match -> results...):
 * watches which of the given screens are enabled and drives a full-screen overlay from
 * {@link ScreenFade}. Sits above screens but below toasts. Always enabled.
 */
public class FadeState extends BaseAppState {

    /** Above every screen's own UI (z 0-2), below {@link ToastState}'s z 50. */
    private static final float Z = 40f;

    private final List<BooleanSupplier> screens;
    private final ScreenFade fade = new ScreenFade();
    private Geometry overlay;
    private Material material;
    private final ColorRGBA color = Theme.BACKGROUND.clone();

    /** {@code screens}: one "is this screen showing?" check per screen to watch. */
    public FadeState(List<BooleanSupplier> screens) {
        this.screens = screens;
    }

    @Override
    protected void initialize(Application application) {
        SimpleApplication app = (SimpleApplication) application;
        overlay = new Geometry("screenFade", new Quad(app.getCamera().getWidth(), app.getCamera().getHeight()));
        material = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        material.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        overlay.setMaterial(material);
        overlay.setLocalTranslation(0f, 0f, Z);
    }

    @Override
    public void update(float tpf) {
        StringBuilder signature = new StringBuilder();
        for (BooleanSupplier screen : screens) {
            signature.append(screen.getAsBoolean() ? '1' : '0');
        }
        float alpha = fade.update(signature.toString(), tpf);
        color.a = alpha;
        material.setColor("Color", color);
        overlay.setCullHint(alpha > 0.005f ? Spatial.CullHint.Never : Spatial.CullHint.Always);
    }

    @Override
    protected void cleanup(Application application) {
        overlay.removeFromParent();
    }

    @Override
    protected void onEnable() {
        ((SimpleApplication) getApplication()).getGuiNode().attachChild(overlay);
    }

    @Override
    protected void onDisable() {
        overlay.removeFromParent();
    }
}

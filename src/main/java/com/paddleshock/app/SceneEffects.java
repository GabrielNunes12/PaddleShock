package com.paddleshock.app;

import com.jme3.asset.AssetManager;
import com.jme3.light.DirectionalLight;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.filters.BloomFilter;
import com.jme3.post.ssao.SSAOFilter;
import com.jme3.renderer.ViewPort;
import com.jme3.shadow.DirectionalLightShadowFilter;
import com.jme3.shadow.EdgeFilteringMode;

import com.paddleshock.settings.GraphicsProfile;

/**
 * The full-screen rendering effects for a match: sun shadows, ambient occlusion and bloom, as
 * chosen by a {@link GraphicsProfile}. Attached to the main viewport for the match's lifetime and
 * removed again by {@link #detach} (menus are GUI-only and don't need it). LOW quality attaches
 * nothing at all.
 */
final class SceneEffects {

    private final ViewPort viewPort;
    private final FilterPostProcessor processor;

    private SceneEffects(ViewPort viewPort, FilterPostProcessor processor) {
        this.viewPort = viewPort;
        this.processor = processor;
    }

    static SceneEffects attach(AssetManager assetManager, ViewPort viewPort, DirectionalLight sun,
            GraphicsProfile profile, int msaaSamples) {
        if (!profile.needsPostProcessing()) {
            return new SceneEffects(viewPort, null);
        }
        FilterPostProcessor processor = new FilterPostProcessor(assetManager);
        if (msaaSamples > 0) {
            processor.setNumSamples(msaaSamples);
        }
        if (profile.shadows()) {
            DirectionalLightShadowFilter shadows = new DirectionalLightShadowFilter(assetManager, profile.shadowMapSize(), 3);
            shadows.setLight(sun);
            shadows.setShadowIntensity(0.55f);
            shadows.setEdgeFilteringMode(EdgeFilteringMode.PCF4);
            shadows.setLambda(0.6f);
            processor.addFilter(shadows);
        }
        if (profile.ambientOcclusion()) {
            // sampleRadius, intensity, scale, bias - tuned for the table-sized scene (~20 units).
            processor.addFilter(new SSAOFilter(2.2f, 1.6f, 0.25f, 0.1f));
        }
        if (profile.bloom()) {
            BloomFilter bloom = new BloomFilter(BloomFilter.GlowMode.Objects);
            bloom.setBloomIntensity(1.6f);
            bloom.setBlurScale(1.4f);
            processor.addFilter(bloom);
        }
        viewPort.addProcessor(processor);
        return new SceneEffects(viewPort, processor);
    }

    void detach() {
        if (processor != null) {
            viewPort.removeProcessor(processor);
        }
    }
}

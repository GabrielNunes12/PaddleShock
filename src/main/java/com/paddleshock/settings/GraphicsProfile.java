package com.paddleshock.settings;

/**
 * Which rendering effects a {@link VideoQuality} turns on - see docs/specs/09-visual-polish.md.
 * Pure data so the "never more effects at a lower quality" rule is unit-testable.
 *
 * @param shadowMapSize directional shadow map resolution; 0 = no real-time shadows
 * @param ambientOcclusion screen-space ambient occlusion
 * @param bloom glow around objects with a glow color (shields, bumper rings, trails...)
 * @param blobShadow a cheap soft disc under the ball, for depth when real shadows are off
 */
public record GraphicsProfile(int shadowMapSize, boolean ambientOcclusion, boolean bloom, boolean blobShadow) {

    public static GraphicsProfile of(VideoQuality quality) {
        return switch (quality) {
            case LOW -> new GraphicsProfile(0, false, false, true);
            case MEDIUM -> new GraphicsProfile(1024, false, false, false);
            case HIGH -> new GraphicsProfile(2048, true, true, false);
            case ULTRA -> new GraphicsProfile(4096, true, true, false);
        };
    }

    public boolean shadows() {
        return shadowMapSize > 0;
    }

    /** True when any full-screen filter is needed at all (LOW needs none). */
    public boolean needsPostProcessing() {
        return shadows() || ambientOcclusion || bloom;
    }
}

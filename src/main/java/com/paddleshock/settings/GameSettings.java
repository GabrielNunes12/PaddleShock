package com.paddleshock.settings;

/** User-adjustable options, persisted between sessions. */
public class GameSettings {

    /** Bump when GameSettings's schema changes in a way that needs migration. */
    public static final int CURRENT_VERSION = 1;

    // Left at 0 (not CURRENT_VERSION) by Gson for saves that predate this field entirely, so
    // migrateIfNeeded() can tell "old save, never versioned" apart from "explicitly version N".
    private int saveVersion = CURRENT_VERSION;

    private float mouseSensitivity = 1.0f;
    private float brightness = 1.0f;
    private float soundVolume = 0.8f;
    private float musicVolume = 0.6f;
    private VideoQuality videoQuality = VideoQuality.MEDIUM;
    private boolean fullscreen = false;
    private Resolution resolution = Resolution.R_1280x720;

    public int getSaveVersion() {
        return saveVersion;
    }

    /**
     * Brings a freshly-deserialized settings object up to {@link #CURRENT_VERSION}. See
     * {@link com.paddleshock.data.PlayerProfile#migrateIfNeeded()} for the same mechanism and
     * the reasoning behind the saveVersion==0 and downgrade handling.
     */
    public void migrateIfNeeded() {
        int fromVersion = saveVersion == 0 ? 1 : saveVersion;

        if (fromVersion > CURRENT_VERSION) {
            System.err.println("GameSettings save is version " + fromVersion
                    + ", newer than this build's CURRENT_VERSION (" + CURRENT_VERSION
                    + "). Proceeding best-effort; some fields may be ignored.");
            return;
        }

        // Migration steps go here as the schema evolves.

        saveVersion = CURRENT_VERSION;
    }

    public float getMouseSensitivity() {
        return mouseSensitivity;
    }

    public void setMouseSensitivity(float mouseSensitivity) {
        this.mouseSensitivity = clamp(mouseSensitivity, 0.1f, 5.0f);
    }

    public float getBrightness() {
        return brightness;
    }

    public void setBrightness(float brightness) {
        this.brightness = clamp(brightness, 0.3f, 2.0f);
    }

    public float getSoundVolume() {
        return soundVolume;
    }

    public void setSoundVolume(float soundVolume) {
        this.soundVolume = clamp(soundVolume, 0f, 1f);
    }

    public float getMusicVolume() {
        return musicVolume;
    }

    public void setMusicVolume(float musicVolume) {
        this.musicVolume = clamp(musicVolume, 0f, 1f);
    }

    public VideoQuality getVideoQuality() {
        return videoQuality;
    }

    public void setVideoQuality(VideoQuality videoQuality) {
        this.videoQuality = videoQuality;
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    public void setFullscreen(boolean fullscreen) {
        this.fullscreen = fullscreen;
    }

    public Resolution getResolution() {
        return resolution;
    }

    public void setResolution(Resolution resolution) {
        this.resolution = resolution;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}

package com.paddleshock.settings;

/** User-adjustable options, persisted between sessions. */
public class GameSettings {

    private float mouseSensitivity = 1.0f;
    private float brightness = 1.0f;
    private float soundVolume = 0.8f;
    private VideoQuality videoQuality = VideoQuality.MEDIUM;
    private boolean fullscreen = false;

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

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}

package com.paddleshock.settings;

/** Windowed-mode resolution presets. Fullscreen always uses the desktop's native resolution. */
public enum Resolution {
    R_1280x720(1280, 720),
    R_1280x800(1280, 800),
    R_1600x900(1600, 900),
    R_1920x1080(1920, 1080),
    R_2560x1440(2560, 1440);

    private final int width;
    private final int height;

    Resolution(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    @Override
    public String toString() {
        return width + "x" + height;
    }
}

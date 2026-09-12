package com.paddleshock.settings;

public enum VideoQuality {
    LOW(0),
    MEDIUM(2),
    HIGH(4),
    ULTRA(8);

    private final int samples;

    VideoQuality(int samples) {
        this.samples = samples;
    }

    /** Antialiasing sample count applied via AppSettings. */
    public int getSamples() {
        return samples;
    }
}

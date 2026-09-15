package com.paddleshock.entities;

/** A real ball mesh used instead of the plain procedural sphere, per catalog item. */
public enum BallModel {
    CLASSIC("Models/Ball/classic.glb", true),
    /** Beach ball ships its own colorful panel materials; don't override them with a tint. */
    BEACH("Models/Ball/beach.glb", false),
    PELLET("Models/Ball/pellet.glb", true),
    /** No good real-model candidate found yet; use a plain sphere. */
    NONE(null, true);

    private final String path;
    private final boolean tinted;

    BallModel(String path, boolean tinted) {
        this.path = path;
        this.tinted = tinted;
    }

    public String getPath() {
        return path;
    }

    public boolean isTinted() {
        return tinted;
    }
}

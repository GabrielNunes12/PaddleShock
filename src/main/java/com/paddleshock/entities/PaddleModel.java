package com.paddleshock.entities;

/** A real paddle mesh per catalog item, mirroring {@link BallModel}. Every variant shares the
 *  same non-centered origin convention (handle bottom at local y ~0.1) as the base classic mesh,
 *  so all of them drop in at {@code Paddle.MODEL_SCALE} unchanged and pivot identically. */
public enum PaddleModel {
    CLASSIC("Models/Paddle/paddle.glb"),
    TURBO("Models/Paddle/paddle_turbo.glb"),
    WALL("Models/Paddle/paddle_wall.glb");

    private final String path;

    PaddleModel(String path) {
        this.path = path;
    }

    public String getPath() {
        return path;
    }
}

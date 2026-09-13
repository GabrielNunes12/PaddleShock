package com.paddleshock;

/**
 * Tunable prototype values. Later these become per-item stats
 * (paddle/table/ball properties) driven by the store.
 */
public final class GameConstants {

    private GameConstants() {
    }

    // Table surface: X is left/right, Z is the long axis (player <-> opponent).
    public static final float TABLE_HALF_WIDTH = 5f;
    public static final float TABLE_HALF_LENGTH = 8f;
    public static final float TABLE_SURFACE_Y = 0f;

    // Paddle
    public static final float PADDLE_RADIUS = 0.6f;
    public static final float PADDLE_HEIGHT = 0.3f;
    public static final float MOUSE_SENSITIVITY = 0.2f;
    public static final float PADDLE_PLAYER_Z = -6.5f;
    public static final float PADDLE_OPPONENT_Z = 6.5f;
    public static final float PADDLE_Z_RANGE = 2.5f;

    // Ball
    public static final float BALL_RADIUS = 0.35f;
    public static final float BALL_BASE_SPEED = 7f;
    public static final float BALL_MAX_SPEED = 16f;
    public static final float BALL_SPEED_RAMP = 1.03f;

    // Match: first to WIN_SCORE takes the match; winner earns a random credit reward.
    public static final int WIN_SCORE = 10;
    public static final int MATCH_REWARD_MIN = 10;
    public static final int MATCH_REWARD_MAX = 15;
}

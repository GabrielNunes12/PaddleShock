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
    public static final float GAMEPAD_DEADZONE = 0.2f;
    public static final float GAMEPAD_MOVE_SPEED = 6f;
    public static final float PADDLE_PLAYER_Z = -6.5f;
    public static final float PADDLE_OPPONENT_Z = 6.5f;
    public static final float PADDLE_Z_RANGE = 2.5f;

    // Ball
    public static final float BALL_RADIUS = 0.35f;
    public static final float BALL_BASE_SPEED = 7f;
    public static final float BALL_MAX_SPEED = 16f;
    public static final float BALL_SPEED_RAMP = 1.03f;

    // Ball vertical bounce: gravity pulls it down, it hops off the table between paddle hits,
    // losing a bit of height each bounce (base restitution), and gets popped back up on every
    // paddle hit. A table's own restitutionMultiplier scales how bouncy its hops decay.
    public static final float BALL_GRAVITY = 22f;
    public static final float BALL_SERVE_POP = 4f;
    public static final float BALL_BOUNCE_BASE_RESTITUTION = 0.80f;
    public static final float BALL_BOUNCE_MAX_RESTITUTION = 0.97f;
    public static final float BALL_BOUNCE_SETTLE_SPEED = 0.5f;

    // Spin (see docs/specs/03-spin.md): a paddle's sideways speed at contact (world units/s)
    // becomes spin, which bends the ball's sideways velocity while it flies and decays over time.
    public static final float SPIN_PER_PADDLE_SPEED = 0.12f;
    public static final float SPIN_MAX = 2f;
    /** Sideways acceleration (units/s^2) per unit of spin. */
    public static final float SPIN_CURVE_ACCEL = 4f;
    /** Fraction of spin lost per second. */
    public static final float SPIN_DECAY_PER_SECOND = 0.8f;
    /** How fast the ball model visibly rotates per unit of spin (radians/s) - cosmetic only. */
    public static final float SPIN_VISUAL_RATE = 9f;

    /** Ghost Ball: the ball is hidden from the target while |z| is below this (mid-table band). */
    public static final float GHOST_ZONE_HALF_DEPTH = 3.5f;
    /** Curveball on a still-paddle hit: below this swipe speed, curve away from the opponent instead. */
    public static final float CURVEBALL_MIN_SWIPE_SPEED = 0.5f;
    public static final float PADDLE_POP_BASE = 4f;
    public static final float PADDLE_POP_SPEED_FACTOR = 0.15f;
    public static final float PADDLE_REACH_HEIGHT = 2.0f;

    // Match: first to WIN_SCORE takes the match; winner earns a random credit reward.
    public static final int WIN_SCORE = 10;
    public static final int MATCH_REWARD_MIN = 10;
    public static final int MATCH_REWARD_MAX = 15;

    // LAN multiplayer: the port a host offers to bind by default in the MULTIPLAYER screen (the
    // player can still hand-edit it before hosting, since it's just a suggested free UDP port).
    public static final int MULTIPLAYER_DEFAULT_PORT = 55123;
}

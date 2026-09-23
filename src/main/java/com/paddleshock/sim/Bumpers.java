package com.paddleshock.sim;

import com.jme3.math.Vector3f;

import com.paddleshock.GameConstants;
import com.paddleshock.entities.Ball;

/**
 * Pinball Palace's two bumpers: round posts at z = -{@link #Z} and +{@link #Z} that slide side to
 * side in mirror image (the near one at x = offset, the far one at x = -offset), so neither end is
 * ever favored. A ball low enough to touch one rebounds off it, a little faster; a higher ball
 * sails over. Pure math over {@link Ball} state - no scene graph.
 */
public final class Bumpers {

    public static final float Z = 3f;
    public static final float RADIUS = 0.7f;
    /** Balls whose bottom is below this height hit a bumper; higher ones pass over it. */
    public static final float HEIGHT = 1.1f;
    public static final float TRAVEL = 3f;
    /** Radians per second of the side-to-side slide. */
    public static final float RATE = 0.9f;
    public static final float SPEED_BOOST = 1.08f;

    private Bumpers() {
    }

    /** The near bumper's x at {@code time} seconds into the match; the far one is at the negation. */
    public static float offsetAt(float time) {
        return TRAVEL * (float) Math.sin(time * RATE);
    }

    /** Rebounds the ball off whichever bumper it's touching (moving into), if any, given the
     *  near bumper's current x. Returns whether it hit one. */
    public static boolean collide(Ball ball, float offset) {
        return collideOne(ball, offset, -Z) || collideOne(ball, -offset, Z);
    }

    private static boolean collideOne(Ball ball, float bumperX, float bumperZ) {
        Vector3f pos = ball.getPosition();
        if (pos.y - ball.getRadius() > HEIGHT) {
            return false;
        }
        float dx = pos.x - bumperX;
        float dz = pos.z - bumperZ;
        float distance = (float) Math.sqrt(dx * dx + dz * dz);
        float minDistance = RADIUS + ball.getRadius();
        if (distance >= minDistance || distance < 1e-4f) {
            return false;
        }
        float nx = dx / distance;
        float nz = dz / distance;
        Vector3f velocity = ball.getVelocity();
        float along = velocity.x * nx + velocity.z * nz;
        if (along >= 0f) {
            // Already moving away (e.g. the bumper slid into it after a bounce) - just don't trap it.
            return false;
        }
        float vx = (velocity.x - 2f * along * nx) * SPEED_BOOST;
        float vz = (velocity.z - 2f * along * nz) * SPEED_BOOST;
        float speed = (float) Math.sqrt(vx * vx + vz * vz);
        if (speed > GameConstants.BALL_MAX_SPEED) {
            vx *= GameConstants.BALL_MAX_SPEED / speed;
            vz *= GameConstants.BALL_MAX_SPEED / speed;
        }
        ball.setNetworkState(bumperX + nx * minDistance, pos.y, bumperZ + nz * minDistance, vx, vz,
                ball.getVerticalVelocity(), ball.getSpin());
        return true;
    }
}

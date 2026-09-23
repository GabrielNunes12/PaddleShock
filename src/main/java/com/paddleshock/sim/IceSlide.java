package com.paddleshock.sim;

/**
 * Glacier Rink's icy paddle: instead of moving exactly as far as the input asks each tick, the
 * paddle's velocity eases toward the input's velocity at {@link #GRIP} per second, so it lags on
 * starts and slides on after the input stops. One instance per paddle. Pure math.
 */
public final class IceSlide {

    /** How quickly the paddle's velocity catches up with the requested one (1/seconds). */
    public static final float GRIP = 4f;

    private float velocityX;
    private float velocityZ;

    /** Turns this tick's requested displacement into the icy one. A zero tpf passes input through
     *  unchanged (nothing to integrate). */
    public float[] step(float deltaX, float deltaZ, float tpf) {
        if (tpf <= 0f) {
            return new float[] {deltaX, deltaZ};
        }
        float blend = Math.min(1f, GRIP * tpf);
        velocityX += (deltaX / tpf - velocityX) * blend;
        velocityZ += (deltaZ / tpf - velocityZ) * blend;
        return new float[] {velocityX * tpf, velocityZ * tpf};
    }

    /** The paddle actually moved {@code actualX} of the {@code attemptedX} asked (a rail stopped
     *  it): kill the slide in that direction so it doesn't keep pushing into the rail. */
    public void onBlocked(float attemptedX, float actualX, float tpf) {
        if (tpf > 0f && Math.abs(actualX) < Math.abs(attemptedX) - 1e-6f) {
            velocityX = actualX / tpf;
        }
    }

    public float getVelocityX() {
        return velocityX;
    }
}

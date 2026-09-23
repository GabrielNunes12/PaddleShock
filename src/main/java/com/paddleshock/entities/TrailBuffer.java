package com.paddleshock.entities;

/**
 * The last {@code capacity} ball positions, oldest first, for {@link BallTrail}. Pure data - no
 * scene graph - so the sampling/fade rules are unit-testable.
 */
public final class TrailBuffer {

    private final float[] xs;
    private final float[] ys;
    private final float[] zs;
    private int size;
    private int head;

    public TrailBuffer(int capacity) {
        xs = new float[capacity];
        ys = new float[capacity];
        zs = new float[capacity];
    }

    public void push(float x, float y, float z) {
        xs[head] = x;
        ys[head] = y;
        zs[head] = z;
        head = (head + 1) % xs.length;
        size = Math.min(size + 1, xs.length);
    }

    public void clear() {
        size = 0;
        head = 0;
    }

    public int size() {
        return size;
    }

    /** Position {@code i}, where 0 is the oldest kept sample and {@code size()-1} the newest. */
    public float[] get(int i) {
        int index = (head - size + i + xs.length) % xs.length;
        return new float[] {xs[index], ys[index], zs[index]};
    }

    /** Opacity of sample {@code i}: newest is {@code maxAlpha}, fading linearly toward the oldest. */
    public float alpha(int i, float maxAlpha) {
        return size == 0 ? 0f : maxAlpha * (i + 1) / size;
    }
}

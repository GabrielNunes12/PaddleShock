package com.paddleshock.input;

/**
 * Four held direction keys as a virtual analog stick: each axis is -1, 0 or +1 (opposite keys
 * cancel), and a diagonal is scaled back to length 1 so it isn't faster than a straight push.
 * Pure state - the key events are fed in by whoever owns the input mappings.
 */
public final class KeyboardStick {

    private boolean left;
    private boolean right;
    private boolean up;
    private boolean down;

    public void setLeft(boolean held) {
        left = held;
    }

    public void setRight(boolean held) {
        right = held;
    }

    public void setUp(boolean held) {
        up = held;
    }

    public void setDown(boolean held) {
        down = held;
    }

    /** {x, y} with x = right, y = up, length at most 1. */
    public float[] vector() {
        float x = (right ? 1f : 0f) - (left ? 1f : 0f);
        float y = (up ? 1f : 0f) - (down ? 1f : 0f);
        if (x != 0f && y != 0f) {
            float inv = (float) (1.0 / Math.sqrt(2.0));
            x *= inv;
            y *= inv;
        }
        return new float[] {x, y};
    }
}

package com.paddleshock.input;

import com.jme3.input.InputManager;
import com.jme3.input.MouseInput;
import com.jme3.input.controls.AnalogListener;
import com.jme3.input.controls.MouseAxisTrigger;

/** Tracks raw mouse movement each frame; the app reads and resets it once per update. */
public class PlayerInput implements AnalogListener {

    private static final String MOUSE_X_POS = "PS_MouseXPos";
    private static final String MOUSE_X_NEG = "PS_MouseXNeg";
    private static final String MOUSE_Y_POS = "PS_MouseYPos";
    private static final String MOUSE_Y_NEG = "PS_MouseYNeg";

    private float pendingX = 0f;
    private float pendingY = 0f;

    public void register(InputManager inputManager) {
        inputManager.setCursorVisible(false);

        inputManager.addMapping(MOUSE_X_POS, new MouseAxisTrigger(MouseInput.AXIS_X, false));
        inputManager.addMapping(MOUSE_X_NEG, new MouseAxisTrigger(MouseInput.AXIS_X, true));
        inputManager.addMapping(MOUSE_Y_POS, new MouseAxisTrigger(MouseInput.AXIS_Y, false));
        inputManager.addMapping(MOUSE_Y_NEG, new MouseAxisTrigger(MouseInput.AXIS_Y, true));
        inputManager.addListener(this, MOUSE_X_POS, MOUSE_X_NEG, MOUSE_Y_POS, MOUSE_Y_NEG);
    }

    @Override
    public void onAnalog(String name, float value, float tpf) {
        switch (name) {
            case MOUSE_X_POS -> pendingX += value;
            case MOUSE_X_NEG -> pendingX -= value;
            case MOUSE_Y_POS -> pendingY += value;
            case MOUSE_Y_NEG -> pendingY -= value;
            default -> {
            }
        }
    }

    /** Returns the accumulated mouse movement since the last call, then clears it. */
    public float[] consumeDelta() {
        float[] delta = {pendingX, pendingY};
        pendingX = 0f;
        pendingY = 0f;
        return delta;
    }
}

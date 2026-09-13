package com.paddleshock.input;

import com.jme3.input.InputManager;
import com.jme3.input.Joystick;
import com.jme3.input.JoystickAxis;
import com.jme3.input.MouseInput;
import com.jme3.input.controls.AnalogListener;
import com.jme3.input.controls.JoyAxisTrigger;
import com.jme3.input.controls.MouseAxisTrigger;

import com.paddleshock.GameConstants;

/**
 * Tracks raw mouse movement and (if present) the primary gamepad's left stick each frame;
 * the app reads and resets both once per update.
 */
public class PlayerInput implements AnalogListener {

    private static final String MOUSE_X_POS = "PS_MouseXPos";
    private static final String MOUSE_X_NEG = "PS_MouseXNeg";
    private static final String MOUSE_Y_POS = "PS_MouseYPos";
    private static final String MOUSE_Y_NEG = "PS_MouseYNeg";

    private static final String GAMEPAD_X_POS = "PS_GamepadXPos";
    private static final String GAMEPAD_X_NEG = "PS_GamepadXNeg";
    private static final String GAMEPAD_Y_POS = "PS_GamepadYPos";
    private static final String GAMEPAD_Y_NEG = "PS_GamepadYNeg";

    private float pendingX = 0f;
    private float pendingY = 0f;

    private float pendingGamepadX = 0f;
    private float pendingGamepadY = 0f;
    private boolean gamepadConnected = false;

    public void register(InputManager inputManager) {
        inputManager.setCursorVisible(false);

        inputManager.addMapping(MOUSE_X_POS, new MouseAxisTrigger(MouseInput.AXIS_X, false));
        inputManager.addMapping(MOUSE_X_NEG, new MouseAxisTrigger(MouseInput.AXIS_X, true));
        inputManager.addMapping(MOUSE_Y_POS, new MouseAxisTrigger(MouseInput.AXIS_Y, false));
        inputManager.addMapping(MOUSE_Y_NEG, new MouseAxisTrigger(MouseInput.AXIS_Y, true));
        inputManager.addListener(this, MOUSE_X_POS, MOUSE_X_NEG, MOUSE_Y_POS, MOUSE_Y_NEG);

        registerGamepad(inputManager);
    }

    /**
     * Binds the first connected joystick/gamepad's left stick (X/Y axes), if any is present.
     * Gracefully does nothing when no gamepad is connected (the overwhelmingly common case),
     * since jME3's joystick backend (LWJGL3/GLFW) simply reports an empty array in that case.
     */
    private void registerGamepad(InputManager inputManager) {
        try {
            Joystick[] joysticks = inputManager.getJoysticks();
            if (joysticks == null || joysticks.length == 0) {
                return;
            }

            Joystick pad = joysticks[0];
            JoystickAxis xAxis = pad.getXAxis();
            JoystickAxis yAxis = pad.getYAxis();
            if (xAxis == null || yAxis == null) {
                return;
            }

            int joyId = pad.getJoyId();
            int xAxisId = xAxis.getAxisId();
            int yAxisId = yAxis.getAxisId();

            inputManager.addMapping(GAMEPAD_X_POS, new JoyAxisTrigger(joyId, xAxisId, false));
            inputManager.addMapping(GAMEPAD_X_NEG, new JoyAxisTrigger(joyId, xAxisId, true));
            inputManager.addMapping(GAMEPAD_Y_POS, new JoyAxisTrigger(joyId, yAxisId, false));
            inputManager.addMapping(GAMEPAD_Y_NEG, new JoyAxisTrigger(joyId, yAxisId, true));
            inputManager.addListener(this, GAMEPAD_X_POS, GAMEPAD_X_NEG, GAMEPAD_Y_POS, GAMEPAD_Y_NEG);

            gamepadConnected = true;
        } catch (RuntimeException e) {
            // Any joystick backend hiccup just means "no gamepad this session" - mouse still works.
            gamepadConnected = false;
        }
    }

    @Override
    public void onAnalog(String name, float value, float tpf) {
        switch (name) {
            case MOUSE_X_POS -> pendingX += value;
            case MOUSE_X_NEG -> pendingX -= value;
            case MOUSE_Y_POS -> pendingY += value;
            case MOUSE_Y_NEG -> pendingY -= value;

            // Stick axes report an absolute position, not a delta, so the latest sample wins
            // rather than accumulating (unlike the mouse deltas above).
            case GAMEPAD_X_POS -> pendingGamepadX = value;
            case GAMEPAD_X_NEG -> pendingGamepadX = -value;
            // Sticks conventionally report "up" as negative; flip so pushing up feels like
            // moving the paddle away from the player, matching the mouse-forward direction.
            case GAMEPAD_Y_POS -> pendingGamepadY = -value;
            case GAMEPAD_Y_NEG -> pendingGamepadY = value;
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

    /**
     * Returns the left stick's current position since the last call, then clears it.
     * Values within {@link GameConstants#GAMEPAD_DEADZONE} of center are reported as zero,
     * so both components being zero also covers "no gamepad connected" and "stick centered".
     */
    public float[] consumeGamepadInput() {
        float x = pendingGamepadX;
        float y = pendingGamepadY;
        pendingGamepadX = 0f;
        pendingGamepadY = 0f;

        float magnitude = (float) Math.sqrt(x * x + y * y);
        if (magnitude < GameConstants.GAMEPAD_DEADZONE) {
            return new float[] {0f, 0f};
        }
        return new float[] {x, y};
    }

    public boolean isGamepadConnected() {
        return gamepadConnected;
    }
}

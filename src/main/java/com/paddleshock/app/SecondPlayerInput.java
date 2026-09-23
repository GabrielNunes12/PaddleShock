package com.paddleshock.app;

import com.jme3.input.InputManager;
import com.jme3.input.Joystick;
import com.jme3.input.JoystickAxis;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.AnalogListener;
import com.jme3.input.controls.JoyAxisTrigger;
import com.jme3.input.controls.JoyButtonTrigger;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.math.Vector3f;

import com.paddleshock.GameConstants;
import com.paddleshock.input.KeyboardStick;

/**
 * Player 2's controls in local versus: the arrow keys (as a {@link KeyboardStick}) or the first
 * gamepad's left stick for movement, and 8/9/0 or gamepad buttons 0/1/2 (A/B/X on most pads) for
 * the three power-up slots. A Steam Remote Play Together guest shows up as exactly this kind of
 * keyboard/gamepad input. Every mapping it adds is removed again in {@link #unregister}.
 */
final class SecondPlayerInput implements ActionListener, AnalogListener {

    private static final String LEFT = "P2_Left";
    private static final String RIGHT = "P2_Right";
    private static final String UP = "P2_Up";
    private static final String DOWN = "P2_Down";
    private static final String PAD_X_POS = "P2_PadXPos";
    private static final String PAD_X_NEG = "P2_PadXNeg";
    private static final String PAD_Y_POS = "P2_PadYPos";
    private static final String PAD_Y_NEG = "P2_PadYNeg";
    static final String[] POWERUP_ACTIONS = {"P2_PowerUp1", "P2_PowerUp2", "P2_PowerUp3"};
    private static final int[] POWERUP_KEYS = {KeyInput.KEY_8, KeyInput.KEY_9, KeyInput.KEY_0};

    private final KeyboardStick keys = new KeyboardStick();
    private float padX;
    private float padY;
    private Integer pendingSlot;

    void register(InputManager inputManager) {
        inputManager.addMapping(LEFT, new KeyTrigger(KeyInput.KEY_LEFT));
        inputManager.addMapping(RIGHT, new KeyTrigger(KeyInput.KEY_RIGHT));
        inputManager.addMapping(UP, new KeyTrigger(KeyInput.KEY_UP));
        inputManager.addMapping(DOWN, new KeyTrigger(KeyInput.KEY_DOWN));
        inputManager.addListener(this, LEFT, RIGHT, UP, DOWN);

        Joystick pad = firstJoystick(inputManager);
        for (int i = 0; i < POWERUP_ACTIONS.length; i++) {
            inputManager.addMapping(POWERUP_ACTIONS[i], new KeyTrigger(POWERUP_KEYS[i]));
            if (pad != null && i < pad.getButtonCount()) {
                inputManager.addMapping(POWERUP_ACTIONS[i], new JoyButtonTrigger(pad.getJoyId(), i));
            }
            inputManager.addListener(this, POWERUP_ACTIONS[i]);
        }
        if (pad != null && pad.getXAxis() != null && pad.getYAxis() != null) {
            JoystickAxis xAxis = pad.getXAxis();
            JoystickAxis yAxis = pad.getYAxis();
            inputManager.addMapping(PAD_X_POS, new JoyAxisTrigger(pad.getJoyId(), xAxis.getAxisId(), false));
            inputManager.addMapping(PAD_X_NEG, new JoyAxisTrigger(pad.getJoyId(), xAxis.getAxisId(), true));
            inputManager.addMapping(PAD_Y_POS, new JoyAxisTrigger(pad.getJoyId(), yAxis.getAxisId(), false));
            inputManager.addMapping(PAD_Y_NEG, new JoyAxisTrigger(pad.getJoyId(), yAxis.getAxisId(), true));
            inputManager.addListener(this, PAD_X_POS, PAD_X_NEG, PAD_Y_POS, PAD_Y_NEG);
        }
    }

    void unregister(InputManager inputManager) {
        for (String mapping : new String[] {LEFT, RIGHT, UP, DOWN, PAD_X_POS, PAD_X_NEG, PAD_Y_POS, PAD_Y_NEG}) {
            if (inputManager.hasMapping(mapping)) {
                inputManager.deleteMapping(mapping);
            }
        }
        for (String action : POWERUP_ACTIONS) {
            if (inputManager.hasMapping(action)) {
                inputManager.deleteMapping(action);
            }
        }
        inputManager.removeListener(this);
    }

    private static Joystick firstJoystick(InputManager inputManager) {
        try {
            Joystick[] joysticks = inputManager.getJoysticks();
            return joysticks == null || joysticks.length == 0 ? null : joysticks[0];
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        switch (name) {
            case LEFT -> keys.setLeft(isPressed);
            case RIGHT -> keys.setRight(isPressed);
            case UP -> keys.setUp(isPressed);
            case DOWN -> keys.setDown(isPressed);
            default -> {
                for (int i = 0; i < POWERUP_ACTIONS.length; i++) {
                    if (POWERUP_ACTIONS[i].equals(name) && isPressed) {
                        pendingSlot = i;
                    }
                }
            }
        }
    }

    @Override
    public void onAnalog(String name, float value, float tpf) {
        switch (name) {
            case PAD_X_POS -> padX = value;
            case PAD_X_NEG -> padX = -value;
            // Sticks report "up" as negative - same flip as PlayerInput.
            case PAD_Y_POS -> padY = -value;
            case PAD_Y_NEG -> padY = value;
            default -> {
            }
        }
    }

    /** This tick's world {x, z} movement: the gamepad stick if it's pushed past the deadzone,
     *  else the arrow keys - see {@link #toWorldDelta}. */
    float[] consumeWorldDelta(float tpf, Vector3f screenRightWorld, Vector3f screenUpWorld) {
        float[] stick = {padX, padY};
        padX = 0f;
        padY = 0f;
        if (Math.sqrt(stick[0] * stick[0] + stick[1] * stick[1]) < GameConstants.GAMEPAD_DEADZONE) {
            stick = keys.vector();
        }
        return toWorldDelta(stick, tpf, screenRightWorld, screenUpWorld);
    }

    /** A stick vector (x = screen right, y = screen up) as a world-space {x, z} displacement for
     *  one tick at gamepad speed, along the camera's own table-plane axes. */
    static float[] toWorldDelta(float[] stick, float tpf, Vector3f screenRightWorld, Vector3f screenUpWorld) {
        float scale = GameConstants.GAMEPAD_MOVE_SPEED * tpf;
        return new float[] {
            (screenRightWorld.x * stick[0] + screenUpWorld.x * stick[1]) * scale,
            (screenRightWorld.z * stick[0] + screenUpWorld.z * stick[1]) * scale};
    }

    /** The power-up slot pressed since the last call, or {@code null}. */
    Integer consumePowerUpSlot() {
        Integer slot = pendingSlot;
        pendingSlot = null;
        return slot;
    }
}

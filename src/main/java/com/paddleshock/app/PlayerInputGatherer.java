package com.paddleshock.app;

import com.jme3.input.InputManager;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.math.Vector3f;

import com.paddleshock.GameConstants;
import com.paddleshock.data.PowerUpDefinition;
import com.paddleshock.input.PlayerInput;

/**
 * Gathers this client's own local mouse/gamepad movement plus power-up hotkey presses, buffering a
 * power-up activation until the next tick consumes it - see {@link GameplayAppState#activatePlayerPowerUp}.
 * The buffered slot is split into two independent buffers ({@link #activateSlot}'s
 * {@code isJoiner} flag) since a joiner's activation is consumed into an outgoing network packet
 * (see {@link #consumeNetworkActivation}) rather than applied to a local {@code PowerUpManager}
 * (see {@link #consumeLocalActivation}) - exactly one of the two is ever non-null for a given
 * {@link GameplayAppState.Mode}. Pulled out of {@link GameplayAppState} since none of this needs
 * the simulation, networking, or scene/HUD state around it - just the camera-relative screen axes
 * {@code GameplayAppState#setUpCamera} already computes and a per-frame mouse-sensitivity value
 * from settings, both passed into {@link #consumeWorldDelta} rather than read internally, so this
 * class has no dependency on the app/settings classes at all.
 */
final class PlayerInputGatherer {

    static final String[] POWERUP_ACTIONS = {"PS_PowerUp1", "PS_PowerUp2", "PS_PowerUp3"};
    private static final int[] POWERUP_KEYS = {KeyInput.KEY_1, KeyInput.KEY_2, KeyInput.KEY_3};

    private final PlayerInput playerInput = new PlayerInput();

    /** Set by {@link #activateSlot} when {@code isJoiner} is false; consumed (and cleared) by
     *  {@link #consumeLocalActivation} on the very next tick. */
    private Integer pendingLocalSlot;
    /** Set by {@link #activateSlot} when {@code isJoiner} is true; consumed (and cleared) by
     *  {@link #consumeNetworkActivation} on the very next tick. */
    private Integer pendingNetworkSlot;

    void register(InputManager inputManager) {
        playerInput.register(inputManager);
    }

    void registerPowerUpKeys(InputManager inputManager, ActionListener listener) {
        for (int i = 0; i < POWERUP_ACTIONS.length; i++) {
            inputManager.addMapping(POWERUP_ACTIONS[i], new KeyTrigger(POWERUP_KEYS[i]));
            inputManager.addListener(listener, POWERUP_ACTIONS[i]);
        }
    }

    /** Buffers activation of {@code slot} for the next tick - {@code isJoiner} selects which of
     *  the two independent buffers (local-apply vs. send-to-host) it lands in. */
    void activateSlot(int slot, boolean isJoiner) {
        if (isJoiner) {
            pendingNetworkSlot = slot;
        } else {
            pendingLocalSlot = slot;
        }
    }

    /** Consumed (and cleared) into the same tick-shaped input the AI (and a remote opponent, via
     *  NetHost/NetClient) uses - see {@code GameplayAppState#computeLocalPaddleInput}. */
    PowerUpDefinition consumeLocalActivation(PowerUpDefinition[] loadout) {
        if (pendingLocalSlot == null) {
            return null;
        }
        PowerUpDefinition def = loadout[pendingLocalSlot];
        pendingLocalSlot = null;
        return def;
    }

    /** Consumed into the outgoing network packet - see {@code GameplayAppState#updateJoiner}. */
    PowerUpDefinition consumeNetworkActivation(PowerUpDefinition[] loadout) {
        if (pendingNetworkSlot == null) {
            return null;
        }
        PowerUpDefinition def = loadout[pendingNetworkSlot];
        pendingNetworkSlot = null;
        return def;
    }

    /** Local mouse/gamepad input gathered into a world-space {x, z} delta for this tick, using
     *  {@code screenRightWorld}/{@code screenUpWorld} (the camera-relative table-plane directions
     *  {@code GameplayAppState#setUpCamera} computes) so screen-relative movement maps correctly
     *  onto the table regardless of which side the camera is mirrored to. */
    float[] consumeWorldDelta(float tpf, Vector3f screenRightWorld, Vector3f screenUpWorld, float mouseSensitivity) {
        float[] mouseDelta = playerInput.consumeDelta();
        float[] gamepadStick = playerInput.consumeGamepadInput();
        boolean gamepadActive = gamepadStick[0] != 0f || gamepadStick[1] != 0f;
        float worldDeltaX;
        float worldDeltaZ;
        if (gamepadActive) {
            float gamepadScale = GameConstants.GAMEPAD_MOVE_SPEED * tpf;
            worldDeltaX = (screenRightWorld.x * gamepadStick[0] + screenUpWorld.x * gamepadStick[1]) * gamepadScale;
            worldDeltaZ = (screenRightWorld.z * gamepadStick[0] + screenUpWorld.z * gamepadStick[1]) * gamepadScale;
        } else {
            float scale = GameConstants.MOUSE_SENSITIVITY * mouseSensitivity;
            worldDeltaX = (screenRightWorld.x * mouseDelta[0] + screenUpWorld.x * mouseDelta[1]) * scale;
            worldDeltaZ = (screenRightWorld.z * mouseDelta[0] + screenUpWorld.z * mouseDelta[1]) * scale;
        }
        return new float[] {worldDeltaX, worldDeltaZ};
    }
}

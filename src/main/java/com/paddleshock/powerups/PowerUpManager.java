package com.paddleshock.powerups;

import java.util.EnumMap;
import java.util.Map;

import com.jme3.asset.AssetManager;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;

import com.paddleshock.GameConstants;
import com.paddleshock.entities.Paddle;

/** Spawns pickups on the table, applies their timed effects, and reverts them on expiry. */
public class PowerUpManager {

    private static final float SPAWN_INTERVAL = 7f;
    private static final float SPAWN_Z_RANGE = 3f;

    private final AssetManager assetManager;
    private final Node worldNode;
    private final Paddle playerPaddle;
    private final Paddle opponentPaddle;

    private PowerUp active;
    private float spawnTimer = SPAWN_INTERVAL;

    private final Map<PowerUpType, Float> playerBuffTimers = new EnumMap<>(PowerUpType.class);
    private float opponentSlowTimer = 0f;

    public PowerUpManager(AssetManager assetManager, Node worldNode, Paddle playerPaddle, Paddle opponentPaddle) {
        this.assetManager = assetManager;
        this.worldNode = worldNode;
        this.playerPaddle = playerPaddle;
        this.opponentPaddle = opponentPaddle;
    }

    public void update(float tpf) {
        updateSpawning(tpf);
        updateActivePickup(tpf);
        updateBuffTimers(tpf);
    }

    private void updateSpawning(float tpf) {
        if (active != null) {
            return;
        }
        spawnTimer -= tpf;
        if (spawnTimer <= 0f) {
            spawn();
            spawnTimer = SPAWN_INTERVAL;
        }
    }

    private void spawn() {
        PowerUpType[] types = PowerUpType.values();
        PowerUpType type = types[(int) (Math.random() * types.length)];

        float maxX = GameConstants.TABLE_HALF_WIDTH - 1f;
        float x = (float) (Math.random() * 2 - 1) * maxX;
        float z = (float) (Math.random() * 2 - 1) * SPAWN_Z_RANGE;
        Vector3f position = new Vector3f(x, 0.5f, z);

        active = new PowerUp(assetManager, type, position);
        worldNode.attachChild(active.getGeometry());
    }

    private void updateActivePickup(float tpf) {
        if (active == null) {
            return;
        }
        active.update(tpf);

        if (active.isWithinPickupRange(playerPaddle.getPosition(), playerPaddle.getEffectiveRadius())) {
            applyEffect(active.getType());
            despawnActive();
        } else if (active.isExpired()) {
            despawnActive();
        }
    }

    private void despawnActive() {
        worldNode.detachChild(active.getGeometry());
        active = null;
    }

    private void applyEffect(PowerUpType type) {
        switch (type) {
            case PADDLE_GROW -> {
                playerPaddle.setRadiusBuff(1.6f);
                playerBuffTimers.put(type, type.getDuration());
            }
            case SPEED_BOOST -> {
                playerPaddle.setSpeedBuff(1.8f);
                playerBuffTimers.put(type, type.getDuration());
            }
            case SLOW_OPPONENT -> {
                opponentPaddle.setSpeedBuff(0.5f);
                opponentSlowTimer = type.getDuration();
            }
        }
    }

    private void updateBuffTimers(float tpf) {
        playerBuffTimers.replaceAll((type, remaining) -> remaining - tpf);
        playerBuffTimers.entrySet().removeIf(entry -> {
            if (entry.getValue() > 0f) {
                return false;
            }
            revertPlayerEffect(entry.getKey());
            return true;
        });

        if (opponentSlowTimer > 0f) {
            opponentSlowTimer -= tpf;
            if (opponentSlowTimer <= 0f) {
                opponentPaddle.setSpeedBuff(1f);
            }
        }
    }

    private void revertPlayerEffect(PowerUpType type) {
        switch (type) {
            case PADDLE_GROW -> playerPaddle.setRadiusBuff(1f);
            case SPEED_BOOST -> playerPaddle.setSpeedBuff(1f);
            default -> {
            }
        }
    }
}

package com.paddleshock.powerups;

import java.util.EnumMap;
import java.util.Map;

import com.paddleshock.entities.Paddle;

/**
 * Applies store-bought power-up effects on demand (triggered by a keybind, for either side) and
 * reverts them on expiry. Each side tracks its own per-type cooldown; a paddle's actual buff each
 * frame is recomputed from whatever effects are currently live on it, so a self-buff (Paddle Grow,
 * Speed Boost) and an incoming debuff (Slow Opponent) can coexist and expire independently.
 */
public class PowerUpManager {

    private final Paddle playerPaddle;
    private final Paddle opponentPaddle;

    private final Map<PowerUpType, Float> playerCooldowns = new EnumMap<>(PowerUpType.class);
    private final Map<PowerUpType, Float> aiCooldowns = new EnumMap<>(PowerUpType.class);
    private final Map<PowerUpType, Float> playerPaddleEffects = new EnumMap<>(PowerUpType.class);
    private final Map<PowerUpType, Float> opponentPaddleEffects = new EnumMap<>(PowerUpType.class);

    public PowerUpManager(Paddle playerPaddle, Paddle opponentPaddle) {
        this.playerPaddle = playerPaddle;
        this.opponentPaddle = opponentPaddle;
    }

    public void update(float tpf) {
        tickCooldowns(playerCooldowns, tpf);
        tickCooldowns(aiCooldowns, tpf);
        tickEffects(playerPaddleEffects, tpf);
        tickEffects(opponentPaddleEffects, tpf);
        applyCombinedBuffs(playerPaddle, playerPaddleEffects);
        applyCombinedBuffs(opponentPaddle, opponentPaddleEffects);
    }

    public boolean isPlayerReady(PowerUpType type) {
        return playerCooldowns.getOrDefault(type, 0f) <= 0f;
    }

    public boolean isAiReady(PowerUpType type) {
        return aiCooldowns.getOrDefault(type, 0f) <= 0f;
    }

    public float getPlayerCooldownRemaining(PowerUpType type) {
        return Math.max(0f, playerCooldowns.getOrDefault(type, 0f));
    }

    /** Attempts to trigger the power-up for the player; returns false (no-op) if still on cooldown. */
    public boolean activatePlayerPowerUp(PowerUpType type, float cooldownSeconds) {
        return activate(true, type, cooldownSeconds);
    }

    /** Attempts to trigger the power-up for the AI opponent; returns false if still on cooldown. */
    public boolean activateAiPowerUp(PowerUpType type, float cooldownSeconds) {
        return activate(false, type, cooldownSeconds);
    }

    private boolean activate(boolean isPlayer, PowerUpType type, float cooldownSeconds) {
        Map<PowerUpType, Float> cooldowns = isPlayer ? playerCooldowns : aiCooldowns;
        if (cooldowns.getOrDefault(type, 0f) > 0f) {
            return false;
        }
        cooldowns.put(type, cooldownSeconds);

        Map<PowerUpType, Float> selfEffects = isPlayer ? playerPaddleEffects : opponentPaddleEffects;
        Map<PowerUpType, Float> foeEffects = isPlayer ? opponentPaddleEffects : playerPaddleEffects;
        if (type == PowerUpType.SLOW_OPPONENT) {
            foeEffects.put(type, type.getDuration());
        } else {
            selfEffects.put(type, type.getDuration());
        }
        return true;
    }

    private void tickCooldowns(Map<PowerUpType, Float> cooldowns, float tpf) {
        cooldowns.replaceAll((type, remaining) -> remaining - tpf);
    }

    private void tickEffects(Map<PowerUpType, Float> effects, float tpf) {
        effects.replaceAll((type, remaining) -> remaining - tpf);
        effects.entrySet().removeIf(entry -> entry.getValue() <= 0f);
    }

    private void applyCombinedBuffs(Paddle paddle, Map<PowerUpType, Float> effects) {
        float radius = effects.containsKey(PowerUpType.PADDLE_GROW) ? 1.6f : 1f;
        float speed = 1f;
        if (effects.containsKey(PowerUpType.SPEED_BOOST)) {
            speed *= 1.8f;
        }
        if (effects.containsKey(PowerUpType.SLOW_OPPONENT)) {
            speed *= 0.5f;
        }
        paddle.setRadiusBuff(radius);
        paddle.setSpeedBuff(speed);
    }
}

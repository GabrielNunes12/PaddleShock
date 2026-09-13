package com.paddleshock.data;

import com.paddleshock.powerups.PowerUpType;

/** A purchasable power-up: unlocked once, then usable from the loadout on a per-match cooldown. */
public class PowerUpDefinition extends ItemDefinition {

    private final PowerUpType type;
    private final float cooldownSeconds;

    public PowerUpDefinition(String id, String displayName, int price, PowerUpType type, float cooldownSeconds) {
        super(id, displayName, price);
        this.type = type;
        this.cooldownSeconds = cooldownSeconds;
    }

    public PowerUpType getType() {
        return type;
    }

    public float getCooldownSeconds() {
        return cooldownSeconds;
    }
}

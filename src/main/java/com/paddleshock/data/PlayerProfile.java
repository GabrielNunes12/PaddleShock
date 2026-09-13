package com.paddleshock.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Persisted player state: currency, owned items, current loadout, and ranked identity. */
public class PlayerProfile {

    private static final int POWERUP_SLOTS = 3;

    private int currency = 300;

    // Identifies this player to the ranked ladder backend (see aws/README.md) - a random id
    // generated once on first use, not tied to any account/login. Old saves predate this field
    // and deserialize it as null; getPlayerId() generates and persists one lazily in that case.
    private String playerId;

    // First-launch onboarding: false until the player has viewed the HOW TO PLAY screen once
    // (either automatically on first launch, or by opening it from the main menu) - see
    // HowToPlayState/PaddleShockApp#showHowToPlay. Old saves predate this field and deserialize it
    // as false too, so an existing player sees it once as well - a one-time no-op inconvenience,
    // not worth a separate "is this actually a brand-new save" check.
    private boolean hasSeenTutorial = false;

    private Set<String> ownedPaddleIds = new HashSet<>(Set.of("paddle_classic"));
    private Set<String> ownedTableIds = new HashSet<>(Set.of("table_classic"));
    private Set<String> ownedBallIds = new HashSet<>(Set.of("ball_classic"));
    private Set<String> ownedPowerUpIds = new HashSet<>();

    private String equippedPaddleId = "paddle_classic";
    private String equippedTableId = "table_classic";
    private String equippedBallId = "ball_classic";
    private String equippedLevelId = "level_classic";

    /** Up to 3 owned power-up ids, one per key slot (1/2/3); a slot is empty when null. */
    private List<String> powerUpLoadout = new ArrayList<>(List.of("", "", ""));

    public int getCurrency() {
        return currency;
    }

    /** This player's identity for the ranked ladder backend - generated once, lazily, and kept
     *  stable across saves thereafter. Callers should {@code SaveManager.saveProfile()} shortly
     *  after the first call that actually generates one, or it'll regenerate on next launch. */
    public String getPlayerId() {
        if (playerId == null) {
            playerId = UUID.randomUUID().toString();
        }
        return playerId;
    }

    public boolean hasSeenTutorial() {
        return hasSeenTutorial;
    }

    public void setHasSeenTutorial(boolean hasSeenTutorial) {
        this.hasSeenTutorial = hasSeenTutorial;
    }

    public void addCurrency(int amount) {
        currency += amount;
    }

    public boolean owns(String category, String id) {
        // Levels are free for everyone, regardless of save history - never gated like paddle/table/ball.
        if ("level".equals(category)) {
            return true;
        }
        return ownedSetFor(category).contains(id);
    }

    /** Attempts to buy the item; deducts currency and marks it owned on success. */
    public boolean purchase(String category, String id, int price) {
        if (owns(category, id) || currency < price) {
            return false;
        }
        currency -= price;
        ownedSetFor(category).add(id);
        return true;
    }

    public void equip(String category, String id) {
        if (!owns(category, id)) {
            return;
        }
        switch (category) {
            case "paddle" -> equippedPaddleId = id;
            case "table" -> equippedTableId = id;
            case "ball" -> equippedBallId = id;
            case "level" -> equippedLevelId = id;
            default -> throw new IllegalArgumentException("Unknown category: " + category);
        }
    }

    public String getEquippedId(String category) {
        return switch (category) {
            case "paddle" -> equippedPaddleId;
            case "table" -> equippedTableId;
            case "ball" -> equippedBallId;
            case "level" -> equippedLevelId;
            default -> throw new IllegalArgumentException("Unknown category: " + category);
        };
    }

    /** Not called for "level" - owns() always returns true for it before reaching here. */
    private Set<String> ownedSetFor(String category) {
        return switch (category) {
            case "paddle" -> ownedPaddleIds;
            case "table" -> ownedTableIds;
            case "ball" -> ownedBallIds;
            default -> throw new IllegalArgumentException("Unknown category: " + category);
        };
    }

    public boolean ownsPowerUp(String id) {
        return ownedPowerUpIds.contains(id);
    }

    /** Attempts to buy the power-up unlock; deducts currency and marks it owned on success. */
    public boolean purchasePowerUp(String id, int price) {
        if (ownsPowerUp(id) || currency < price) {
            return false;
        }
        currency -= price;
        ownedPowerUpIds.add(id);
        return true;
    }

    /** The 3 loadout slots (key 1/2/3); an empty string means that slot has nothing assigned. */
    public List<String> getLoadout() {
        normalizeLoadoutSize();
        return List.copyOf(powerUpLoadout);
    }

    public String getLoadoutSlot(int slot) {
        normalizeLoadoutSize();
        return powerUpLoadout.get(slot);
    }

    /**
     * Assigns an owned power-up to a slot (or clears it with {@code null}/""), bumping it out of
     * any other slot it already occupied so the same power-up never fills two slots at once.
     */
    public void setLoadoutSlot(int slot, String powerUpId) {
        normalizeLoadoutSize();
        String id = powerUpId == null ? "" : powerUpId;
        if (!id.isEmpty() && !ownsPowerUp(id)) {
            return;
        }
        for (int i = 0; i < powerUpLoadout.size(); i++) {
            if (!id.isEmpty() && id.equals(powerUpLoadout.get(i))) {
                powerUpLoadout.set(i, "");
            }
        }
        powerUpLoadout.set(slot, id);
    }

    /** Old saves (or a save made before power-ups existed) may have fewer than 3 slots recorded. */
    private void normalizeLoadoutSize() {
        if (powerUpLoadout == null) {
            powerUpLoadout = new ArrayList<>();
        }
        while (powerUpLoadout.size() < POWERUP_SLOTS) {
            powerUpLoadout.add("");
        }
    }
}

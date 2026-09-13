package com.paddleshock.data;

import java.util.HashSet;
import java.util.Set;

/** Persisted player state: currency, owned items, and current loadout. */
public class PlayerProfile {

    private int currency = 300;

    private Set<String> ownedPaddleIds = new HashSet<>(Set.of("paddle_classic"));
    private Set<String> ownedTableIds = new HashSet<>(Set.of("table_classic"));
    private Set<String> ownedBallIds = new HashSet<>(Set.of("ball_classic"));

    private String equippedPaddleId = "paddle_classic";
    private String equippedTableId = "table_classic";
    private String equippedBallId = "ball_classic";

    public int getCurrency() {
        return currency;
    }

    public void addCurrency(int amount) {
        currency += amount;
    }

    public boolean owns(String category, String id) {
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
            default -> throw new IllegalArgumentException("Unknown category: " + category);
        }
    }

    public String getEquippedId(String category) {
        return switch (category) {
            case "paddle" -> equippedPaddleId;
            case "table" -> equippedTableId;
            case "ball" -> equippedBallId;
            default -> throw new IllegalArgumentException("Unknown category: " + category);
        };
    }

    private Set<String> ownedSetFor(String category) {
        return switch (category) {
            case "paddle" -> ownedPaddleIds;
            case "table" -> ownedTableIds;
            case "ball" -> ownedBallIds;
            default -> throw new IllegalArgumentException("Unknown category: " + category);
        };
    }
}

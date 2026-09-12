package com.paddleshock.data;

/** Common fields shared by every purchasable item (paddle, table, ball). */
public abstract class ItemDefinition {

    private final String id;
    private final String displayName;
    private final int price;

    protected ItemDefinition(String id, String displayName, int price) {
        this.id = id;
        this.displayName = displayName;
        this.price = price;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getPrice() {
        return price;
    }
}

package com.paddleshock.entities;

/** A CC0 color+normal map pair reused across catalog items to keep asset count small. */
public enum TextureSet {
    PLASTIC("Textures/Paddle/Paddle_Color.jpg", "Textures/Paddle/Paddle_Normal.jpg"),
    MARBLE("Textures/Table/Table_Color.jpg", "Textures/Table/Table_Normal.jpg"),
    RUBBER("Textures/Ball/Ball_Color.jpg", "Textures/Ball/Ball_Normal.jpg"),
    METAL("Textures/Metal/Metal_Color.jpg", "Textures/Metal/Metal_Normal.jpg"),
    CONCRETE("Textures/Concrete/Concrete_Color.jpg", "Textures/Concrete/Concrete_Normal.jpg"),
    ASPHALT("Textures/Asphalt/Asphalt_Color.jpg", "Textures/Asphalt/Asphalt_Normal.jpg");

    private final String colorMap;
    private final String normalMap;

    TextureSet(String colorMap, String normalMap) {
        this.colorMap = colorMap;
        this.normalMap = normalMap;
    }

    public String getColorMap() {
        return colorMap;
    }

    public String getNormalMap() {
        return normalMap;
    }
}

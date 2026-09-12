package com.paddleshock.data;

import java.util.List;
import java.util.Optional;

import com.jme3.math.ColorRGBA;

import com.paddleshock.entities.TextureSet;

/** Hard-coded store catalog. Each list's first entry is the free default. */
public final class Catalog {

    public static final List<PaddleDefinition> PADDLES = List.of(
            new PaddleDefinition("paddle_classic", "Classic", 0,
                    1.0f, 1.0f, new ColorRGBA(0.2f, 0.6f, 1f, 1f), TextureSet.PLASTIC),
            new PaddleDefinition("paddle_turbo", "Turbo", 150,
                    1.35f, 0.8f, new ColorRGBA(1f, 0.8f, 0.1f, 1f), TextureSet.METAL),
            new PaddleDefinition("paddle_wall", "The Wall", 150,
                    0.75f, 1.4f, new ColorRGBA(0.6f, 0.6f, 0.62f, 1f), TextureSet.CONCRETE));

    public static final List<TableDefinition> TABLES = List.of(
            new TableDefinition("table_classic", "Classic", 0,
                    1.0f, new ColorRGBA(0.10f, 0.12f, 0.18f, 1f), TextureSet.MARBLE),
            new TableDefinition("table_bouncy", "Super Bouncy", 200,
                    1.25f, new ColorRGBA(0.55f, 0.15f, 0.55f, 1f), TextureSet.PLASTIC),
            new TableDefinition("table_slow", "Molasses", 200,
                    0.8f, new ColorRGBA(0.20f, 0.22f, 0.18f, 1f), TextureSet.ASPHALT));

    public static final List<BallDefinition> BALLS = List.of(
            new BallDefinition("ball_classic", "Classic", 0,
                    1.0f, 1.0f, ColorRGBA.White, TextureSet.RUBBER),
            new BallDefinition("ball_pellet", "Pellet", 150,
                    1.3f, 0.7f, new ColorRGBA(1f, 0.3f, 0.3f, 1f), TextureSet.METAL),
            new BallDefinition("ball_beach", "Beach Ball", 150,
                    0.7f, 1.6f, new ColorRGBA(0.3f, 0.8f, 1f, 1f), TextureSet.PLASTIC));

    private Catalog() {
    }

    public static Optional<PaddleDefinition> findPaddle(String id) {
        return PADDLES.stream().filter(p -> p.getId().equals(id)).findFirst();
    }

    public static Optional<TableDefinition> findTable(String id) {
        return TABLES.stream().filter(t -> t.getId().equals(id)).findFirst();
    }

    public static Optional<BallDefinition> findBall(String id) {
        return BALLS.stream().filter(b -> b.getId().equals(id)).findFirst();
    }
}

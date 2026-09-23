package com.paddleshock.data;

import java.util.List;
import java.util.Optional;

import com.jme3.math.ColorRGBA;

import com.paddleshock.entities.BallModel;
import com.paddleshock.entities.PaddleModel;
import com.paddleshock.entities.TextureSet;
import com.paddleshock.powerups.PowerUpType;

/** Hard-coded store catalog. Each list's first entry is the free default. */
public final class Catalog {

    public static final List<PaddleDefinition> PADDLES = List.of(
            new PaddleDefinition("paddle_classic", "Classic", 0,
                    1.0f, 1.0f, new ColorRGBA(0.2f, 0.6f, 1f, 1f), TextureSet.PLASTIC, PaddleModel.CLASSIC),
            new PaddleDefinition("paddle_turbo", "Turbo", 150,
                    1.35f, 0.8f, new ColorRGBA(1f, 0.8f, 0.1f, 1f), TextureSet.METAL, PaddleModel.TURBO),
            new PaddleDefinition("paddle_wall", "The Wall", 150,
                    0.75f, 1.4f, new ColorRGBA(0.6f, 0.6f, 0.62f, 1f), TextureSet.CONCRETE, PaddleModel.WALL));

    public static final List<TableDefinition> TABLES = List.of(
            new TableDefinition("table_classic", "Classic", 0,
                    1.0f, new ColorRGBA(0.10f, 0.12f, 0.18f, 1f), TextureSet.MARBLE),
            new TableDefinition("table_bouncy", "Super Bouncy", 200,
                    1.25f, new ColorRGBA(0.55f, 0.15f, 0.55f, 1f), TextureSet.PLASTIC),
            new TableDefinition("table_slow", "Molasses", 200,
                    0.8f, new ColorRGBA(0.20f, 0.22f, 0.18f, 1f), TextureSet.ASPHALT));

    public static final List<BallDefinition> BALLS = List.of(
            new BallDefinition("ball_classic", "Classic", 0,
                    1.0f, 1.0f, ColorRGBA.White, TextureSet.RUBBER, BallModel.CLASSIC),
            new BallDefinition("ball_pellet", "Pellet", 150,
                    1.3f, 0.7f, new ColorRGBA(1f, 0.3f, 0.3f, 1f), TextureSet.METAL, BallModel.PELLET),
            new BallDefinition("ball_beach", "Beach Ball", 150,
                    0.7f, 1.6f, new ColorRGBA(0.3f, 0.8f, 1f, 1f), TextureSet.PLASTIC, BallModel.BEACH));

    /** No free default here - power-ups start locked and are unlocked one-by-one from the store. */
    public static final List<PowerUpDefinition> POWERUPS = List.of(
            new PowerUpDefinition("powerup_paddle_grow", "Paddle Grow", 120, PowerUpType.PADDLE_GROW, 20f),
            new PowerUpDefinition("powerup_speed_boost", "Speed Boost", 120, PowerUpType.SPEED_BOOST, 20f),
            new PowerUpDefinition("powerup_slow_opponent", "Slow Opponent", 120, PowerUpType.SLOW_OPPONENT, 20f),
            new PowerUpDefinition("powerup_tiny_paddle", "Tiny Paddle", 120, PowerUpType.SHRINK_OPPONENT, 20f),
            new PowerUpDefinition("powerup_curveball", "Curveball", 150, PowerUpType.CURVEBALL, 18f),
            new PowerUpDefinition("powerup_shield", "Shield", 180, PowerUpType.SHIELD, 25f),
            new PowerUpDefinition("powerup_ghost_ball", "Ghost Ball", 160, PowerUpType.GHOST_BALL, 22f));

    // Cosmetics (look-only - see CosmeticDefinition). The first of each list is the free "none".
    public static final List<CosmeticDefinition> SKINS = List.of(
            new CosmeticDefinition("skin_none", "Standard", 0, "skin", ColorRGBA.White, TextureSet.PLASTIC, false),
            new CosmeticDefinition("skin_crimson", "Crimson", 120, "skin", new ColorRGBA(0.9f, 0.12f, 0.18f, 1f), TextureSet.PLASTIC, false),
            new CosmeticDefinition("skin_midnight", "Midnight", 120, "skin", new ColorRGBA(0.1f, 0.12f, 0.3f, 1f), TextureSet.PLASTIC, false),
            new CosmeticDefinition("skin_mint", "Mint", 150, "skin", new ColorRGBA(0.45f, 0.95f, 0.7f, 1f), TextureSet.PLASTIC, false),
            // The metal texture darkens any tint to olive under the arena lights, so gold is a bright plastic.
            new CosmeticDefinition("skin_gold", "Gold", 300, "skin", new ColorRGBA(1f, 0.82f, 0.2f, 1f), TextureSet.PLASTIC, false));

    public static final List<CosmeticDefinition> TRAILS = List.of(
            new CosmeticDefinition("trail_none", "No Trail", 0, "trail", ColorRGBA.White, null, false),
            new CosmeticDefinition("trail_ember", "Ember", 150, "trail", new ColorRGBA(1f, 0.5f, 0.15f, 1f), null, false),
            new CosmeticDefinition("trail_frost", "Frost", 150, "trail", new ColorRGBA(0.5f, 0.9f, 1f, 1f), null, false),
            new CosmeticDefinition("trail_rainbow", "Rainbow", 350, "trail", ColorRGBA.White, null, true));

    public static final List<CosmeticDefinition> CELEBRATIONS = List.of(
            new CosmeticDefinition("celebration_none", "No Celebration", 0, "celebration", ColorRGBA.White, null, false),
            new CosmeticDefinition("celebration_sparks", "Sparks", 200, "celebration", new ColorRGBA(1f, 0.85f, 0.3f, 1f), null, false),
            new CosmeticDefinition("celebration_confetti", "Confetti", 300, "celebration", ColorRGBA.White, null, true));

    /** Arenas: free for everyone. Each has its own environment AND its own ball-physics quirk
     *  (gravity/wind/bounce energy), layered on top of whatever paddle/table/ball is equipped. */
    public static final List<LevelDefinition> LEVELS = List.of(
            new LevelDefinition("level_classic", "Classic Court", 0,
                    ColorRGBA.Black, ColorRGBA.White, ColorRGBA.White,
                    new ColorRGBA(0.30f, 0.32f, 0.36f, 1f), TextureSet.CONCRETE,
                    new ColorRGBA(0.102f, 0.114f, 0.141f, 1f), "THE ORIGINAL",
                    1.0f, 0f, 1.0f, false),
            new LevelDefinition("level_neon", "Neon Arcade", 0,
                    new ColorRGBA(0.05f, 0.02f, 0.08f, 1f), new ColorRGBA(0.6f, 0.85f, 1f, 1f),
                    new ColorRGBA(0.75f, 0.3f, 0.9f, 1f),
                    new ColorRGBA(0.15f, 0.08f, 0.22f, 1f), TextureSet.ASPHALT,
                    new ColorRGBA(0.35f, 0.1f, 0.5f, 1f), "HIGH-ENERGY BOUNCE",
                    1.0f, 0f, 1.35f, false),
            new LevelDefinition("level_sunset", "Sunset Beach", 0,
                    new ColorRGBA(0.85f, 0.5f, 0.32f, 1f), new ColorRGBA(1f, 0.82f, 0.55f, 1f),
                    new ColorRGBA(0.95f, 0.6f, 0.55f, 1f),
                    new ColorRGBA(0.82f, 0.68f, 0.45f, 1f), TextureSet.CONCRETE,
                    new ColorRGBA(0.9f, 0.45f, 0.4f, 1f), "SIDEWAYS WIND DRIFT",
                    1.0f, 1.4f, 1.0f, false),
            new LevelDefinition("level_pinball", "Pinball Palace", 0,
                    new ColorRGBA(0.12f, 0.03f, 0.07f, 1f), new ColorRGBA(1f, 0.85f, 0.75f, 1f),
                    new ColorRGBA(0.95f, 0.45f, 0.6f, 1f),
                    new ColorRGBA(0.2f, 0.08f, 0.12f, 1f), TextureSet.METAL,
                    new ColorRGBA(0.75f, 0.15f, 0.35f, 1f), "SLIDING BUMPERS",
                    1.0f, 0f, 1.1f, false, LevelHazard.BUMPERS),
            new LevelDefinition("level_glacier", "Glacier Rink", 0,
                    new ColorRGBA(0.62f, 0.8f, 0.93f, 1f), new ColorRGBA(0.95f, 0.97f, 1f, 1f),
                    new ColorRGBA(0.75f, 0.86f, 1f, 1f),
                    new ColorRGBA(0.82f, 0.9f, 0.97f, 1f), TextureSet.CONCRETE,
                    new ColorRGBA(0.35f, 0.65f, 0.9f, 1f), "ICY PADDLES",
                    1.0f, 0f, 0.95f, false, LevelHazard.ICE),
            new LevelDefinition("level_space", "Space Station", 0,
                    new ColorRGBA(0.01f, 0.01f, 0.035f, 1f), new ColorRGBA(0.7f, 0.82f, 1f, 1f),
                    new ColorRGBA(0.28f, 0.34f, 0.5f, 1f),
                    new ColorRGBA(0.5f, 0.55f, 0.62f, 1f), TextureSet.METAL,
                    new ColorRGBA(0.04f, 0.05f, 0.1f, 1f), "LOW GRAVITY",
                    0.35f, 0f, 1.0f, true));

    private Catalog() {
    }

    public static Optional<PowerUpDefinition> findPowerUp(String id) {
        return POWERUPS.stream().filter(p -> p.getId().equals(id)).findFirst();
    }

    /** The cosmetic list for "skin", "trail" or "celebration". */
    public static List<CosmeticDefinition> cosmeticsFor(String category) {
        return switch (category) {
            case "skin" -> SKINS;
            case "trail" -> TRAILS;
            case "celebration" -> CELEBRATIONS;
            default -> throw new IllegalArgumentException("Not a cosmetic category: " + category);
        };
    }

    public static Optional<CosmeticDefinition> findCosmetic(String category, String id) {
        return cosmeticsFor(category).stream().filter(c -> c.getId().equals(id)).findFirst();
    }

    public static Optional<LevelDefinition> findLevel(String id) {
        return LEVELS.stream().filter(l -> l.getId().equals(id)).findFirst();
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

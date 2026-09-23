package com.paddleshock.tour;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.jme3.math.ColorRGBA;

/**
 * The World Tour ladder and its rules. Pure logic - takes the set of beaten opponent ids (from
 * {@code PlayerProfile#getTourBeatenIds()}) rather than the profile itself, so it's trivially
 * unit-testable.
 *
 * <p>Twelve opponents, three per arena in {@code Catalog.LEVELS} order; the third of each arena
 * is its boss. Unlocks are strictly linear: an opponent is playable once the one before it has
 * been beaten. Tuning numbers are first guesses pending playtesting.
 */
public final class WorldTour {

    private WorldTour() {
    }

    private static final List<String> NONE = List.of();
    private static final String GROW = "powerup_paddle_grow";
    private static final String BOOST = "powerup_speed_boost";
    private static final String SLOW = "powerup_slow_opponent";
    private static final String TINY = "powerup_tiny_paddle";
    private static final String CURVE = "powerup_curveball";
    private static final String SHIELD = "powerup_shield";
    private static final String GHOST = "powerup_ghost_ball";

    public static final List<TourOpponent> OPPONENTS = List.of(
            // Classic Court - learn the basics.
            new TourOpponent("rookie_rex", "Rookie Rex", "level_classic", 3.8f, 1.1f, NONE, 99f, 99f,
                    new ColorRGBA(0.45f, 0.75f, 0.45f, 1f), 1f, 5, 30, false),
            new TourOpponent("coach_mara", "Coach Mara", "level_classic", 4.8f, 0.8f, List.of(BOOST), 7f, 10f,
                    new ColorRGBA(0.35f, 0.55f, 0.9f, 1f), 1f, 7, 40, false),
            new TourOpponent("the_professor", "The Professor", "level_classic", 5.8f, 0.5f, List.of(BOOST, SLOW), 5f, 8f,
                    new ColorRGBA(0.55f, 0.4f, 0.25f, 1f), 1.1f, 7, 80, true),
            // Neon Arcade - high bounce.
            new TourOpponent("pixel", "Pixel", "level_neon", 5.4f, 0.7f, List.of(TINY), 6f, 9f,
                    new ColorRGBA(0.3f, 1f, 0.9f, 1f), 0.9f, 7, 50, false),
            new TourOpponent("glitch", "Glitch", "level_neon", 6.6f, 1.3f, List.of(BOOST, GHOST), 4f, 7f,
                    new ColorRGBA(0.9f, 0.2f, 0.9f, 1f), 1f, 7, 60, false),
            new TourOpponent("neon_viper", "Neon Viper", "level_neon", 7.2f, 0.4f, List.of(BOOST, TINY), 4f, 6f,
                    new ColorRGBA(0.55f, 1f, 0.2f, 1f), 1f, 10, 120, true),
            // Sunset Beach - wind drift.
            new TourOpponent("sandy", "Sandy", "level_sunset", 5.6f, 0.8f, List.of(GROW), 6f, 9f,
                    new ColorRGBA(0.95f, 0.8f, 0.45f, 1f), 1.25f, 7, 60, false),
            new TourOpponent("breeze", "Breeze", "level_sunset", 6.4f, 0.6f, List.of(SLOW), 5f, 8f,
                    new ColorRGBA(0.5f, 0.85f, 1f, 1f), 1f, 7, 70, false),
            new TourOpponent("captain_tide", "Captain Tide", "level_sunset", 7.6f, 0.35f, List.of(GROW, SLOW, SHIELD), 3.5f, 6f,
                    new ColorRGBA(0.1f, 0.35f, 0.7f, 1f), 1.1f, 10, 150, true),
            // Space Station - low gravity.
            new TourOpponent("cosmo", "Cosmo", "level_space", 7f, 0.5f, List.of(BOOST), 4f, 7f,
                    new ColorRGBA(0.85f, 0.85f, 0.95f, 1f), 1f, 7, 80, false),
            new TourOpponent("nova", "Nova", "level_space", 8f, 0.4f, List.of(BOOST, TINY, CURVE), 3f, 6f,
                    new ColorRGBA(1f, 0.55f, 0.2f, 1f), 1f, 10, 100, false),
            new TourOpponent("the_void", "The Void", "level_space", 9.2f, 0.2f, List.of(GROW, BOOST, SLOW, TINY, CURVE, SHIELD, GHOST), 2.5f, 4.5f,
                    new ColorRGBA(0.25f, 0.1f, 0.4f, 1f), 1.15f, 10, 250, true));

    public static Optional<TourOpponent> find(String id) {
        return OPPONENTS.stream().filter(o -> o.id().equals(id)).findFirst();
    }

    /** Opponent 0 is always open; any later one needs the previous opponent beaten. */
    public static boolean isUnlocked(Set<String> beatenIds, TourOpponent opponent) {
        int index = OPPONENTS.indexOf(opponent);
        if (index < 0) {
            return false;
        }
        return index == 0 || beatenIds.contains(OPPONENTS.get(index - 1).id());
    }

    /** The first opponent not yet beaten, or empty once the whole tour is complete. */
    public static Optional<TourOpponent> nextOpponent(Set<String> beatenIds) {
        return OPPONENTS.stream().filter(o -> !beatenIds.contains(o.id())).findFirst();
    }

    public static boolean isComplete(Set<String> beatenIds) {
        return nextOpponent(beatenIds).isEmpty();
    }

    public static int beatenCount(Set<String> beatenIds) {
        return (int) OPPONENTS.stream().filter(o -> beatenIds.contains(o.id())).count();
    }

    /** Credits for a win against {@code opponent}: its one-time reward if it wasn't already
     *  beaten, otherwise {@code replayReward} (the normal random match reward). */
    public static int winReward(Set<String> beatenIdsBeforeMatch, TourOpponent opponent, int replayReward) {
        return beatenIdsBeforeMatch.contains(opponent.id()) ? replayReward : opponent.firstWinReward();
    }

    /** The opponents of one arena, in ladder order. */
    public static List<TourOpponent> forLevel(String levelId) {
        return OPPONENTS.stream().filter(o -> o.levelId().equals(levelId)).toList();
    }
}

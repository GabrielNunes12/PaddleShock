package com.paddleshock.achievements;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.paddleshock.data.Catalog;
import com.paddleshock.data.ItemDefinition;
import com.paddleshock.data.LevelDefinition;
import com.paddleshock.data.PlayerProfile;
import com.paddleshock.rank.RankTier;
import com.paddleshock.settings.AiDifficulty;
import com.paddleshock.tour.WorldTour;

/**
 * The achievement rules. Every method updates the profile's counters/unlocks in place and returns
 * only the achievements that became unlocked by this call (empty if none), so the caller can
 * toast/sync exactly those. No jME, no Steam - just {@link PlayerProfile} data.
 */
public final class AchievementTracker {

    public static final int WINS_25 = 25;
    public static final int WINS_100 = 100;
    public static final int POWER_PLAYER_USES = 100;
    public static final String FIRST_BOSS_ID = "the_professor";

    private AchievementTracker() {
    }

    /** A match just finished: updates the win/power-up/arena counters, then checks both the
     *  match-specific achievements and everything that depends on profile state. Call after the
     *  rest of the match-end bookkeeping (e.g. World Tour progress) has been applied. */
    public static List<Achievement> recordMatch(PlayerProfile profile, MatchOutcome outcome) {
        if (outcome.kind() == MatchOutcome.Kind.LOCAL_VERSUS) {
            // One person can play both sides of a local match - it never counts toward anything.
            return List.of();
        }
        profile.addPowerUpsUsed(outcome.powerUpsUsed());
        if (outcome.won()) {
            profile.addWin(outcome.levelId());
        }

        List<Achievement> unlocked = new ArrayList<>();
        if (outcome.won()) {
            unlockIf(profile, unlocked, Achievement.FIRST_WIN, true);
            unlockIf(profile, unlocked, Achievement.SHUTOUT, outcome.opponentScore() == 0);
            unlockIf(profile, unlocked, Achievement.CLOSE_CALL, outcome.playerScore() - outcome.opponentScore() == 1);
            unlockIf(profile, unlocked, Achievement.BEAT_HARD_AI,
                    outcome.kind() == MatchOutcome.Kind.QUICK_MATCH && outcome.aiDifficulty() == AiDifficulty.HARD);
            unlockIf(profile, unlocked, Achievement.ONLINE_WIN, outcome.kind() == MatchOutcome.Kind.ONLINE);
        }
        unlocked.addAll(checkProfileState(profile));
        return unlocked;
    }

    /** A ranked result arrived with the player's current tier. */
    public static List<Achievement> recordRank(PlayerProfile profile, RankTier tier) {
        List<Achievement> unlocked = new ArrayList<>();
        if (tier != null) {
            unlockIf(profile, unlocked, Achievement.RANK_GOLD, tier.ordinal() >= RankTier.GOLD.ordinal());
            unlockIf(profile, unlocked, Achievement.RANK_DIAMOND, tier == RankTier.DIAMOND);
        }
        return unlocked;
    }

    /** Everything that can be decided from profile state alone - used after every match, after
     *  purchases, and on startup (so an existing save gets credit for what it already has). */
    public static List<Achievement> checkProfileState(PlayerProfile profile) {
        List<Achievement> unlocked = new ArrayList<>();
        unlockIf(profile, unlocked, Achievement.WINS_25, profile.getTotalWins() >= WINS_25);
        unlockIf(profile, unlocked, Achievement.WINS_100, profile.getTotalWins() >= WINS_100);
        unlockIf(profile, unlocked, Achievement.POWER_PLAYER, profile.getPowerUpsUsed() >= POWER_PLAYER_USES);
        unlockIf(profile, unlocked, Achievement.ARENA_MASTER, wonOnEveryArena(profile));
        unlockIf(profile, unlocked, Achievement.TOUR_FIRST_BOSS, profile.getTourBeatenIds().contains(FIRST_BOSS_ID));
        unlockIf(profile, unlocked, Achievement.TOUR_COMPLETE, WorldTour.isComplete(profile.getTourBeatenIds()));
        unlockIf(profile, unlocked, Achievement.FULL_KIT, ownsAllGear(profile));
        unlockIf(profile, unlocked, Achievement.ALL_POWERUPS,
                Catalog.POWERUPS.stream().allMatch(p -> profile.ownsPowerUp(p.getId())));
        return unlocked;
    }

    /** "current / target" for achievements with a counter, e.g. {@code 12 / 25}; empty otherwise. */
    public static Optional<String> progress(PlayerProfile profile, Achievement achievement) {
        return switch (achievement) {
            case WINS_25 -> Optional.of(Math.min(profile.getTotalWins(), WINS_25) + " / " + WINS_25);
            case WINS_100 -> Optional.of(Math.min(profile.getTotalWins(), WINS_100) + " / " + WINS_100);
            case POWER_PLAYER -> Optional.of(Math.min(profile.getPowerUpsUsed(), POWER_PLAYER_USES) + " / " + POWER_PLAYER_USES);
            case ARENA_MASTER -> Optional.of(Catalog.LEVELS.stream().filter(l -> profile.getLevelsWonOn().contains(l.getId())).count()
                    + " / " + Catalog.LEVELS.size());
            case TOUR_COMPLETE -> Optional.of(WorldTour.beatenCount(profile.getTourBeatenIds()) + " / " + WorldTour.OPPONENTS.size());
            default -> Optional.empty();
        };
    }

    private static boolean wonOnEveryArena(PlayerProfile profile) {
        return Catalog.LEVELS.stream().map(LevelDefinition::getId).allMatch(profile.getLevelsWonOn()::contains);
    }

    private static boolean ownsAllGear(PlayerProfile profile) {
        return Catalog.PADDLES.stream().map(ItemDefinition::getId).allMatch(id -> profile.owns("paddle", id))
                && Catalog.TABLES.stream().map(ItemDefinition::getId).allMatch(id -> profile.owns("table", id))
                && Catalog.BALLS.stream().map(ItemDefinition::getId).allMatch(id -> profile.owns("ball", id));
    }

    private static void unlockIf(PlayerProfile profile, List<Achievement> unlocked, Achievement achievement, boolean condition) {
        if (condition && profile.unlockAchievement(achievement.name())) {
            unlocked.add(achievement);
        }
    }
}

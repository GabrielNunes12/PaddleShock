package com.paddleshock.achievements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import com.paddleshock.data.Catalog;
import com.paddleshock.data.PlayerProfile;
import com.paddleshock.rank.RankTier;
import com.paddleshock.settings.AiDifficulty;
import com.paddleshock.tour.TourOpponent;
import com.paddleshock.tour.WorldTour;

class AchievementTrackerTest {

    private static MatchOutcome quick(boolean won, int mine, int theirs) {
        return new MatchOutcome(MatchOutcome.Kind.QUICK_MATCH, won, mine, theirs, "level_classic", AiDifficulty.NORMAL, 0);
    }

    private static MatchOutcome withPowerUps(int used) {
        return new MatchOutcome(MatchOutcome.Kind.QUICK_MATCH, false, 3, 10, "level_classic", AiDifficulty.NORMAL, used);
    }

    @Test
    void firstWinUnlocksOnceAndOnlyOnAWin() {
        PlayerProfile p = new PlayerProfile();
        assertFalse(AchievementTracker.recordMatch(p, quick(false, 5, 10)).contains(Achievement.FIRST_WIN));
        assertTrue(AchievementTracker.recordMatch(p, quick(true, 10, 5)).contains(Achievement.FIRST_WIN));
        assertFalse(AchievementTracker.recordMatch(p, quick(true, 10, 5)).contains(Achievement.FIRST_WIN),
                "an already unlocked achievement must not be reported again");
    }

    @Test
    void shutoutNeedsZeroConceded() {
        PlayerProfile p = new PlayerProfile();
        assertFalse(AchievementTracker.recordMatch(p, quick(true, 10, 1)).contains(Achievement.SHUTOUT));
        assertTrue(AchievementTracker.recordMatch(p, quick(true, 10, 0)).contains(Achievement.SHUTOUT));
    }

    @Test
    void closeCallNeedsExactlyOnePointMargin() {
        PlayerProfile p = new PlayerProfile();
        assertFalse(AchievementTracker.recordMatch(p, quick(true, 10, 8)).contains(Achievement.CLOSE_CALL));
        assertFalse(AchievementTracker.recordMatch(p, quick(false, 9, 10)).contains(Achievement.CLOSE_CALL));
        assertTrue(AchievementTracker.recordMatch(p, quick(true, 10, 9)).contains(Achievement.CLOSE_CALL));
    }

    @Test
    void winCountersUnlockExactlyAtTheirThreshold() {
        PlayerProfile p = new PlayerProfile();
        for (int i = 1; i < AchievementTracker.WINS_25; i++) {
            assertFalse(AchievementTracker.recordMatch(p, quick(true, 10, 5)).contains(Achievement.WINS_25), "win " + i);
        }
        assertTrue(AchievementTracker.recordMatch(p, quick(true, 10, 5)).contains(Achievement.WINS_25));
        assertEquals(25, p.getTotalWins());
        assertFalse(p.getUnlockedAchievements().contains(Achievement.WINS_100.name()));
    }

    @Test
    void lossesDoNotCountAsWins() {
        PlayerProfile p = new PlayerProfile();
        AchievementTracker.recordMatch(p, quick(false, 4, 10));
        assertEquals(0, p.getTotalWins());
        assertTrue(p.getLevelsWonOn().isEmpty());
    }

    @Test
    void powerPlayerUnlocksAtOneHundredUses() {
        PlayerProfile p = new PlayerProfile();
        assertFalse(AchievementTracker.recordMatch(p, withPowerUps(99)).contains(Achievement.POWER_PLAYER));
        assertTrue(AchievementTracker.recordMatch(p, withPowerUps(1)).contains(Achievement.POWER_PLAYER));
    }

    @Test
    void hardAiOnlyCountsForQuickMatchOnHard() {
        PlayerProfile p = new PlayerProfile();
        MatchOutcome tour = new MatchOutcome(MatchOutcome.Kind.WORLD_TOUR, true, 7, 2, "level_classic", null, 0);
        MatchOutcome normal = quick(true, 10, 5);
        MatchOutcome hard = new MatchOutcome(MatchOutcome.Kind.QUICK_MATCH, true, 10, 5, "level_classic", AiDifficulty.HARD, 0);
        assertFalse(AchievementTracker.recordMatch(p, tour).contains(Achievement.BEAT_HARD_AI));
        assertFalse(AchievementTracker.recordMatch(p, normal).contains(Achievement.BEAT_HARD_AI));
        assertTrue(AchievementTracker.recordMatch(p, hard).contains(Achievement.BEAT_HARD_AI));
    }

    @Test
    void onlineWinNeedsAMultiplayerWin() {
        PlayerProfile p = new PlayerProfile();
        assertFalse(AchievementTracker.recordMatch(p, quick(true, 10, 5)).contains(Achievement.ONLINE_WIN));
        MatchOutcome online = new MatchOutcome(MatchOutcome.Kind.ONLINE, true, 10, 7, null, null, 0);
        assertTrue(AchievementTracker.recordMatch(p, online).contains(Achievement.ONLINE_WIN));
        assertEquals(2, p.getTotalWins(), "an online win with an unknown arena still counts as a win");
    }

    @Test
    void arenaMasterNeedsAWinOnEveryArena() {
        PlayerProfile p = new PlayerProfile();
        for (int i = 0; i < Catalog.LEVELS.size(); i++) {
            String level = Catalog.LEVELS.get(i).getId();
            MatchOutcome win = new MatchOutcome(MatchOutcome.Kind.QUICK_MATCH, true, 10, 5, level, AiDifficulty.EASY, 0);
            boolean last = i == Catalog.LEVELS.size() - 1;
            assertEquals(last, AchievementTracker.recordMatch(p, win).contains(Achievement.ARENA_MASTER), level);
        }
    }

    @Test
    void tourAchievementsFollowTourProgressIncludingRetroactively() {
        PlayerProfile p = new PlayerProfile();
        for (TourOpponent o : WorldTour.OPPONENTS.subList(0, 2)) {
            p.markTourBeaten(o.id());
        }
        assertFalse(AchievementTracker.checkProfileState(p).contains(Achievement.TOUR_FIRST_BOSS));
        p.markTourBeaten(AchievementTracker.FIRST_BOSS_ID);
        assertTrue(AchievementTracker.checkProfileState(p).contains(Achievement.TOUR_FIRST_BOSS));

        WorldTour.OPPONENTS.forEach(o -> p.markTourBeaten(o.id()));
        assertTrue(AchievementTracker.checkProfileState(p).contains(Achievement.TOUR_COMPLETE));
    }

    @Test
    void rankAchievementsUnlockFromGoldAndDiamond() {
        PlayerProfile p = new PlayerProfile();
        assertTrue(AchievementTracker.recordRank(p, RankTier.SILVER).isEmpty());
        assertEquals(List.of(Achievement.RANK_GOLD), AchievementTracker.recordRank(p, RankTier.PLATINUM));
        assertEquals(List.of(Achievement.RANK_DIAMOND), AchievementTracker.recordRank(p, RankTier.DIAMOND));
        assertTrue(AchievementTracker.recordRank(p, null).isEmpty());
    }

    @Test
    void collectionAchievementsNeedEverything() {
        PlayerProfile p = new PlayerProfile();
        p.addCurrency(100_000);
        Catalog.PADDLES.forEach(i -> p.purchase("paddle", i.getId(), i.getPrice()));
        Catalog.TABLES.forEach(i -> p.purchase("table", i.getId(), i.getPrice()));
        assertFalse(AchievementTracker.checkProfileState(p).contains(Achievement.FULL_KIT), "balls still missing");
        Catalog.BALLS.forEach(i -> p.purchase("ball", i.getId(), i.getPrice()));
        assertTrue(AchievementTracker.checkProfileState(p).contains(Achievement.FULL_KIT));

        Catalog.POWERUPS.subList(0, Catalog.POWERUPS.size() - 1).forEach(i -> p.purchasePowerUp(i.getId(), i.getPrice()));
        assertFalse(AchievementTracker.checkProfileState(p).contains(Achievement.ALL_POWERUPS));
        Catalog.POWERUPS.forEach(i -> p.purchasePowerUp(i.getId(), i.getPrice()));
        assertTrue(AchievementTracker.checkProfileState(p).contains(Achievement.ALL_POWERUPS));
    }

    @Test
    void progressShowsCappedCounters() {
        PlayerProfile p = new PlayerProfile();
        for (int i = 0; i < 30; i++) {
            AchievementTracker.recordMatch(p, quick(true, 10, 5));
        }
        assertEquals("25 / 25", AchievementTracker.progress(p, Achievement.WINS_25).orElseThrow());
        assertEquals("30 / 100", AchievementTracker.progress(p, Achievement.WINS_100).orElseThrow());
        assertTrue(AchievementTracker.progress(p, Achievement.SHUTOUT).isEmpty());
    }

    @Test
    void oldSavesStartWithNoAchievementsAndZeroCounters() {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        PlayerProfile old = gson.fromJson("{\"unlockedAchievements\":null,\"levelsWonOn\":null}", PlayerProfile.class);
        assertTrue(old.getUnlockedAchievements().isEmpty());
        assertTrue(old.getLevelsWonOn().isEmpty());
        assertEquals(0, old.getTotalWins());
        old.addWin("level_neon");
        assertTrue(old.unlockAchievement("FIRST_WIN"));
        PlayerProfile back = gson.fromJson(gson.toJson(old), PlayerProfile.class);
        assertEquals(1, back.getTotalWins());
        assertTrue(back.getUnlockedAchievements().contains("FIRST_WIN"));
    }

    @Test
    void everyAchievementHasTranslatedTitleAndDescriptionAndAValidSteamName() throws IOException {
        for (String file : List.of("strings_en.properties", "strings_pt_BR.properties")) {
            Properties strings = new Properties();
            try (InputStream in = AchievementTrackerTest.class.getResourceAsStream("/i18n/" + file)) {
                strings.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (Achievement a : Achievement.values()) {
                assertTrue(strings.containsKey(a.titleKey()), file + " " + a.titleKey());
                assertTrue(strings.containsKey(a.descKey()), file + " " + a.descKey());
            }
        }
        for (Achievement a : Achievement.values()) {
            assertTrue(a.steamApiName().matches("[A-Z0-9_]{1,64}"), a.steamApiName());
        }
    }
}

package com.paddleshock.tour;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.paddleshock.data.Catalog;

class WorldTourTest {

    private static final List<TourOpponent> LADDER = WorldTour.OPPONENTS;

    private static Set<String> beatenUpTo(int countBeaten) {
        return LADDER.subList(0, countBeaten).stream().map(TourOpponent::id).collect(Collectors.toSet());
    }

    @Test
    void freshProfileUnlocksOnlyTheFirstOpponent() {
        Set<String> none = Set.of();
        assertTrue(WorldTour.isUnlocked(none, LADDER.get(0)));
        for (int i = 1; i < LADDER.size(); i++) {
            assertFalse(WorldTour.isUnlocked(none, LADDER.get(i)), LADDER.get(i).id());
        }
    }

    @Test
    void beatingAnOpponentUnlocksExactlyTheNextOne() {
        for (int beaten = 1; beaten < LADDER.size(); beaten++) {
            Set<String> ids = beatenUpTo(beaten);
            for (int i = 0; i < LADDER.size(); i++) {
                assertEquals(i <= beaten, WorldTour.isUnlocked(ids, LADDER.get(i)),
                        "beaten=" + beaten + " opponent=" + i);
            }
        }
    }

    @Test
    void nextOpponentAndCompletionFollowTheLadder() {
        assertEquals(LADDER.get(0), WorldTour.nextOpponent(Set.of()).orElseThrow());
        assertEquals(LADDER.get(3), WorldTour.nextOpponent(beatenUpTo(3)).orElseThrow());
        assertFalse(WorldTour.isComplete(beatenUpTo(LADDER.size() - 1)));
        assertTrue(WorldTour.isComplete(beatenUpTo(LADDER.size())));
        assertEquals(5, WorldTour.beatenCount(beatenUpTo(5)));
    }

    @Test
    void firstWinPaysTheOpponentRewardAndReplaysPayTheNormalReward() {
        TourOpponent first = LADDER.get(0);
        assertEquals(first.firstWinReward(), WorldTour.winReward(Set.of(), first, 12));
        assertEquals(12, WorldTour.winReward(Set.of(first.id()), first, 12));
    }

    @Test
    void everyArenaHasThreeOpponentsEndingInItsBoss() {
        assertEquals(Catalog.LEVELS.size() * 3, LADDER.size());
        Catalog.LEVELS.forEach(level -> {
            List<TourOpponent> arena = WorldTour.forLevel(level.getId());
            assertEquals(3, arena.size(), level.getId());
            assertTrue(arena.get(2).boss(), level.getId());
            assertFalse(arena.get(0).boss() || arena.get(1).boss(), level.getId());
        });
    }

    @Test
    void opponentDataReferencesRealCatalogEntries() {
        Set<String> ids = new HashSet<>();
        for (TourOpponent o : LADDER) {
            assertTrue(ids.add(o.id()), "duplicate id " + o.id());
            assertTrue(Catalog.findLevel(o.levelId()).isPresent(), o.levelId());
            o.powerUpIds().forEach(p -> assertTrue(Catalog.findPowerUp(p).isPresent(), p));
            assertTrue(o.winScore() > 0 && o.firstWinReward() > 0, o.id());
            assertTrue(o.powerUpMinInterval() <= o.powerUpMaxInterval(), o.id());
        }
    }

    @Test
    void everyOpponentBlurbIsTranslated() throws IOException {
        for (String file : List.of("strings_en.properties", "strings_pt_BR.properties")) {
            Properties strings = new Properties();
            try (InputStream in = WorldTourTest.class.getResourceAsStream("/i18n/" + file)) {
                strings.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (TourOpponent o : LADDER) {
                assertTrue(strings.containsKey(o.blurbKey()), file + " missing " + o.blurbKey());
            }
        }
    }

    @Test
    void aBeatenOpponentStaysUnlockedEvenIfTheRungBeforeItIsNew() {
        TourOpponent later = LADDER.get(LADDER.size() - 2);
        assertTrue(WorldTour.isUnlocked(Set.of(later.id()), later));
    }

    @Test
    void theVoidIsStillTheFinalBoss() {
        assertEquals("the_void", LADDER.get(LADDER.size() - 1).id());
    }
}

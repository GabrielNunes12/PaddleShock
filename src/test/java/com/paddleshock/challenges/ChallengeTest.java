package com.paddleshock.challenges;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.paddleshock.achievements.MatchOutcome;
import com.paddleshock.data.Catalog;
import com.paddleshock.data.PlayerProfile;
import com.paddleshock.settings.AiDifficulty;
import com.paddleshock.ui.ChallengeText;

/** See docs/specs/07-challenges.md. */
class ChallengeTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 21); // a Monday

    private static MatchOutcome win(int mine, int theirs, String level) {
        return new MatchOutcome(MatchOutcome.Kind.QUICK_MATCH, true, mine, theirs, level, AiDifficulty.NORMAL, 0);
    }

    private static MatchOutcome loss(int mine) {
        return new MatchOutcome(MatchOutcome.Kind.QUICK_MATCH, false, mine, 10, "level_classic", AiDifficulty.NORMAL, 0);
    }

    /** The first day from START whose daily challenge is of {@code kind}. */
    private static LocalDate dayWithDaily(ChallengeKind kind) {
        for (LocalDate d = START; d.isBefore(START.plusYears(1)); d = d.plusDays(1)) {
            if (ChallengeGenerator.daily(d).kind() == kind) {
                return d;
            }
        }
        throw new AssertionError("no daily of kind " + kind + " within a year");
    }

    @Test
    void theSameDateAlwaysGivesTheSameChallenges() {
        assertEquals(ChallengeGenerator.daily(START), ChallengeGenerator.daily(START));
        for (int i = 0; i < 7; i++) {
            assertEquals(ChallengeGenerator.weekly(START), ChallengeGenerator.weekly(START.plusDays(i)), "day " + i);
        }
        assertNotEquals(ChallengeGenerator.weekly(START).id(), ChallengeGenerator.weekly(START.plusDays(7)).id());
        assertEquals(DayOfWeek.MONDAY, START.getDayOfWeek());
    }

    @Test
    void dailiesVaryAndEveryKindShowsUpWithinAYear() {
        Set<ChallengeKind> dailyKinds = new HashSet<>();
        Set<ChallengeKind> weeklyKinds = new HashSet<>();
        for (int i = 0; i < 400; i++) {
            LocalDate d = START.plusDays(i);
            dailyKinds.add(ChallengeGenerator.daily(d).kind());
            weeklyKinds.add(ChallengeGenerator.weekly(d).kind());
        }
        assertEquals(Set.of(ChallengeKind.values()), dailyKinds);
        assertEquals(Set.of(ChallengeKind.values()), weeklyKinds);
    }

    @Test
    void everyGeneratedChallengeIsAchievableAndSensible() {
        for (int i = 0; i < 400; i++) {
            for (Challenge c : ChallengeGenerator.current(START.plusDays(i))) {
                assertTrue(c.target() > 0 && c.reward() > 0, c.toString());
                if (c.kind() == ChallengeKind.WINS_ON_ARENA) {
                    assertTrue(Catalog.findLevel(c.levelId()).isPresent(), c.toString());
                } else {
                    assertEquals(null, c.levelId(), c.toString());
                }
            }
            Challenge daily = ChallengeGenerator.daily(START.plusDays(i));
            Challenge weekly = ChallengeGenerator.weekly(START.plusDays(i));
            assertTrue(weekly.reward() > daily.reward(), "a weekly always pays more than a daily");
        }
    }

    @Test
    void progressRulesPerKind() {
        assertEquals(1, ChallengeKind.WINS.progressFrom(win(10, 9, "level_neon"), null));
        assertEquals(0, ChallengeKind.WINS.progressFrom(loss(9), null));
        assertEquals(1, ChallengeKind.WINS_ON_ARENA.progressFrom(win(10, 2, "level_neon"), "level_neon"));
        assertEquals(0, ChallengeKind.WINS_ON_ARENA.progressFrom(win(10, 2, "level_space"), "level_neon"));
        assertEquals(1, ChallengeKind.BIG_WINS.progressFrom(win(10, 5, null), null));
        assertEquals(0, ChallengeKind.BIG_WINS.progressFrom(win(10, 6, null), null));
        assertEquals(7, ChallengeKind.POINTS.progressFrom(loss(7), null), "points count even in a loss");
        MatchOutcome hardWin = new MatchOutcome(MatchOutcome.Kind.QUICK_MATCH, true, 10, 3, null, AiDifficulty.HARD, 0);
        assertEquals(1, ChallengeKind.HARD_AI_WINS.progressFrom(hardWin, null));
        assertEquals(0, ChallengeKind.HARD_AI_WINS.progressFrom(win(10, 3, null), null));
        MatchOutcome tourWin = new MatchOutcome(MatchOutcome.Kind.WORLD_TOUR, true, 7, 3, "level_classic", null, 0);
        assertEquals(1, ChallengeKind.TOUR_WINS.progressFrom(tourWin, null));
        assertEquals(0, ChallengeKind.TOUR_WINS.progressFrom(win(10, 3, null), null));
    }

    @Test
    void completingAChallengePaysItsRewardExactlyOnce() {
        LocalDate day = dayWithDaily(ChallengeKind.WINS);
        Challenge daily = ChallengeGenerator.daily(day);
        PlayerProfile profile = new PlayerProfile();
        int start = profile.getCurrency();

        for (int i = 1; i < daily.target(); i++) {
            assertFalse(ChallengeTracker.recordMatch(profile, win(10, 4, "level_classic"), day).contains(daily));
        }
        assertEquals(start, profile.getCurrency(), "nothing paid before the target");
        assertTrue(ChallengeTracker.recordMatch(profile, win(10, 4, "level_classic"), day).contains(daily));
        assertTrue(ChallengeTracker.isComplete(profile, daily));
        int afterPayout = profile.getCurrency();
        assertTrue(afterPayout >= start + daily.reward());

        assertFalse(ChallengeTracker.recordMatch(profile, win(10, 4, "level_classic"), day).contains(daily));
        assertEquals(daily.target(), ChallengeTracker.progress(profile, daily), "progress is capped at the target");
    }

    @Test
    void aNewDayStartsFreshAndOldProgressIsPruned() {
        LocalDate day = dayWithDaily(ChallengeKind.POINTS);
        PlayerProfile profile = new PlayerProfile();
        ChallengeTracker.recordMatch(profile, loss(6), day);
        assertEquals(6, profile.getChallengeProgress(ChallengeGenerator.daily(day).id()));

        LocalDate next = day.plusDays(1);
        ChallengeTracker.recordMatch(profile, loss(0), next);
        assertEquals(0, profile.getChallengeProgress(ChallengeGenerator.daily(day).id()), "yesterday's entry is gone");
    }

    @Test
    void everyDescriptionIsTranslatedAndFullyFormatted() throws IOException {
        for (String file : List.of("strings_en.properties", "strings_pt_BR.properties")) {
            Properties strings = new Properties();
            try (InputStream in = ChallengeTest.class.getResourceAsStream("/i18n/" + file)) {
                strings.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (ChallengeKind kind : ChallengeKind.values()) {
                assertTrue(strings.containsKey(kind.descriptionKey()), file + " " + kind);
                assertTrue(strings.containsKey(kind.descriptionKey() + ".one"), file + " " + kind + ".one");
            }
        }
        for (int i = 0; i < 400; i++) {
            for (Challenge c : ChallengeGenerator.current(START.plusDays(i))) {
                String text = ChallengeText.describe(c);
                assertFalse(text.contains("%") || text.contains("?"), text);
            }
        }
    }
}

package com.paddleshock.challenges;

import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.Random;

import com.paddleshock.data.Catalog;

/**
 * Picks the day's and the week's challenge deterministically from the date (seeded by the epoch
 * day / ISO week), so everyone gets the same ones and no server is involved. Every template only
 * needs things every player has (any paddle, free arenas), so a new player can always finish them.
 */
public final class ChallengeGenerator {

    private ChallengeGenerator() {
    }

    private record Template(ChallengeKind kind, int target, int reward) {
    }

    private static final List<Template> DAILY = List.of(
            new Template(ChallengeKind.WINS, 2, 35),
            new Template(ChallengeKind.WINS_ON_ARENA, 1, 35),
            new Template(ChallengeKind.BIG_WINS, 1, 40),
            new Template(ChallengeKind.POINTS, 25, 35),
            new Template(ChallengeKind.HARD_AI_WINS, 1, 50),
            new Template(ChallengeKind.TOUR_WINS, 1, 35));

    private static final List<Template> WEEKLY = List.of(
            new Template(ChallengeKind.WINS, 10, 150),
            new Template(ChallengeKind.WINS_ON_ARENA, 4, 160),
            new Template(ChallengeKind.BIG_WINS, 4, 170),
            new Template(ChallengeKind.POINTS, 120, 150),
            new Template(ChallengeKind.HARD_AI_WINS, 3, 200),
            new Template(ChallengeKind.TOUR_WINS, 5, 160));

    public static Challenge daily(LocalDate date) {
        return pick(DAILY, new Random(date.toEpochDay() * 31L + 7L), "D" + date, false);
    }

    public static Challenge weekly(LocalDate date) {
        int year = date.get(IsoFields.WEEK_BASED_YEAR);
        int week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return pick(WEEKLY, new Random(year * 100L + week), String.format("W%d-%02d", year, week), true);
    }

    /** Today's daily and this week's weekly, in that order. */
    public static List<Challenge> current(LocalDate date) {
        return List.of(daily(date), weekly(date));
    }

    private static Challenge pick(List<Template> templates, Random random, String id, boolean weekly) {
        Template template = templates.get(random.nextInt(templates.size()));
        String levelId = template.kind() == ChallengeKind.WINS_ON_ARENA
                ? Catalog.LEVELS.get(random.nextInt(Catalog.LEVELS.size())).getId()
                : null;
        return new Challenge(id, weekly, template.kind(), levelId, template.target(), template.reward());
    }
}

package com.paddleshock.challenges;

import com.paddleshock.achievements.MatchOutcome;
import com.paddleshock.settings.AiDifficulty;

/**
 * What a challenge asks for, and how much one finished match advances it. Description strings
 * are i18n keys {@code challenge.<lowercase name>} with the target (and arena name, for
 * {@link #WINS_ON_ARENA}) as format arguments.
 */
public enum ChallengeKind {
    WINS,
    WINS_ON_ARENA,
    BIG_WINS,
    POINTS,
    HARD_AI_WINS,
    TOUR_WINS;

    /** A "big win" is by at least this many points. */
    public static final int BIG_WIN_MARGIN = 5;

    /** How far {@code outcome} advances a challenge of this kind ({@code levelId} for
     *  {@link #WINS_ON_ARENA}, ignored otherwise). */
    public int progressFrom(MatchOutcome outcome, String levelId) {
        boolean won = outcome.won();
        return switch (this) {
            case WINS -> won ? 1 : 0;
            case WINS_ON_ARENA -> won && levelId != null && levelId.equals(outcome.levelId()) ? 1 : 0;
            case BIG_WINS -> won && outcome.playerScore() - outcome.opponentScore() >= BIG_WIN_MARGIN ? 1 : 0;
            case POINTS -> Math.max(0, outcome.playerScore());
            case HARD_AI_WINS -> won && outcome.kind() == MatchOutcome.Kind.QUICK_MATCH
                    && outcome.aiDifficulty() == AiDifficulty.HARD ? 1 : 0;
            case TOUR_WINS -> won && outcome.kind() == MatchOutcome.Kind.WORLD_TOUR ? 1 : 0;
        };
    }

    public String descriptionKey() {
        return "challenge." + name().toLowerCase();
    }
}

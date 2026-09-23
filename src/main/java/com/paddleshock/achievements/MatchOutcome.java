package com.paddleshock.achievements;

import com.paddleshock.settings.AiDifficulty;

/**
 * A finished match, as far as achievements care.
 *
 * @param kind          what sort of match it was
 * @param levelId       arena it was played in ({@code null} if unknown, e.g. a joiner)
 * @param aiDifficulty  quick-match AI difficulty, {@code null} for anything else
 * @param powerUpsUsed  power-ups the local player activated during this match
 */
public record MatchOutcome(Kind kind, boolean won, int playerScore, int opponentScore, String levelId,
        AiDifficulty aiDifficulty, int powerUpsUsed) {

    public enum Kind { QUICK_MATCH, WORLD_TOUR, ONLINE, LOCAL_VERSUS }
}

package com.paddleshock.app;

import java.util.random.RandomGenerator;

import com.paddleshock.GameConstants;

/** Credits paid for a completed match: the random win range on a win, a flat consolation on a loss. */
public final class MatchRewards {

    private MatchRewards() {
    }

    public static int forResult(boolean won, RandomGenerator random) {
        if (!won) {
            return GameConstants.LOSS_REWARD;
        }
        return random.nextInt(GameConstants.MATCH_REWARD_MIN, GameConstants.MATCH_REWARD_MAX + 1);
    }
}

package com.paddleshock.tour;

import java.util.List;

import com.jme3.math.ColorRGBA;

/**
 * One World Tour opponent. Names are proper nouns (not translated); the one-line personality
 * blurb is the i18n key {@code tour.opp.<id>}.
 *
 * @param levelId          arena the match is always played in (a {@code Catalog.LEVELS} id)
 * @param maxSpeed         AI paddle speed, world units/second (quick match NORMAL is 6.5)
 * @param sloppiness       AI tracking error amplitude in world units (0 = perfect tracking)
 * @param powerUpIds       the AI's own power-up kit ({@code Catalog.POWERUPS} ids); may be empty
 * @param powerUpMinInterval seconds between power-up attempts, lower bound
 * @param powerUpMaxInterval seconds between power-up attempts, upper bound
 * @param paddleColor      the AI paddle's tint
 * @param paddleSize       AI paddle radius multiplier
 * @param winScore         points needed to win this match
 * @param firstWinReward   credits paid the first time this opponent is beaten
 * @param boss             the arena's final opponent
 */
public record TourOpponent(String id, String name, String levelId, float maxSpeed, float sloppiness,
        List<String> powerUpIds, float powerUpMinInterval, float powerUpMaxInterval,
        ColorRGBA paddleColor, float paddleSize, int winScore, int firstWinReward, boolean boss) {

    public String blurbKey() {
        return "tour.opp." + id;
    }
}

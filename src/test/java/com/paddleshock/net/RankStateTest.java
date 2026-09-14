package com.paddleshock.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Covers {@link RankState#nextPromoLabel()} - the pure logic behind the pre-match "Promotion
 *  match! Win to advance to X" callout. */
class RankStateTest {

    @Test
    void withinTierAdvancesToBetterDivision() {
        RankState state = RankState.fromRelay("SILVER", 3, 40, 5, 2, 0, false, false, null);
        assertEquals("Silver II", state.nextPromoLabel());
    }

    @Test
    void divisionOneAdvancesToNextTierDivisionFour() {
        RankState state = RankState.fromRelay("SILVER", 1, 90, 5, 2, 0, false, false, null);
        assertEquals("Gold IV", state.nextPromoLabel());
    }

    @Test
    void topOfLadderHasNoNextLabel() {
        RankState state = RankState.fromRelay("DIAMOND", 1, 100, 5, 2, 0, false, false, null);
        assertNull(state.nextPromoLabel());
    }
}

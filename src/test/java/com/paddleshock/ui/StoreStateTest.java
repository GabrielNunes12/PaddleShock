package com.paddleshock.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Covers {@link StoreState#estimateWinsNeeded} - the only pure/testable logic added for the
 *  store's "X more wins to unlock" framing (the rest is Lemur UI construction). */
class StoreStateTest {

    @Test
    void alreadyAffordableNeedsNoWins() {
        assertEquals(0, StoreState.estimateWinsNeeded(100, 100));
        assertEquals(0, StoreState.estimateWinsNeeded(100, 150));
    }

    @Test
    void roundsUpToNextWholeWin() {
        // Avg reward is (10 + 15 + 1) / 2 = 13. Shortfall of 1 still needs a full extra win.
        assertEquals(1, StoreState.estimateWinsNeeded(101, 100));
        assertEquals(1, StoreState.estimateWinsNeeded(113, 100));
        assertEquals(2, StoreState.estimateWinsNeeded(114, 100));
    }

    @Test
    void scalesWithLargerShortfalls() {
        // Shortfall of 120 credits at ~13/win -> ceil(120/13) = 10.
        assertEquals(10, StoreState.estimateWinsNeeded(120, 0));
    }
}

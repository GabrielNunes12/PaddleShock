package com.paddleshock.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** Exercises {@link MatchHistoryEntry#formatResult()}, the only non-trivial logic on this
 *  otherwise-plain value type. */
class MatchHistoryEntryTest {

    @Test
    void formatsAWinWithNoLpChange() {
        MatchHistoryEntry entry = new MatchHistoryEntry(0L, "vs AI", 10, 4, true, 0);
        assertEquals("WIN 10-4", entry.formatResult());
    }

    @Test
    void formatsALossWithNoLpChange() {
        MatchHistoryEntry entry = new MatchHistoryEntry(0L, "LAN Host", 6, 10, false, 0);
        assertEquals("LOSS 6-10", entry.formatResult());
    }

    @Test
    void formatsAWinWithPositiveLpChange() {
        MatchHistoryEntry entry = new MatchHistoryEntry(0L, "Ranked", 10, 8, true, 21);
        assertEquals("WIN 10-8 (+21 LP)", entry.formatResult());
    }

    @Test
    void formatsALossWithNegativeLpChange() {
        MatchHistoryEntry entry = new MatchHistoryEntry(0L, "Ranked", 6, 10, false, -14);
        assertEquals("LOSS 6-10 (-14 LP)", entry.formatResult());
    }

    @Test
    void plainGettersRoundTripConstructorArguments() {
        MatchHistoryEntry entry = new MatchHistoryEntry(12345L, "LAN Join", 3, 10, false, 0);
        assertEquals(12345L, entry.getTimestamp());
        assertEquals("LAN Join", entry.getMode());
        assertEquals(3, entry.getPlayerScore());
        assertEquals(10, entry.getOpponentScore());
        assertFalse(entry.isWon());
        assertEquals(0, entry.getLpChange());
    }
}

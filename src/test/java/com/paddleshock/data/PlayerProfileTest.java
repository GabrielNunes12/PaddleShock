package com.paddleshock.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Exercises {@link PlayerProfile}'s display-name fallback/edit and match-history append+cap
 *  behavior added for the PROFILE screen - see {@link MatchHistoryEntry}. */
class PlayerProfileTest {

    @Test
    void displayNameDefaultsToPlayerAndFallsBackOnBlank() {
        PlayerProfile profile = new PlayerProfile();
        assertEquals("Player", profile.getDisplayName());

        profile.setDisplayName("Gabriel");
        assertEquals("Gabriel", profile.getDisplayName());

        profile.setDisplayName("   ");
        assertEquals("Player", profile.getDisplayName());

        profile.setDisplayName(null);
        assertEquals("Player", profile.getDisplayName());

        profile.setDisplayName("  Trimmed  ");
        assertEquals("Trimmed", profile.getDisplayName());
    }

    @Test
    void matchHistoryStartsEmpty() {
        PlayerProfile profile = new PlayerProfile();
        assertTrue(profile.getMatchHistory().isEmpty());
    }

    @Test
    void addMatchHistoryEntryInsertsMostRecentFirst() {
        PlayerProfile profile = new PlayerProfile();
        profile.addMatchHistoryEntry(new MatchHistoryEntry(1000L, "vs AI", 10, 4, true, 0));
        profile.addMatchHistoryEntry(new MatchHistoryEntry(2000L, "Ranked", 6, 10, false, -14));

        assertEquals(2, profile.getMatchHistory().size());
        assertEquals(2000L, profile.getMatchHistory().get(0).getTimestamp());
        assertEquals(1000L, profile.getMatchHistory().get(1).getTimestamp());
    }

    @Test
    void addMatchHistoryEntryCapsAtFiftyDroppingOldest() {
        PlayerProfile profile = new PlayerProfile();
        for (int i = 0; i < 60; i++) {
            profile.addMatchHistoryEntry(new MatchHistoryEntry(i, "vs AI", 10, i % 10, true, 0));
        }

        assertEquals(50, profile.getMatchHistory().size());
        // Most recent (timestamp 59) first, oldest kept is timestamp 10 (0-9 dropped).
        assertEquals(59L, profile.getMatchHistory().get(0).getTimestamp());
        assertEquals(10L, profile.getMatchHistory().get(49).getTimestamp());
    }

    @Test
    void getMatchHistoryReturnsAnImmutableSnapshot() {
        PlayerProfile profile = new PlayerProfile();
        profile.addMatchHistoryEntry(new MatchHistoryEntry(1L, "vs AI", 10, 0, true, 0));
        var snapshot = profile.getMatchHistory();

        profile.addMatchHistoryEntry(new MatchHistoryEntry(2L, "vs AI", 10, 1, true, 0));

        assertEquals(1, snapshot.size(), "earlier snapshot should not see a later mutation");
        assertEquals(2, profile.getMatchHistory().size());
    }
}

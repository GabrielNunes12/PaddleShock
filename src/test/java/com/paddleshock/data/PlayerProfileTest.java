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

    @Test
    void rivalsStartEmptyAndLastRewardedSeasonDefaultsToUnclaimed() {
        PlayerProfile profile = new PlayerProfile();
        assertTrue(profile.getRivals().isEmpty());
        assertEquals(-1, profile.getLastRewardedSeason());
    }

    @Test
    void recordRivalResultCreatesANewEntry() {
        PlayerProfile profile = new PlayerProfile();
        profile.recordRivalResult("opp-1", "Nemesis", true);

        var rivals = profile.getRivals();
        assertEquals(1, rivals.size());
        RivalRecord rival = rivals.get(0);
        assertEquals("opp-1", rival.getOpponentPlayerId());
        assertEquals("Nemesis", rival.getOpponentDisplayNameHint());
        assertEquals(1, rival.getWins());
        assertEquals(0, rival.getLosses());
        assertEquals("1W-0L", rival.formatRecord());
    }

    @Test
    void recordRivalResultIncrementsAnExistingEntry() {
        PlayerProfile profile = new PlayerProfile();
        profile.recordRivalResult("opp-1", "Nemesis", true);
        profile.recordRivalResult("opp-1", "Nemesis", false);
        profile.recordRivalResult("opp-1", "Nemesis", true);

        var rivals = profile.getRivals();
        assertEquals(1, rivals.size(), "same opponent id should update one entry, not create duplicates");
        RivalRecord rival = rivals.get(0);
        assertEquals(2, rival.getWins());
        assertEquals(1, rival.getLosses());
        assertEquals("2W-1L", rival.formatRecord());
    }

    @Test
    void recordRivalResultTracksMultipleDistinctRivalsSortedByGamesPlayed() {
        PlayerProfile profile = new PlayerProfile();
        profile.recordRivalResult("opp-few", "Casual", true);
        profile.recordRivalResult("opp-many", "Regular", true);
        profile.recordRivalResult("opp-many", "Regular", true);
        profile.recordRivalResult("opp-many", "Regular", false);

        var rivals = profile.getRivals();
        assertEquals(2, rivals.size());
        assertEquals("opp-many", rivals.get(0).getOpponentPlayerId(), "most-played opponent should sort first");
        assertEquals("opp-few", rivals.get(1).getOpponentPlayerId());
    }

    @Test
    void recordRivalResultIgnoresANullOrBlankOpponentId() {
        PlayerProfile profile = new PlayerProfile();
        profile.recordRivalResult(null, "Ghost", true);
        profile.recordRivalResult("  ", "Ghost", true);
        assertTrue(profile.getRivals().isEmpty());
    }

    @Test
    void recordRivalResultUsesShortIdFallbackWhenNoDisplayNameHintWasEverCaptured() {
        PlayerProfile profile = new PlayerProfile();
        profile.recordRivalResult("12345678-abcd-abcd-abcd-abcdefabcdef", null, true);
        RivalRecord rival = profile.getRivals().get(0);
        assertEquals("Player-12345678", rival.displayName());
    }
}

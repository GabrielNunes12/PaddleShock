package com.paddleshock.net;

import java.io.IOException;
import java.util.List;

import com.paddleshock.net.RankClient.LeaderboardEntry;
import com.paddleshock.net.RankClient.MatchReportResult;

/**
 * Ranked ladder actions - see {@link RankClient} (the default implementation) for the actual
 * network behavior/docs. Exists so callers depend on an interface rather than a static class,
 * making them unit-testable/swappable.
 */
public interface RankService {

    RankState getRank(String playerId) throws IOException;

    MatchReportResult reportMatchResult(String matchId, String hostPlayerId, String joinerPlayerId,
            boolean hostWon, String lobbyCode) throws IOException;

    List<LeaderboardEntry> getLeaderboard(int limit) throws IOException;
}

package com.paddleshock.net;

import java.io.IOException;

import com.paddleshock.net.TournamentClient.State;

/**
 * Tournament-bracket actions - see {@link TournamentClient} (the default implementation) for the
 * actual network behavior/docs. Exists so callers depend on an interface rather than a static
 * class, making them unit-testable/swappable.
 */
public interface TournamentService {

    String createTournament(String hostPlayerId, int maxPlayers) throws IOException;

    State joinTournament(String code, String playerId, String displayNameHint) throws IOException;

    State startTournament(String code, String hostPlayerId) throws IOException;

    State setTournamentMatchLobbyCode(String code, int roundIndex, int matchIndex, String playerId,
            String lobbyCode) throws IOException;

    State reportTournamentMatchResult(String code, int roundIndex, int matchIndex, String winnerPlayerId,
            String reporterPlayerId) throws IOException;

    State getTournamentState(String code) throws IOException;
}

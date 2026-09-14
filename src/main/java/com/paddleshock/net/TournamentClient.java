package com.paddleshock.net;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Client for the tournament-bracket actions on the same AWS Lambda backend as {@link RankClient}/
 * {@link LobbyClient} (same Function URL - see {@code aws/README.md}). Follows the exact same
 * plain-{@code HttpURLConnection}-less (actually {@code java.net.http.HttpClient}, matching
 * {@code RankClient}) POST-with-an-"action"-field/Gson-parsed-response pattern as the rest of this
 * package; every method here makes a blocking HTTPS call and must be run off the render thread
 * (see how {@code MultiplayerState}/{@code TournamentState} wrap these in background threads).
 *
 * <p>Actually playing a bracket match reuses the existing lobby-code create/join flow
 * ({@link LobbyClient} via {@code NetHost}/{@code NetClient}) completely unchanged - this class is
 * only the orchestration layer around that: creating/joining a tournament, starting it, publishing
 * a match's lobby code once its host is ready, reporting who won a pairing, and polling the overall
 * bracket state.
 */
public final class TournamentClient {

    private static final String ENDPOINT = "https://2mjcwpgb6sesrjy36nm6qxdmpu0wbvrb.lambda-url.us-east-1.on.aws/";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();
    private static final Gson GSON = new Gson();

    private TournamentClient() {
    }

    /** Creates a new tournament for {@code maxPlayers} (4 or 8), owned by {@code hostPlayerId} -
     *  returns the short code to share with other players. Blocking network call. */
    public static String createTournament(String hostPlayerId, int maxPlayers) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("action", "createTournament");
        body.addProperty("hostPlayerId", hostPlayerId);
        body.addProperty("maxPlayers", maxPlayers);
        JsonObject response = post(body);
        return response.get("code").getAsString();
    }

    /** Joins (or, if already joined, idempotently re-fetches) the tournament {@code code} as
     *  {@code playerId}, and returns the full tournament state. Blocking network call. */
    public static State joinTournament(String code, String playerId, String displayNameHint) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("action", "joinTournament");
        body.addProperty("code", code);
        body.addProperty("playerId", playerId);
        body.addProperty("displayNameHint", displayNameHint);
        return parseState(post(body));
    }

    /** Host-only: starts the tournament once it's exactly full, populating the bracket. Blocking
     *  network call. */
    public static State startTournament(String code, String hostPlayerId) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("action", "startTournament");
        body.addProperty("code", code);
        body.addProperty("hostPlayerId", hostPlayerId);
        return parseState(post(body));
    }

    /** Called only by the {@code p1} side of a pairing, once it has created a real lobby (via the
     *  existing {@code NetHost.registerLobby}) for that round's match - publishes the lobby code
     *  into the bracket so {@code p2} can find and join it. Blocking network call. */
    public static State setTournamentMatchLobbyCode(String code, int roundIndex, int matchIndex,
            String playerId, String lobbyCode) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("action", "setTournamentMatchLobbyCode");
        body.addProperty("code", code);
        body.addProperty("roundIndex", roundIndex);
        body.addProperty("matchIndex", matchIndex);
        body.addProperty("playerId", playerId);
        body.addProperty("lobbyCode", lobbyCode);
        return parseState(post(body));
    }

    /** Reports the winner of one bracket pairing - idempotent, so either side (or both) may call
     *  this once the match ends with no coordination needed about who reports first. Blocking
     *  network call. */
    public static State reportTournamentMatchResult(String code, int roundIndex, int matchIndex,
            String winnerPlayerId, String reporterPlayerId) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("action", "reportTournamentMatchResult");
        body.addProperty("code", code);
        body.addProperty("roundIndex", roundIndex);
        body.addProperty("matchIndex", matchIndex);
        body.addProperty("winnerPlayerId", winnerPlayerId);
        body.addProperty("reporterPlayerId", reporterPlayerId);
        return parseState(post(body));
    }

    /** Fetches the current tournament state - what the waiting-room/bracket views poll. Blocking
     *  network call. */
    public static State getTournamentState(String code) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("action", "getTournamentState");
        body.addProperty("code", code);
        return parseState(post(body));
    }

    private static State parseState(JsonObject json) {
        return GSON.fromJson(json, State.class);
    }

    /** Full tournament state, exactly the shape returned by every action above (bar
     *  {@code createTournament}, which only returns a bare code). */
    public static final class State {
        private String code;
        private String hostPlayerId;
        private int maxPlayers;
        private List<PlayerEntry> players;
        private String status;
        private Bracket bracket;
        private String champion;

        public String getCode() {
            return code;
        }

        public String getHostPlayerId() {
            return hostPlayerId;
        }

        public int getMaxPlayers() {
            return maxPlayers;
        }

        public List<PlayerEntry> getPlayers() {
            return players == null ? new ArrayList<>() : players;
        }

        /** "OPEN", "IN_PROGRESS", or "COMPLETE". */
        public String getStatus() {
            return status;
        }

        public boolean isOpen() {
            return "OPEN".equals(status);
        }

        public boolean isInProgress() {
            return "IN_PROGRESS".equals(status);
        }

        public boolean isComplete() {
            return "COMPLETE".equals(status);
        }

        public Bracket getBracket() {
            return bracket;
        }

        public String getChampion() {
            return champion;
        }

        public boolean isFull() {
            return getPlayers().size() >= maxPlayers;
        }
    }

    public static final class PlayerEntry {
        private String playerId;
        private String displayNameHint;

        public String getPlayerId() {
            return playerId;
        }

        public String getDisplayNameHint() {
            return displayNameHint;
        }

        /** Short, human-friendly stand-in when no display name hint was sent/stored - matching
         *  {@code RankClient.LeaderboardEntry.shortId()}'s style. */
        public String displayName() {
            if (displayNameHint != null && !displayNameHint.isBlank()) {
                return displayNameHint;
            }
            String id = playerId == null ? "" : playerId.replace("-", "");
            return "Player-" + (id.length() >= 8 ? id.substring(0, 8) : id).toUpperCase();
        }
    }

    public static final class Bracket {
        private List<List<Match>> rounds;

        public List<List<Match>> getRounds() {
            return rounds == null ? new ArrayList<>() : rounds;
        }
    }

    public static final class Match {
        private String p1;
        private String p2;
        private String winner;
        private String lobbyCode;

        public String getP1() {
            return p1;
        }

        public String getP2() {
            return p2;
        }

        public String getWinner() {
            return winner;
        }

        public String getLobbyCode() {
            return lobbyCode;
        }

        public boolean isDecided() {
            return winner != null;
        }
    }

    private static JsonObject post(JsonObject body) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .timeout(TIMEOUT)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for the tournament service", e);
        }

        JsonObject parsed;
        try {
            parsed = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("tournament service returned a malformed response");
        }
        if (parsed.has("error")) {
            throw new IOException(parsed.get("error").getAsString());
        }
        return parsed;
    }
}

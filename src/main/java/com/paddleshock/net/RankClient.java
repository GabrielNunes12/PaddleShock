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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.paddleshock.rank.RankTier;

/**
 * Client for the ranked ladder actions on the same AWS Lambda backend as {@link LobbyClient} (see
 * {@code aws/README.md}). The host reports match results for both players in one call, since the
 * host already owns the authoritative match simulation - the same trust boundary the P2P
 * architecture already relies on for the match itself, not a new one.
 */
public final class RankClient {

    private static final String ENDPOINT = "https://2mjcwpgb6sesrjy36nm6qxdmpu0wbvrb.lambda-url.us-east-1.on.aws/";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();
    private static final Gson GSON = new Gson();

    private RankClient() {
    }

    /** Fetches {@code playerId}'s current rank (a fresh Copper IV/0 LP record if they've never
     *  played a ranked match). Blocking network call - run off the render thread. */
    public static RankState getRank(String playerId) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("action", "getRank");
        body.addProperty("playerId", playerId);
        return GSON.fromJson(post(body), RankState.class);
    }

    /** Reports the outcome of a ranked match for both players at once (host-authoritative - see
     *  class docs) and returns each player's updated rank plus what changed. {@code matchId}
     *  should be a fresh random id per match; a retried report for the same id is a no-op rather
     *  than double-applying the result. Blocking network call - run off the render thread. */
    public static MatchReportResult reportMatchResult(String matchId, String hostPlayerId,
            String joinerPlayerId, boolean hostWon) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("action", "reportMatchResult");
        body.addProperty("matchId", matchId);
        body.addProperty("hostPlayerId", hostPlayerId);
        body.addProperty("joinerPlayerId", joinerPlayerId);
        body.addProperty("hostWon", hostWon);
        return GSON.fromJson(post(body), MatchReportResult.class);
    }

    /** Fetches the top {@code limit} entries on the ranked ladder, best-to-worst (the server sorts
     *  by tier desc, then division asc/better, then LP desc). Blocking network call - run off the
     *  render thread. */
    public static List<LeaderboardEntry> getLeaderboard(int limit) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("action", "getLeaderboard");
        body.addProperty("limit", limit);
        JsonObject response = post(body);

        List<LeaderboardEntry> entries = new ArrayList<>();
        JsonArray array = response.has("entries") ? response.getAsJsonArray("entries") : new JsonArray();
        for (JsonElement element : array) {
            entries.add(GSON.fromJson(element, LeaderboardEntry.class));
        }
        return entries;
    }

    /** One row of the {@code getLeaderboard} response. There is no player display-name/username
     *  system anywhere in this codebase (profiles are keyed purely by a generated UUID - see
     *  {@link com.paddleshock.data.PlayerProfile#getPlayerId()}), so entries are shown by a
     *  shortened form of that id rather than blocking this screen on a feature that doesn't exist
     *  yet - a real display-name system is a natural follow-up. */
    public static final class LeaderboardEntry {
        private String playerId;
        private String tier;
        private int division;
        private int lp;
        private int wins;
        private int losses;

        public String getPlayerId() {
            return playerId;
        }

        public RankTier getTier() {
            return RankTier.valueOf(tier);
        }

        public int getDivision() {
            return division;
        }

        public int getLp() {
            return lp;
        }

        public int getWins() {
            return wins;
        }

        public int getLosses() {
            return losses;
        }

        /** Shortened, anonymized stand-in for a real display name: "Player-" plus the first 8
         *  characters of the player's UUID. */
        public String shortId() {
            String id = playerId == null ? "" : playerId.replace("-", "");
            return "Player-" + (id.length() >= 8 ? id.substring(0, 8) : id).toUpperCase();
        }

        /** "Player-XXXXXXXX - Silver II - 65 LP (12W-8L)" style summary row, matching
         *  {@link RankState#formatLabel()}'s style. */
        public String formatLabel() {
            return shortId() + " - " + getTier().getDisplayName() + " " + RankTier.divisionToRoman(division)
                    + " - " + lp + " LP (" + wins + "W-" + losses + "L)";
        }
    }

    public static final class MatchReportResult {
        private RankState host;
        private RankState joiner;

        public RankState getHost() {
            return host;
        }

        public RankState getJoiner() {
            return joiner;
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
            throw new IOException("interrupted while waiting for the rank service", e);
        }

        JsonObject parsed;
        try {
            parsed = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("rank service returned a malformed response");
        }
        if (parsed.has("error")) {
            throw new IOException(parsed.get("error").getAsString());
        }
        return parsed;
    }
}

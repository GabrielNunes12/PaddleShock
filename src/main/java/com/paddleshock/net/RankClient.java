package com.paddleshock.net;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
     *  than double-applying the result. {@code lobbyCode} should be {@code NetHost.getLobbyCode()}
     *  for a lobby-code (internet) match, so the backend can verify this report is backed by a
     *  real session between these two players instead of trusting the ids outright; pass
     *  {@code null} for a direct IP:port LAN match, which never registered a lobby session in the
     *  first place and so can't be verified this way (see {@code aws/README.md} "Trust model" for
     *  that accepted tradeoff). Blocking network call - run off the render thread. */
    public static MatchReportResult reportMatchResult(String matchId, String hostPlayerId,
            String joinerPlayerId, boolean hostWon, String lobbyCode) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("action", "reportMatchResult");
        body.addProperty("matchId", matchId);
        body.addProperty("hostPlayerId", hostPlayerId);
        body.addProperty("joinerPlayerId", joinerPlayerId);
        body.addProperty("hostWon", hostWon);
        if (lobbyCode != null) {
            body.addProperty("code", lobbyCode);
        }
        return GSON.fromJson(post(body), MatchReportResult.class);
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

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

/**
 * Client for the direct-invite mailbox actions on the same AWS Lambda backend as {@link RankClient}/
 * {@link TournamentClient}/{@link LobbyClient} (same Function URL - see {@code aws/README.md}).
 * Follows the exact same plain-{@code HttpClient} POST-with-an-"action"-field/Gson-parsed-response
 * pattern as the rest of this package; every method here makes a blocking HTTPS call and must be
 * run off the render thread.
 *
 * <p>The friends list itself is entirely local (see {@code PlayerProfile}) - this class only
 * covers the small "invite a friend into my current lobby" mailbox: sending an invite, polling for
 * pending ones, and dismissing them once seen/acted on.
 */
public final class InviteClient implements InviteService {

    private static final String ENDPOINT = "https://2mjcwpgb6sesrjy36nm6qxdmpu0wbvrb.lambda-url.us-east-1.on.aws/";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();
    private static final Gson GSON = new Gson();

    public InviteClient() {
    }

    /** Sends an invite from {@code fromPlayerId} to {@code toPlayerId}, inviting them into
     *  {@code lobbyCode}. {@code fromNameHint} may be {@code null}. Blocking network call - run off
     *  the render thread. */
    @Override
    public void sendInvite(String fromPlayerId, String fromNameHint, String toPlayerId, String lobbyCode)
            throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("action", "sendInvite");
        body.addProperty("fromPlayerId", fromPlayerId);
        if (fromNameHint != null) {
            body.addProperty("fromNameHint", fromNameHint);
        }
        body.addProperty("toPlayerId", toPlayerId);
        body.addProperty("lobbyCode", lobbyCode);
        post(body);
    }

    /** Fetches {@code playerId}'s current pending invites (empty list if none) - this is what a
     *  client polls. Blocking network call - run off the render thread. */
    @Override
    public List<Invite> getInvites(String playerId) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("action", "getInvites");
        body.addProperty("playerId", playerId);
        JsonObject response = post(body);

        List<Invite> invites = new ArrayList<>();
        JsonArray array = response.has("invites") ? response.getAsJsonArray("invites") : new JsonArray();
        for (JsonElement element : array) {
            invites.add(GSON.fromJson(element, Invite.class));
        }
        return invites;
    }

    /** Clears {@code playerId}'s pending invites, so they don't see the same ones again on the
     *  next poll. Blocking network call - run off the render thread. */
    @Override
    public void dismissInvites(String playerId) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("action", "dismissInvites");
        body.addProperty("playerId", playerId);
        post(body);
    }

    /** One pending invite, exactly the shape stored server-side - see {@code aws/README.md}
     *  "Direct invites". */
    public static final class Invite {
        private String fromPlayerId;
        private String fromNameHint;
        private String lobbyCode;
        private long sentAt;

        public String getFromPlayerId() {
            return fromPlayerId;
        }

        public String getFromNameHint() {
            return fromNameHint;
        }

        public String getLobbyCode() {
            return lobbyCode;
        }

        public long getSentAt() {
            return sentAt;
        }

        /** {@code fromNameHint} if present, otherwise a shortened-id fallback - same convention as
         *  {@code RankClient.LeaderboardEntry#shortId()}/{@code Friend#shortId()}. */
        public String displayName() {
            if (fromNameHint != null && !fromNameHint.isBlank()) {
                return fromNameHint;
            }
            String id = fromPlayerId == null ? "" : fromPlayerId.replace("-", "");
            return "Player-" + (id.length() >= 8 ? id.substring(0, 8) : id).toUpperCase();
        }
    }

    private JsonObject post(JsonObject body) throws IOException {
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
            throw new IOException("interrupted while waiting for the invite service", e);
        }

        JsonObject parsed;
        try {
            parsed = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("invite service returned a malformed response");
        }
        if (parsed.has("error")) {
            throw new IOException(parsed.get("error").getAsString());
        }
        return parsed;
    }
}

package com.paddleshock.net;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Client for the AWS Lambda lobby-code rendezvous broker (see aws/README.md). Brokers a short
 * code between two players so they can exchange public ip:port and attempt a direct UDP
 * connection - this class (and the Lambda behind it) never sees or relays any game traffic.
 *
 * <p>Every method here makes a blocking HTTPS call and must be run off the render thread (see
 * how {@code MultiplayerState} wraps these in background threads).
 */
public final class LobbyClient {

    private static final String ENDPOINT = "https://2mjcwpgb6sesrjy36nm6qxdmpu0wbvrb.lambda-url.us-east-1.on.aws/";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    private LobbyClient() {
    }

    /** Registers a new lobby for {@code publicAddr} (this host's own address, typically from
     *  {@code NetHost.getPublicAddress()} formatted via {@code StunClient.format}), owned by
     *  {@code playerId} (this host's ranked-ladder id - see {@code RankClient}), and returns the
     *  short code to share with the joiner. The stored playerId lets a later
     *  {@code reportMatchResult} call for this same code prove it's backed by a real session
     *  instead of trusting an unverified claim (see aws/README.md "Trust model"). */
    public static String create(String publicAddr, String playerId) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("action", "create");
        body.addProperty("addr", publicAddr);
        body.addProperty("playerId", playerId);
        JsonObject response = post(body);
        return response.get("code").getAsString();
    }

    /** Registers {@code publicAddr}/{@code playerId} as the joiner for {@code code} and returns
     *  the host's public address to connect to. */
    public static String join(String code, String publicAddr, String playerId) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("action", "join");
        body.addProperty("code", code);
        body.addProperty("addr", publicAddr);
        body.addProperty("playerId", playerId);
        JsonObject response = post(body);
        return response.get("hostAddr").getAsString();
    }

    /** Polls whether a joiner has registered for {@code code} yet; returns their public address,
     *  or {@code null} if nobody has joined yet. Not yet used by any UI flow (see aws/README.md,
     *  Phase D - active hole-punching) - kept here since the backend already supports it. */
    public static String poll(String code) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("action", "poll");
        body.addProperty("code", code);
        JsonObject response = post(body);
        return response.has("joinerAddr") && !response.get("joinerAddr").isJsonNull()
                ? response.get("joinerAddr").getAsString()
                : null;
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
            throw new IOException("interrupted while waiting for the lobby service", e);
        }

        JsonObject parsed;
        try {
            parsed = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("lobby service returned a malformed response");
        }
        if (parsed.has("error")) {
            throw new IOException(parsed.get("error").getAsString());
        }
        return parsed;
    }
}

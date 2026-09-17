package com.paddleshock.app;

/**
 * Tracks how long a peer's connection has been continuously timed out, resending HELLO on its own
 * cadence for a bounded reconnect window before giving up - the exact logic
 * {@code GameplayAppState#updateJoiner}/{@code updateSpectator} used to duplicate verbatim (both
 * watch the same {@code NetClient#isHostTimedOut()} condition the same way). A transient NAT remap
 * (Wi-Fi blip, mobile handoff) is common enough to deserve a real chance to self-heal rather than
 * an instant dead-end - see {@code NetHost#handleHello}'s matching reconnect-by-player-id acceptance.
 */
final class ReconnectWatcher {

    private static final float HELLO_INTERVAL_SECONDS = 1f;
    /** A few seconds beyond {@code NetClient#DISCONNECT_TIMEOUT_MS} (which has already elapsed by
     *  the time this window even starts) - long enough to give a real transient blip a chance to
     *  self-heal, short enough that a genuinely dead host still dead-ends in a reasonable time. */
    private static final float WINDOW_SECONDS = 8f;

    private float elapsedSeconds;
    private float helloTimer;

    /** Call once per frame. If {@code timedOut} is false, resets and returns false (the caller
     *  should proceed with its normal per-frame work). If true, advances the timers, resends HELLO
     *  via {@code sendHello} on its own cadence, and returns true (the caller should skip its
     *  normal per-frame work this frame) - unless the reconnect window has fully elapsed, in which
     *  case it instead runs {@code onGiveUp} once (a genuinely dead/unreachable peer) and still
     *  returns true. */
    boolean handle(float tpf, boolean timedOut, Runnable sendHello, Runnable onGiveUp) {
        if (!timedOut) {
            elapsedSeconds = 0f;
            helloTimer = 0f;
            return false;
        }
        elapsedSeconds += tpf;
        if (elapsedSeconds >= WINDOW_SECONDS) {
            onGiveUp.run();
            return true;
        }
        helloTimer += tpf;
        if (helloTimer >= HELLO_INTERVAL_SECONDS) {
            helloTimer = 0f;
            sendHello.run();
        }
        return true;
    }

    /** Resets the watcher for a fresh/rematch match - see {@code GameplayAppState#startNewMatch}. */
    void reset() {
        elapsedSeconds = 0f;
        helloTimer = 0f;
    }
}

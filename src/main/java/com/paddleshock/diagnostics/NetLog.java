package com.paddleshock.diagnostics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.paddleshock.data.SaveManager;

/**
 * A tiny local rolling log for non-fatal netcode/backend hiccups that are deliberately swallowed
 * as best-effort (malformed packets, a rank-report call that failed because the player is
 * offline, etc.) - those already have comments explaining why they're not treated as errors, but
 * previously left no trace at all. This gives a player having connection trouble something to
 * find and attach to a bug report, without changing any of that best-effort behavior.
 *
 * <p>Deliberately not a logging framework - just a single small, thread-safe file-append helper.
 * Never throws: a failure to log is itself swallowed (falling back to stderr) since logging must
 * never be the thing that breaks netcode.
 */
public final class NetLog {

    private static final Path LOG_FILE = SaveManager.getDataDir().resolve("netcode.log");

    /** Once the log file exceeds this size, it's rotated (truncated and restarted) rather than
     *  left to grow forever across many play sessions. */
    private static final long MAX_LOG_BYTES = 512 * 1024; // 512 KB

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final Object LOCK = new Object();

    private NetLog() {
    }

    /** Appends one line to the local netcode log: {@code [timestamp] [thread] message}. Best-effort
     *  and never throws - if the file can't be written, the message is printed to stderr instead
     *  so nothing is silently lost. */
    public static void log(String message) {
        String line = "[" + LocalDateTime.now().format(TIMESTAMP_FORMAT) + "] ["
                + Thread.currentThread().getName() + "] " + message;
        try {
            synchronized (LOCK) {
                Files.createDirectories(LOG_FILE.getParent());
                rotateIfTooLarge(LOG_FILE, MAX_LOG_BYTES);
                Files.writeString(LOG_FILE, line + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Throwable t) {
            // Never let logging itself break the caller's (best-effort) code path.
            System.err.println(line + " (also failed to write netcode.log: " + t + ")");
        }
    }

    /** Convenience overload for logging an exception alongside a short description, without
     *  dumping a full stack trace into what's meant to be a lightweight rolling log. */
    public static void log(String message, Throwable t) {
        log(message + " - " + t.getClass().getSimpleName()
                + (t.getMessage() != null ? ": " + t.getMessage() : ""));
    }

    /** Package-private (not {@code private}) so unit tests can exercise rotation against a
     *  throwaway temp file instead of the real, non-injectable {@link #LOG_FILE}. */
    static void rotateIfTooLarge(Path logFile, long maxBytes) {
        try {
            if (Files.exists(logFile) && Files.size(logFile) > maxBytes) {
                Files.writeString(logFile, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (IOException e) {
            // Best-effort rotation; worst case the file keeps growing until the next successful
            // rotation attempt.
        }
    }
}

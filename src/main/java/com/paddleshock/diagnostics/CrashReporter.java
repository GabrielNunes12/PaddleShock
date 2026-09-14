package com.paddleshock.diagnostics;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.paddleshock.data.SaveManager;

/**
 * Local, best-effort crash reporting: on any uncaught exception, on any thread, writes a
 * timestamped report to a file under the player's own {@code crashes/} data folder that they can
 * voluntarily attach to a bug report. Nothing is ever sent anywhere automatically - this is
 * purely local disk I/O.
 *
 * <p>This is deliberately paranoid: the crash handler itself must never throw or loop, since it
 * runs in exactly the situation ("something already went wrong") where that would be worst. Every
 * public entry point is wrapped so a failure here falls back to printing the original exception to
 * stderr, exactly like the JVM's default behavior, rather than swallowing it.
 */
public final class CrashReporter {

    private static final Path CRASH_DIR = SaveManager.getDataDir().resolve("crashes");

    /** Keep only the most recent N crash reports so this can't grow unbounded across many play
     *  sessions on a player's disk. */
    private static final int MAX_CRASH_FILES = 30;

    private static final DateTimeFormatter FILENAME_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final DateTimeFormatter REPORT_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** Describes whatever's cheaply available about current app/match state at crash time (e.g.
     *  "HOST match in progress (ranked)", "no match in progress"). Set once by
     *  {@code PaddleShockApp} during startup; defaults to a supplier that says nothing useful was
     *  wired up yet, which is expected for anything that crashes before that point. */
    private static volatile Supplier<String> contextSupplier = () -> "unknown (app not yet initialized)";

    private static volatile boolean installed = false;

    private CrashReporter() {
    }

    /** Installs the global uncaught-exception handler covering every thread - the render thread's
     *  own errors are handled separately via {@link #reportRenderThreadError}, since jME catches
     *  those itself rather than letting them reach {@link Thread.UncaughtExceptionHandler}. Safe to
     *  call more than once (only installs once). Should be called as early as possible in
     *  {@code main}, before anything else that could throw. */
    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        try {
            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                writeCrashReport(thread.getName(), throwable);
            });
        } catch (Throwable t) {
            System.err.println("CrashReporter failed to install the uncaught exception handler: " + t);
        }
    }

    /** Lets {@code PaddleShockApp} supply a short description of current app/match state, read
     *  lazily at crash time (never eagerly), so a crash report can note whether a match was in
     *  progress and what mode it was. The supplier itself is wrapped defensively when read. */
    public static void setContextSupplier(Supplier<String> supplier) {
        if (supplier != null) {
            contextSupplier = supplier;
        }
    }

    /** Called from {@code PaddleShockApp.handleError} - jME's own render-thread error path (see
     *  {@code LegacyApplication.handleError}) - so a render-thread exception is captured the same
     *  way a background-thread one is, before jME's own handling (logging + stopping the app)
     *  proceeds. */
    public static void reportRenderThreadError(String message, Throwable throwable) {
        writeCrashReport("jME render thread" + (message != null && !message.isEmpty() ? " (" + message + ")" : ""),
                throwable);
    }

    /** Writes one crash report file. Best-effort and defensive end-to-end: if anything here fails,
     *  falls back to printing the original exception to stderr (like the JVM's default handler)
     *  rather than losing it silently, and never throws back into the caller (which, for the
     *  default uncaught-exception handler, would itself be running on an already-dying thread). */
    private static void writeCrashReport(String threadName, Throwable throwable) {
        try {
            Files.createDirectories(CRASH_DIR);
            String filename = "crash-" + LocalDateTime.now().format(FILENAME_TIMESTAMP) + ".log";
            Path file = CRASH_DIR.resolve(filename);
            String content = formatReport(threadName, throwable, safeContext());
            Files.writeString(file, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            trimOldReports(CRASH_DIR, MAX_CRASH_FILES);
        } catch (Throwable writeFailure) {
            // Crash reporting must never swallow the original crash - print both to stderr,
            // matching what would have happened if this handler didn't exist at all.
            System.err.println("CrashReporter failed to write a crash report: " + writeFailure);
        } finally {
            // Always also surface the original exception the normal way, regardless of whether
            // the file write above succeeded - a crash file is a nice-to-have, not a replacement
            // for the console output a developer running from a terminal would already see.
            System.err.println("Uncaught exception on thread \"" + threadName + "\":");
            if (throwable != null) {
                throwable.printStackTrace();
            }
        }
    }

    /** Package-private (not {@code private}) so unit tests can verify the report's shape without
     *  going through the real, non-injectable {@link #CRASH_DIR}/{@link #contextSupplier}. */
    static String formatReport(String threadName, Throwable throwable, String context) {
        StringBuilder sb = new StringBuilder();
        sb.append("PaddleShock crash report\n");
        sb.append("Timestamp: ").append(LocalDateTime.now().format(REPORT_TIMESTAMP)).append('\n');
        sb.append("Thread: ").append(threadName).append('\n');
        sb.append("Match state: ").append(context).append('\n');
        sb.append("OS: ").append(safeProperty("os.name")).append(' ')
                .append(safeProperty("os.version")).append(" (").append(safeProperty("os.arch")).append(")\n");
        sb.append("Java: ").append(safeProperty("java.version"))
                .append(" (").append(safeProperty("java.vendor")).append(")\n");
        sb.append("Exception:\n");
        sb.append(stackTraceOf(throwable));
        return sb.toString();
    }

    private static String safeContext() {
        try {
            String context = contextSupplier.get();
            return context == null ? "unknown" : context;
        } catch (Throwable t) {
            return "unknown (context supplier threw: " + t + ")";
        }
    }

    private static String safeProperty(String key) {
        try {
            return System.getProperty(key, "unknown");
        } catch (Throwable t) {
            return "unknown";
        }
    }

    static String stackTraceOf(Throwable throwable) {
        if (throwable == null) {
            return "(no throwable provided)\n";
        }
        try (StringWriter sw = new StringWriter(); PrintWriter pw = new PrintWriter(sw)) {
            // PrintWriter#printStackTrace already walks the full "Caused by:" cause chain.
            throwable.printStackTrace(pw);
            return sw.toString();
        } catch (Throwable t) {
            return "(failed to render stack trace: " + t + ")\n";
        }
    }

    /** Deletes the oldest crash reports beyond {@code maxFiles}, relying on the filename's
     *  sortable timestamp prefix. Best-effort - failing to trim just means the folder stays a bit
     *  larger than intended, not that anything else breaks. Package-private so unit tests can
     *  exercise it against a throwaway temp directory instead of the real {@link #CRASH_DIR}. */
    static void trimOldReports(Path crashDir, int maxFiles) {
        try (Stream<Path> files = Files.list(crashDir)) {
            List<Path> crashFiles = files
                    .filter(p -> p.getFileName().toString().startsWith("crash-"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            int excess = crashFiles.size() - maxFiles;
            for (int i = 0; i < excess; i++) {
                try {
                    Files.deleteIfExists(crashFiles.get(i));
                } catch (IOException ignored) {
                    // Best-effort cleanup; not fatal.
                }
            }
        } catch (IOException e) {
            // Best-effort cleanup; not fatal.
        }
    }
}

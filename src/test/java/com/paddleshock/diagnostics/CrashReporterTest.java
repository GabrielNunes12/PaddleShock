package com.paddleshock.diagnostics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link CrashReporter}'s report formatting and file-count-capping logic directly,
 * against throwaway temp files/directories rather than the real (non-injectable) data directory -
 * no jME/graphics stack needed, same spirit as {@code MatchSimulationTest}.
 */
class CrashReporterTest {

    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("crash-reporter-test");
    }

    @AfterEach
    void tearDown() throws IOException {
        try (Stream<Path> files = Files.walk(tempDir)) {
            List<Path> toDelete = files.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList();
            for (Path p : toDelete) {
                Files.deleteIfExists(p);
            }
        }
    }

    @Test
    void formatReportIncludesTimestampThreadContextAndFullCauseChain() {
        Exception cause = new IllegalStateException("root cause");
        Exception outer = new RuntimeException("wrapper", cause);

        String report = CrashReporter.formatReport("NetHost-recv", outer, "host match in progress");

        assertTrue(report.contains("PaddleShock crash report"));
        assertTrue(report.contains("Thread: NetHost-recv"));
        assertTrue(report.contains("Match state: host match in progress"));
        assertTrue(report.contains("OS: "));
        assertTrue(report.contains("Java: "));
        assertTrue(report.contains("RuntimeException: wrapper"));
        assertTrue(report.contains("Caused by:"));
        assertTrue(report.contains("IllegalStateException: root cause"));
    }

    @Test
    void formatReportHandlesNullThrowableWithoutThrowing() {
        String report = CrashReporter.formatReport("main", null, "no match in progress");

        assertTrue(report.contains("no match in progress"));
        assertTrue(report.contains("(no throwable provided)"));
    }

    @Test
    void trimOldReportsKeepsOnlyTheMostRecentN() throws IOException {
        // Filenames are zero-padded so lexicographic sort matches chronological order, matching
        // the real crash-<timestamp>.log naming scheme.
        for (int i = 0; i < 10; i++) {
            Files.writeString(tempDir.resolve(String.format("crash-%03d.log", i)), "x");
        }

        CrashReporter.trimOldReports(tempDir, 4);

        List<String> remaining;
        try (Stream<Path> files = Files.list(tempDir)) {
            remaining = files.map(p -> p.getFileName().toString()).sorted().toList();
        }

        assertEquals(List.of("crash-006.log", "crash-007.log", "crash-008.log", "crash-009.log"), remaining);
    }

    @Test
    void trimOldReportsIsNoOpWhenUnderTheCap() throws IOException {
        Files.writeString(tempDir.resolve("crash-001.log"), "x");
        Files.writeString(tempDir.resolve("crash-002.log"), "x");

        CrashReporter.trimOldReports(tempDir, 30);

        try (Stream<Path> files = Files.list(tempDir)) {
            assertEquals(2, files.count());
        }
    }

    @Test
    void trimOldReportsIgnoresFilesNotMatchingTheCrashPrefix() throws IOException {
        Files.writeString(tempDir.resolve("crash-001.log"), "x");
        Files.writeString(tempDir.resolve("crash-002.log"), "x");
        Files.writeString(tempDir.resolve("readme.txt"), "not a crash file");

        CrashReporter.trimOldReports(tempDir, 1);

        List<String> remaining;
        try (Stream<Path> files = Files.list(tempDir)) {
            remaining = files.map(p -> p.getFileName().toString()).sorted().toList();
        }

        assertEquals(List.of("crash-002.log", "readme.txt"), remaining);
    }
}

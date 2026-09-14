package com.paddleshock.diagnostics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Exercises {@link NetLog}'s size-based rotation directly against a throwaway temp file, since
 * the real log path is fixed to the player's actual data directory. No jME/graphics stack needed.
 */
class NetLogTest {

    private Path tempFile;

    @BeforeEach
    void setUp() throws IOException {
        tempFile = Files.createTempFile("netcode-test", ".log");
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(tempFile);
    }

    @Test
    void rotateIfTooLargeTruncatesOnceOverTheCap() throws IOException {
        Files.writeString(tempFile, "x".repeat(2048));

        NetLog.rotateIfTooLarge(tempFile, 1024);

        assertEquals(0, Files.size(tempFile));
    }

    @Test
    void rotateIfTooLargeLeavesSmallFilesAlone() throws IOException {
        Files.writeString(tempFile, "small content");

        NetLog.rotateIfTooLarge(tempFile, 1024);

        assertEquals("small content", Files.readString(tempFile));
    }

    @Test
    void rotateIfTooLargeIsNoOpForAMissingFile() throws IOException {
        Path missing = tempFile.resolveSibling("does-not-exist.log");

        NetLog.rotateIfTooLarge(missing, 1024);

        assertFalse(Files.exists(missing));
    }
}

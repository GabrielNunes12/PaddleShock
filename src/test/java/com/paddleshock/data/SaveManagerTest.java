package com.paddleshock.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;

/**
 * Exercises the per-install save encryption key introduced to replace the old shared/hardcoded
 * one: a fresh save round-trips under a key generated (and cached) for this install, and a save
 * written under the old hardcoded key is still readable once and gets migrated to the new key.
 *
 * Redirects "user.home" to a throwaway temp directory (via a static initializer, before
 * SaveManager/SaveCrypto - both of which cache paths and keys in static final fields - are ever
 * touched) so this never reads or writes the real player's save data.
 */
class SaveManagerTest {

    private static final Path TEMP_HOME;

    static {
        try {
            TEMP_HOME = Files.createTempDirectory("paddleshock-save-test");
            System.setProperty("user.home", TEMP_HOME.toString());
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final Path SAVE_DIR = TEMP_HOME.resolve(".paddleshock");
    private static final Path PROFILE_FILE = SAVE_DIR.resolve("profile.dat");
    private static final Path PROFILE_BACKUP = SAVE_DIR.resolve("profile.dat.bak");
    private static final Path KEY_FILE = SAVE_DIR.resolve("install.key");

    // Must match the hardcoded string SaveCrypto derives its legacy key from.
    private static final String LEGACY_KEY_MATERIAL = "PaddleShock-save-v1-do-not-edit";

    @Test
    void roundTripsProfileUnderPerInstallKeyAndPersistsIt() throws IOException {
        clearSaveDir();

        PlayerProfile profile = SaveManager.loadProfile();
        profile.addCurrency(1234);
        String playerId = profile.getPlayerId();
        SaveManager.saveProfile(profile);

        assertTrue(Files.exists(KEY_FILE), "install key file should have been created next to the save");

        PlayerProfile reloaded = SaveManager.loadProfile();
        assertEquals(profile.getCurrency(), reloaded.getCurrency());
        assertEquals(playerId, reloaded.getPlayerId());

        // The on-disk file should decrypt directly under the install key - no legacy fallback needed.
        SaveCrypto.DecryptResult result = SaveCrypto.decrypt(Files.readAllBytes(PROFILE_FILE));
        assertNotNull(result);
        assertFalse(result.usedLegacyKey());
    }

    @Test
    void migratesASaveEncryptedWithTheOldHardcodedKey() throws Exception {
        clearSaveDir();

        PlayerProfile legacyProfile = new PlayerProfile();
        legacyProfile.addCurrency(500);
        String legacyPlayerId = legacyProfile.getPlayerId();
        String json = new Gson().toJson(legacyProfile);
        Files.createDirectories(SAVE_DIR);
        Files.write(PROFILE_FILE, encryptWithLegacyKey(json));

        // First load: install key fails on this file, falls back to the legacy key, succeeds,
        // and should immediately re-save under the install key.
        PlayerProfile loaded = SaveManager.loadProfile();
        assertEquals(legacyProfile.getCurrency(), loaded.getCurrency());
        assertEquals(legacyPlayerId, loaded.getPlayerId());

        SaveCrypto.DecryptResult reSaved = SaveCrypto.decrypt(Files.readAllBytes(PROFILE_FILE));
        assertNotNull(reSaved, "re-saved file should still be readable");
        assertFalse(reSaved.usedLegacyKey(), "expected the migrated file to no longer need the legacy-key fallback");

        // Second load should read straight from the (now install-key-encrypted) primary file.
        PlayerProfile reloaded = SaveManager.loadProfile();
        assertEquals(legacyProfile.getCurrency(), reloaded.getCurrency());
        assertEquals(legacyPlayerId, reloaded.getPlayerId());
    }

    @Test
    void corruptedPrimaryFallsBackToBackup() throws IOException {
        clearSaveDir();

        PlayerProfile profile = SaveManager.loadProfile();
        int defaultCurrency = profile.getCurrency(); // PlayerProfile's default starting currency
        profile.addCurrency(111);
        String playerId = profile.getPlayerId();
        SaveManager.saveProfile(profile); // first save: no prior file yet, so no backup written

        profile.addCurrency(222); // now defaultCurrency + 333
        SaveManager.saveProfile(profile); // second save: the first save's content becomes profile.dat.bak
        assertTrue(Files.exists(PROFILE_BACKUP), "a second save should have rotated the prior save into .bak");

        // Simulate a crash/tamper leaving the primary file corrupt - truncate it so it fails GCM
        // authentication - while the backup (the first save's content, +111 only) is untouched.
        Files.write(PROFILE_FILE, new byte[] {1, 2, 3});

        PlayerProfile recovered = SaveManager.loadProfile();
        assertEquals(defaultCurrency + 111, recovered.getCurrency(),
                "should recover the BACKUP generation (+111 only), not silently reset or use the newer (+333) generation");
        assertEquals(playerId, recovered.getPlayerId());
    }

    @Test
    void bothPrimaryAndBackupCorruptedResetsToFreshDefaultRatherThanThrowing() throws IOException {
        clearSaveDir();

        PlayerProfile original = SaveManager.loadProfile();
        int defaultCurrency = original.getCurrency();
        original.addCurrency(999);
        String originalPlayerId = original.getPlayerId();
        SaveManager.saveProfile(original);

        Files.write(PROFILE_FILE, new byte[] {9, 9, 9});
        Files.createDirectories(SAVE_DIR);
        Files.write(PROFILE_BACKUP, new byte[] {9, 9, 9});

        PlayerProfile reset = SaveManager.loadProfile();
        assertEquals(defaultCurrency, reset.getCurrency(),
                "with both generations unusable, load must fall back to a brand-new default profile");
        assertTrue(!reset.getPlayerId().equals(originalPlayerId),
                "a fresh default profile should never reuse the old (now unreachable) identity");
    }

    private static void clearSaveDir() throws IOException {
        Files.deleteIfExists(PROFILE_FILE);
        Files.deleteIfExists(PROFILE_BACKUP);
    }

    /** Encrypts exactly like SaveCrypto used to (before per-install keys): AES/GCM with a key
     *  derived from the old hardcoded string, [12-byte IV][ciphertext+tag]. */
    private static byte[] encryptWithLegacyKey(String plaintext) throws GeneralSecurityException {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = sha256.digest(LEGACY_KEY_MATERIAL.getBytes(StandardCharsets.UTF_8));
        SecretKeySpec legacyKey = new SecretKeySpec(keyBytes, "AES");

        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, legacyKey, new GCMParameterSpec(128, iv));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        byte[] output = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, output, 0, iv.length);
        System.arraycopy(ciphertext, 0, output, iv.length, ciphertext.length);
        return output;
    }
}

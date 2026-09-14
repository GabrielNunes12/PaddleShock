package com.paddleshock.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-GCM encryption for save files, so currency/purchases/settings aren't plain editable JSON.
 *
 * The key is a random 256-bit key generated the first time the game runs on a given install, and
 * cached in {@link #KEY_FILE} (next to the save files) so every later launch on that install
 * reuses it. This replaces an earlier scheme where the key was a single fixed value embedded in
 * every copy of the game binary: since {@link PlayerProfile} now carries a {@code playerId} used
 * to authenticate ranked match reports server-side, a shared hardcoded key meant anyone with the
 * jar could decrypt (or forge) ANY player's save, not just their own. A per-install key means the
 * binary alone no longer lets an attacker do that at scale - it only ever protects/forges saves
 * for its own install. This key file is not itself encrypted: it defends against the key being
 * extracted from the shared binary, not against local filesystem access on the player's own
 * machine, which is a different threat model this change isn't trying to address.
 *
 * {@link #LEGACY_KEY} is the old shared/hardcoded key. It's kept only so a save written before
 * this change can still be decrypted once on upgrade (see SaveManager.load()'s primary/backup
 * fallback chain, which also falls back to this key) - it is never used to encrypt new data, and
 * once a save is successfully read with it, the caller immediately re-saves it under the new
 * per-install key so the fallback is only ever needed once per save file.
 */
final class SaveCrypto {

    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LENGTH_BYTES = 12;
    private static final int KEY_LENGTH_BYTES = 32; // AES-256, matching the legacy SHA-256-derived key.

    static final Path KEY_FILE = SaveManager.SAVE_DIR.resolve("install.key");

    private static final SecretKeySpec LEGACY_KEY = deriveLegacyKey();
    private static final SecretKeySpec INSTALL_KEY = loadOrCreateInstallKey();

    private SaveCrypto() {
    }

    private static SecretKeySpec deriveLegacyKey() {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest("PaddleShock-save-v1-do-not-edit".getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(hash, "AES");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Loads this install's key from {@link #KEY_FILE}, or generates and persists a fresh random
     *  one if the file is absent or unreadable/corrupt. */
    private static SecretKeySpec loadOrCreateInstallKey() {
        byte[] existing = tryReadKeyFile();
        if (existing != null && existing.length == KEY_LENGTH_BYTES) {
            return new SecretKeySpec(existing, "AES");
        }

        byte[] fresh = new byte[KEY_LENGTH_BYTES];
        new SecureRandom().nextBytes(fresh);
        writeKeyFile(fresh);
        return new SecretKeySpec(fresh, "AES");
    }

    private static byte[] tryReadKeyFile() {
        try {
            if (!Files.exists(KEY_FILE)) {
                return null;
            }
            String encoded = Files.readString(KEY_FILE, StandardCharsets.UTF_8).trim();
            return Base64.getDecoder().decode(encoded);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Failed to read install key " + KEY_FILE + ": " + e.getMessage()
                    + "; generating a new one.");
            return null;
        }
    }

    private static void writeKeyFile(byte[] key) {
        try {
            Files.createDirectories(KEY_FILE.getParent());
            Files.writeString(KEY_FILE, Base64.getEncoder().encodeToString(key), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Worst case we regenerate (and lose reuse of) the key next launch; saves themselves
            // are still written/read correctly within this run since INSTALL_KEY is cached in memory.
            System.err.println("Failed to persist install key " + KEY_FILE + ": " + e.getMessage());
        }
    }

    static byte[] encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, INSTALL_KEY, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] output = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, output, 0, iv.length);
            System.arraycopy(ciphertext, 0, output, iv.length, ciphertext.length);
            return output;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt save data", e);
        }
    }

    /**
     * Attempts to decrypt {@code data}, trying this install's key first and falling back to the
     * old shared/hardcoded key (for saves written before per-install keys existed). Returns null
     * if the data is too short, corrupted, or fails GCM authentication under both keys.
     */
    static DecryptResult decrypt(byte[] data) {
        if (data.length < IV_LENGTH_BYTES) {
            return null;
        }

        String json = decryptWith(data, INSTALL_KEY);
        if (json != null) {
            return new DecryptResult(json, false);
        }

        json = decryptWith(data, LEGACY_KEY);
        if (json != null) {
            return new DecryptResult(json, true);
        }

        return null;
    }

    private static String decryptWith(byte[] data, SecretKeySpec key) {
        try {
            byte[] iv = Arrays.copyOfRange(data, 0, IV_LENGTH_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(data, IV_LENGTH_BYTES, data.length);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    /** Result of a successful decrypt: the plaintext JSON, and whether reaching it required
     *  falling back to {@link #LEGACY_KEY} (meaning the caller should immediately re-save the
     *  data so it's encrypted with {@link #INSTALL_KEY} from now on). */
    record DecryptResult(String json, boolean usedLegacyKey) {
    }
}

package com.paddleshock.data;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-GCM encryption for save files, so currency/purchases/settings aren't plain editable JSON.
 * The key is embedded in the client, so this raises the bar against casual save editing rather
 * than providing real security against a determined attacker with the binary in hand - that's
 * an inherent limit of any client-side encryption for single-player save data.
 */
final class SaveCrypto {

    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LENGTH_BYTES = 12;
    private static final SecretKeySpec KEY = deriveKey();

    private SaveCrypto() {
    }

    private static SecretKeySpec deriveKey() {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest("PaddleShock-save-v1-do-not-edit".getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(hash, "AES");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static byte[] encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, KEY, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] output = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, output, 0, iv.length);
            System.arraycopy(ciphertext, 0, output, iv.length, ciphertext.length);
            return output;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt save data", e);
        }
    }

    /** Returns null if the data is too short, corrupted, or fails the GCM authentication tag (tampered). */
    static String decrypt(byte[] data) {
        if (data.length < IV_LENGTH_BYTES) {
            return null;
        }
        try {
            byte[] iv = Arrays.copyOfRange(data, 0, IV_LENGTH_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(data, IV_LENGTH_BYTES, data.length);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, KEY, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }
}

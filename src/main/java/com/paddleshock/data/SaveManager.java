package com.paddleshock.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.paddleshock.settings.GameSettings;

/** Loads/saves {@link PlayerProfile} and {@link GameSettings}, AES-GCM encrypted, under the user's home dir. */
public final class SaveManager {

    private static final Path SAVE_DIR = Path.of(System.getProperty("user.home"), ".paddleshock");
    private static final Path PROFILE_FILE = SAVE_DIR.resolve("profile.dat");
    private static final Path SETTINGS_FILE = SAVE_DIR.resolve("settings.dat");

    private static final Gson GSON = new GsonBuilder().create();

    private SaveManager() {
    }

    public static PlayerProfile loadProfile() {
        return load(PROFILE_FILE, PlayerProfile.class, PlayerProfile::new);
    }

    public static void saveProfile(PlayerProfile profile) {
        save(PROFILE_FILE, profile);
    }

    public static GameSettings loadSettings() {
        return load(SETTINGS_FILE, GameSettings.class, GameSettings::new);
    }

    public static void saveSettings(GameSettings settings) {
        save(SETTINGS_FILE, settings);
    }

    private static <T> T load(Path file, Class<T> type, Supplier<T> fallback) {
        try {
            if (Files.exists(file)) {
                byte[] encrypted = Files.readAllBytes(file);
                String json = SaveCrypto.decrypt(encrypted);
                if (json == null) {
                    System.err.println("Save file " + file + " failed its integrity check "
                            + "(tampered or corrupted); resetting to defaults.");
                } else {
                    T loaded = GSON.fromJson(json, type);
                    if (loaded != null) {
                        return loaded;
                    }
                }
            }
        } catch (IOException | JsonSyntaxException e) {
            System.err.println("Failed to load " + file + ", using defaults: " + e.getMessage());
        }
        return fallback.get();
    }

    private static void save(Path file, Object data) {
        try {
            Files.createDirectories(SAVE_DIR);
            Files.write(file, SaveCrypto.encrypt(GSON.toJson(data)));
        } catch (IOException e) {
            System.err.println("Failed to save " + file + ": " + e.getMessage());
        }
    }
}

package com.paddleshock.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.paddleshock.settings.GameSettings;

/** Loads/saves {@link PlayerProfile} and {@link GameSettings} as JSON under the user's home dir. */
public final class SaveManager {

    private static final Path SAVE_DIR = Path.of(System.getProperty("user.home"), ".paddleshock");
    private static final Path PROFILE_FILE = SAVE_DIR.resolve("profile.json");
    private static final Path SETTINGS_FILE = SAVE_DIR.resolve("settings.json");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

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

    private static <T> T load(Path file, Class<T> type, java.util.function.Supplier<T> fallback) {
        try {
            if (Files.exists(file)) {
                String json = Files.readString(file);
                T loaded = GSON.fromJson(json, type);
                if (loaded != null) {
                    return loaded;
                }
            }
        } catch (IOException | com.google.gson.JsonSyntaxException e) {
            System.err.println("Failed to load " + file + ", using defaults: " + e.getMessage());
        }
        return fallback.get();
    }

    private static void save(Path file, Object data) {
        try {
            Files.createDirectories(SAVE_DIR);
            Files.writeString(file, GSON.toJson(data));
        } catch (IOException e) {
            System.err.println("Failed to save " + file + ": " + e.getMessage());
        }
    }
}

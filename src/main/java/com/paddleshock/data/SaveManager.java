package com.paddleshock.data;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.paddleshock.settings.GameSettings;

/** Loads/saves {@link PlayerProfile} and {@link GameSettings}, AES-GCM encrypted, under the user's home dir. */
public final class SaveManager implements ProfileStore {

    static final Path SAVE_DIR = Path.of(System.getProperty("user.home"), ".paddleshock");
    private static final Path PROFILE_FILE = SAVE_DIR.resolve("profile.dat");
    private static final Path SETTINGS_FILE = SAVE_DIR.resolve("settings.dat");

    private static final Gson GSON = new GsonBuilder().create();

    public SaveManager() {
    }

    /** The local per-player data directory (also home to {@code crashes/} and {@code netcode.log} -
     *  see {@code com.paddleshock.diagnostics}). Kept static: {@code CrashReporter}/{@code NetLog}
     *  need this path before a {@code SaveManager}/{@code PaddleShockApp} instance necessarily
     *  exists, and it's a pure path lookup with no state worth injecting. */
    public static Path getDataDir() {
        return SAVE_DIR;
    }

    @Override
    public PlayerProfile loadProfile() {
        return load(PROFILE_FILE, PlayerProfile.class, PlayerProfile::new, PlayerProfile::migrateIfNeeded);
    }

    @Override
    public void saveProfile(PlayerProfile profile) {
        save(PROFILE_FILE, profile);
    }

    @Override
    public GameSettings loadSettings() {
        return load(SETTINGS_FILE, GameSettings.class, GameSettings::new, GameSettings::migrateIfNeeded);
    }

    @Override
    public void saveSettings(GameSettings settings) {
        save(SETTINGS_FILE, settings);
    }

    /**
     * Loads {@code file}, falling back to its {@code .bak} backup if the primary copy is
     * missing/corrupt/tampered, and only falling back to a brand-new default if both are
     * unusable. Logs clearly which path was taken so a field report is diagnosable.
     */
    private static <T> T load(Path file, Class<T> type, Supplier<T> fallback, Consumer<T> migrator) {
        Path backup = backupPathFor(file);

        TryLoadResult<T> loaded = tryLoad(file, type);
        if (loaded != null) {
            System.out.println("Loaded " + file + " (fresh).");
            migrator.accept(loaded.value());
            migrateKeyIfNeeded(file, loaded);
            return loaded.value();
        }

        if (Files.exists(file)) {
            // The primary file exists but failed to load (corrupt/tampered/truncated) - try the backup.
            System.err.println("Primary save " + file + " could not be loaded; attempting backup " + backup);
        }

        TryLoadResult<T> recovered = tryLoad(backup, type);
        if (recovered != null) {
            System.out.println("Recovered " + file + " from backup " + backup + ".");
            migrator.accept(recovered.value());
            migrateKeyIfNeeded(file, recovered);
            return recovered.value();
        }

        if (Files.exists(file) || Files.exists(backup)) {
            System.err.println("Both " + file + " and its backup " + backup
                    + " are unusable; resetting to defaults.");
        }
        T freshDefault = fallback.get();
        migrator.accept(freshDefault);
        return freshDefault;
    }

    /**
     * If {@code loaded} was only decryptable using the old shared/hardcoded key (i.e. this is the
     * first load since upgrading to per-install keys), immediately re-saves it to {@code file} so
     * it's now encrypted with this install's key, making the migration a one-time cost.
     */
    private static <T> void migrateKeyIfNeeded(Path file, TryLoadResult<T> loaded) {
        if (loaded.needsKeyMigration()) {
            System.out.println("Re-encrypting " + file + " with this install's key (was under the legacy shared key).");
            save(file, loaded.value());
        }
    }

    /** Returns the deserialized object (plus whether the legacy key had to be used), or null if
     *  the file doesn't exist or fails to load cleanly under either key. */
    private static <T> TryLoadResult<T> tryLoad(Path file, Class<T> type) {
        try {
            if (!Files.exists(file)) {
                return null;
            }
            byte[] encrypted = Files.readAllBytes(file);
            SaveCrypto.DecryptResult result = SaveCrypto.decrypt(encrypted);
            if (result == null) {
                System.err.println("Save file " + file + " failed its integrity check (tampered or corrupted).");
                return null;
            }
            T value = GSON.fromJson(result.json(), type);
            return new TryLoadResult<>(value, result.usedLegacyKey());
        } catch (IOException | JsonSyntaxException e) {
            System.err.println("Failed to read/parse " + file + ": " + e.getMessage());
            return null;
        }
    }

    /** value: the deserialized object. needsKeyMigration: true if decrypting it required falling
     *  back to the old shared/hardcoded key, meaning the caller should re-save it under this
     *  install's key so the fallback isn't needed again next time. */
    private record TryLoadResult<T>(T value, boolean needsKeyMigration) {
    }

    /**
     * Writes {@code data} atomically: encrypt to a temp file in the same directory, back up the
     * previous generation (keeping exactly one, {@code <file>.bak}), then atomically rename the
     * temp file into place. This means a crash/power-loss mid-write can never leave a
     * truncated/corrupt file at {@code file} - the rename either completes fully or not at all,
     * and the prior generation always survives as the backup.
     */
    private static void save(Path file, Object data) {
        Path tempFile = null;
        try {
            Files.createDirectories(SAVE_DIR);
            tempFile = Files.createTempFile(SAVE_DIR, file.getFileName().toString(), ".tmp");
            Files.write(tempFile, SaveCrypto.encrypt(GSON.toJson(data)));

            if (Files.exists(file)) {
                Path backup = backupPathFor(file);
                Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
            }

            moveAtomicallyWithFallback(tempFile, file);
            tempFile = null;
        } catch (IOException e) {
            System.err.println("Failed to save " + file + ": " + e.getMessage());
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // Best-effort cleanup of the temp file; not fatal.
                }
            }
        }
    }

    private static void moveAtomicallyWithFallback(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Some filesystems (e.g. certain network mounts) don't support atomic moves; fall
            // back to a plain (non-atomic) move rather than failing the save outright.
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path backupPathFor(Path file) {
        return file.resolveSibling(file.getFileName().toString() + ".bak");
    }
}

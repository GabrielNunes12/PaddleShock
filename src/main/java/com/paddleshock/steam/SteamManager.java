package com.paddleshock.steam;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.codedisaster.steamworks.SteamAPI;
import com.codedisaster.steamworks.SteamException;
import com.codedisaster.steamworks.SteamLibraryLoader;

/**
 * Best-effort wrapper around the Steamworks native API. Steam integration is entirely
 * optional for local/non-Steam development: if the native libraries can't be loaded, the
 * Steam client isn't running, or there's no valid App ID (steam_appid.txt / real App ID),
 * initialization simply fails and the game keeps running in "offline mode" with every
 * Steam-facing call becoming a safe no-op.
 *
 * <p>Two distinct native libraries are involved. steamworks4j's own JNI bridge ships as a
 * classpath resource inside the steamworks4j jar and is extracted to a temp file and loaded
 * with {@link System#load}; Valve's redistributable {@code steam_api64.dll} is NOT bundled
 * (it's checked into {@code native/win64/} instead) and is loaded via
 * {@link System#loadLibrary}, relying on the {@code java.library.path} the {@code run} Gradle
 * task points at that folder (see {@code build.gradle.kts}).
 */
public class SteamManager {

    private static final Logger LOG = Logger.getLogger(SteamManager.class.getName());

    private boolean available;

    public SteamManager() {
        try {
            boolean librariesLoaded = SteamAPI.loadLibraries(new BridgeExtractingLoader());
            available = librariesLoaded && SteamAPI.init();
        } catch (Throwable t) {
            // Covers SteamException (Steam not running / bad App ID), UnsatisfiedLinkError /
            // NoClassDefFoundError (native lib missing or wrong platform), and anything else
            // that could go wrong loading a native library we don't control.
            available = false;
        }

        if (!available) {
            LOG.info("Steam unavailable, running in offline mode");
        } else {
            LOG.info("Steam initialized");
        }
    }

    /** True once {@code SteamAPI.init()} has succeeded; false in offline mode. */
    public boolean isAvailable() {
        return available;
    }

    /** Pumps Steam callbacks; safe to call every frame even when Steam is unavailable. */
    public void update() {
        if (!available) {
            return;
        }
        try {
            SteamAPI.runCallbacks();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Steam callback pump failed, disabling Steam integration", e);
            available = false;
        }
    }

    /** Shuts down the Steam API if (and only if) it was successfully initialized. */
    public void shutdown() {
        if (!available) {
            return;
        }
        try {
            SteamAPI.shutdown();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Steam shutdown failed", e);
        } finally {
            available = false;
        }
    }

    /**
     * Loads the two native libraries steamworks4j asks for by their bare names ("steam_api",
     * "steamworks4j"). "steam_api" is Valve's redistributable and is left to a plain
     * {@link System#loadLibrary}, which finds it via {@code java.library.path} (see the
     * {@code run} task in build.gradle.kts). "steamworks4j" is the wrapper's own JNI bridge,
     * bundled as a classpath resource inside the steamworks4j jar with no filesystem path of
     * its own, so it has to be extracted to a temp file before it can be loaded.
     */
    private static final class BridgeExtractingLoader implements SteamLibraryLoader {

        @Override
        public boolean loadLibrary(String libraryName) {
            try {
                if ("steam_api".equals(libraryName)) {
                    System.loadLibrary(is64Bit() ? "steam_api64" : "steam_api");
                } else {
                    System.load(extractBridgeLibrary(libraryName).getAbsolutePath());
                }
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        private static File extractBridgeLibrary(String libraryName) throws IOException {
            String resourceName = "/" + libraryName + (is64Bit() ? "64" : "") + platformExtension();
            try (InputStream in = SteamManager.class.getResourceAsStream(resourceName)) {
                if (in == null) {
                    throw new IOException("Bundled native resource not found: " + resourceName);
                }
                File tempFile = File.createTempFile("paddleshock-" + libraryName, platformExtension());
                tempFile.deleteOnExit();
                try (OutputStream out = new FileOutputStream(tempFile)) {
                    in.transferTo(out);
                }
                return tempFile;
            }
        }

        private static boolean is64Bit() {
            return System.getProperty("os.arch", "").contains("64");
        }

        private static String platformExtension() {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                return ".dll";
            } else if (os.contains("mac")) {
                return ".dylib";
            }
            return ".so";
        }
    }
}

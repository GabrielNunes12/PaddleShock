package com.paddleshock.i18n;

import com.paddleshock.settings.Lang;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Static text lookup for all in-game UI strings. Backed by {@code i18n/strings_*.properties} on
 * the classpath (loaded as plain UTF-8 {@link Properties}, not {@link java.util.ResourceBundle} -
 * a ResourceBundle's default ISO-8859-1 .properties decoding mangles PT-BR accents, and Properties
 * lets us load the file as UTF-8 explicitly). Call {@link #setLanguage} once at startup (from the
 * saved {@link com.paddleshock.settings.GameSettings}) and again whenever the player changes it in
 * the Settings screen; every UI state re-reads {@link #t} on its next rebuild, so no restart is
 * needed - screens already rebuild their text on every {@code onEnable()}.
 */
public final class I18n {

    private static Lang currentLang = Lang.EN;
    private static Properties current = new Properties();
    private static Properties fallback = new Properties();

    private I18n() {
    }

    public static void setLanguage(Lang lang) {
        currentLang = lang == null ? Lang.EN : lang;
        current = load(currentLang);
        fallback = currentLang == Lang.EN ? current : load(Lang.EN);
    }

    public static Lang getLanguage() {
        return currentLang;
    }

    /** Looks up {@code key} in the active language, falling back to English, then the key itself
     *  (so a missing translation still shows readable text instead of blowing up). */
    public static String t(String key) {
        String value = current.getProperty(key);
        if (value != null) {
            return value;
        }
        value = fallback.getProperty(key);
        return value != null ? value : key;
    }

    /** {@link #t(String)} plus {@link String#format}, e.g. {@code t("profile.wins", 12)}. */
    public static String t(String key, Object... args) {
        return String.format(t(key), args);
    }

    private static Properties load(Lang lang) {
        Properties props = new Properties();
        String path = "/i18n/" + lang.getBundleName() + ".properties";
        try (Reader reader = new InputStreamReader(
                I18n.class.getResourceAsStream(path), StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (Exception e) {
            System.err.println("I18n: failed to load " + path + " - " + e.getMessage());
        }
        return props;
    }
}

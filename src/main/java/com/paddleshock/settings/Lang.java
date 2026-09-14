package com.paddleshock.settings;

/**
 * Display language for all in-game UI text. Picked in the Settings screen's LANGUAGE card and
 * persisted in {@link GameSettings}; drives which {@code i18n/strings_*.properties} bundle
 * {@link com.paddleshock.i18n.I18n} loads.
 */
public enum Lang {

    EN("English", "United States", "strings_en"),
    PT_BR("Português", "Brasil", "strings_pt_BR");

    private final String displayName;
    private final String regionName;
    private final String bundleName;

    Lang(String displayName, String regionName, String bundleName) {
        this.displayName = displayName;
        this.regionName = regionName;
        this.bundleName = bundleName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getRegionName() {
        return regionName;
    }

    /** Base filename (no extension) of this language's bundle under {@code i18n/} on the classpath. */
    public String getBundleName() {
        return bundleName;
    }
}

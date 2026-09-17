package com.paddleshock.data;

import com.paddleshock.settings.GameSettings;

/**
 * Loads/saves {@link PlayerProfile} and {@link GameSettings} - see {@link SaveManager} (the
 * default implementation) for the actual storage behavior/docs. Exists so callers depend on an
 * interface rather than a static class, making them unit-testable/swappable.
 */
public interface ProfileStore {

    PlayerProfile loadProfile();

    void saveProfile(PlayerProfile profile);

    GameSettings loadSettings();

    void saveSettings(GameSettings settings);
}

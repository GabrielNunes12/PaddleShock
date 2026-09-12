package com.paddleshock.app;

import java.awt.Dimension;
import java.awt.Toolkit;

import com.jme3.app.SimpleApplication;
import com.jme3.system.AppSettings;
import com.simsilica.lemur.GuiGlobals;
import com.simsilica.lemur.style.BaseStyles;

import com.paddleshock.data.PlayerProfile;
import com.paddleshock.data.SaveManager;
import com.paddleshock.settings.GameSettings;
import com.paddleshock.ui.MainMenuState;
import com.paddleshock.ui.OptionsState;
import com.paddleshock.ui.PauseState;
import com.paddleshock.ui.SplashState;
import com.paddleshock.ui.StoreState;

/** App shell: owns save data and switches between the menu/gameplay app states. */
public class PaddleShockApp extends SimpleApplication {

    private PlayerProfile profile;
    private GameSettings gameSettings;

    private SplashState splashState;
    private MainMenuState mainMenuState;
    private PauseState pauseState;
    private OptionsState optionsState;
    private StoreState storeState;
    private GameplayAppState gameplayState;

    @Override
    public void simpleInitApp() {
        flyCam.setEnabled(false);
        setDisplayStatView(false);
        setDisplayFps(false);

        profile = SaveManager.loadProfile();
        gameSettings = SaveManager.loadSettings();

        GuiGlobals.initialize(this);
        BaseStyles.loadGlassStyle();
        GuiGlobals.getInstance().getStyles().setDefaultStyle("glass");

        splashState = new SplashState();
        mainMenuState = new MainMenuState();
        pauseState = new PauseState();
        optionsState = new OptionsState();
        storeState = new StoreState();

        stateManager.attach(splashState);
        stateManager.attach(mainMenuState);
        stateManager.attach(pauseState);
        stateManager.attach(optionsState);
        stateManager.attach(storeState);

        mainMenuState.setEnabled(false);
        pauseState.setEnabled(false);
        optionsState.setEnabled(false);
        storeState.setEnabled(false);
    }

    public PlayerProfile getProfile() {
        return profile;
    }

    public void saveProfile() {
        SaveManager.saveProfile(profile);
    }

    public GameSettings getGameSettings() {
        return gameSettings;
    }

    public void saveGameSettings() {
        SaveManager.saveSettings(gameSettings);
    }

    public void showMainMenu() {
        if (gameplayState != null) {
            stateManager.detach(gameplayState);
            gameplayState = null;
        }
        splashState.setEnabled(false);
        pauseState.setEnabled(false);
        optionsState.setEnabled(false);
        storeState.setEnabled(false);
        mainMenuState.setEnabled(true);
    }

    public void startMatchVsAI() {
        mainMenuState.setEnabled(false);
        if (gameplayState != null) {
            stateManager.detach(gameplayState);
        }
        gameplayState = new GameplayAppState();
        stateManager.attach(gameplayState);
    }

    public void showPause() {
        gameplayState.setEnabled(false);
        pauseState.setEnabled(true);
    }

    public void resumeMatch() {
        pauseState.setEnabled(false);
        gameplayState.setEnabled(true);
    }

    public void quitToMainMenu() {
        pauseState.setEnabled(false);
        if (gameplayState != null) {
            stateManager.detach(gameplayState);
            gameplayState = null;
        }
        mainMenuState.setEnabled(true);
    }

    public void showStore() {
        mainMenuState.setEnabled(false);
        storeState.setEnabled(true);
    }

    public void showOptions(Runnable backAction) {
        optionsState.setBackAction(backAction);
        mainMenuState.setEnabled(false);
        pauseState.setEnabled(false);
        optionsState.setEnabled(true);
    }

    /** Rebuilds the display (resolution/fullscreen/antialiasing) from the current settings and restarts. */
    public void applyDisplaySettings() {
        AppSettings newSettings = new AppSettings(true);
        newSettings.copyFrom(settings);
        newSettings.setSamples(gameSettings.getVideoQuality().getSamples());

        if (gameSettings.isFullscreen()) {
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            newSettings.setResolution(screenSize.width, screenSize.height);
            newSettings.setFullscreen(true);
        } else {
            newSettings.setResolution(gameSettings.getResolution().getWidth(), gameSettings.getResolution().getHeight());
            newSettings.setFullscreen(false);
        }

        setSettings(newSettings);
        restart();
    }
}

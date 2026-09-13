package com.paddleshock.app;

import java.awt.Dimension;
import java.awt.Toolkit;
import java.util.concurrent.ThreadLocalRandom;

import com.jme3.app.SimpleApplication;
import com.jme3.system.AppSettings;
import com.simsilica.lemur.GuiGlobals;
import com.simsilica.lemur.style.BaseStyles;

import com.paddleshock.GameConstants;
import com.paddleshock.audio.AudioManager;
import com.paddleshock.data.PlayerProfile;
import com.paddleshock.data.SaveManager;
import com.paddleshock.settings.GameSettings;
import com.paddleshock.ui.MainMenuState;
import com.paddleshock.ui.MatchEndState;
import com.paddleshock.ui.OptionsState;
import com.paddleshock.ui.PauseState;
import com.paddleshock.ui.SplashState;
import com.paddleshock.ui.StoreState;

/** App shell: owns save data and switches between the menu/gameplay app states. */
public class PaddleShockApp extends SimpleApplication {

    private PlayerProfile profile;
    private GameSettings gameSettings;
    private AudioManager audioManager;

    private SplashState splashState;
    private MainMenuState mainMenuState;
    private PauseState pauseState;
    private OptionsState optionsState;
    private StoreState storeState;
    private MatchEndState matchEndState;
    private GameplayAppState gameplayState;

    @Override
    public void simpleInitApp() {
        flyCam.setEnabled(false);
        setDisplayStatView(false);
        setDisplayFps(false);
        // jME's SimpleApplication binds Escape to quitting the app by default; we use
        // Escape for our own pause menu instead, so drop that binding.
        inputManager.deleteMapping(INPUT_MAPPING_EXIT);

        profile = SaveManager.loadProfile();
        gameSettings = SaveManager.loadSettings();
        audioManager = new AudioManager(assetManager, gameSettings);

        GuiGlobals.initialize(this);
        BaseStyles.loadGlassStyle();
        GuiGlobals.getInstance().getStyles().setDefaultStyle("glass");

        splashState = new SplashState();
        mainMenuState = new MainMenuState();
        pauseState = new PauseState();
        optionsState = new OptionsState();
        storeState = new StoreState();
        matchEndState = new MatchEndState();

        stateManager.attach(splashState);
        stateManager.attach(mainMenuState);
        stateManager.attach(pauseState);
        stateManager.attach(optionsState);
        stateManager.attach(storeState);
        stateManager.attach(matchEndState);

        mainMenuState.setEnabled(false);
        pauseState.setEnabled(false);
        optionsState.setEnabled(false);
        storeState.setEnabled(false);
        matchEndState.setEnabled(false);
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

    public AudioManager getAudioManager() {
        return audioManager;
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
        matchEndState.setEnabled(false);
        mainMenuState.setEnabled(true);
        audioManager.playMenuMusic();
    }

    public void startMatchVsAI() {
        mainMenuState.setEnabled(false);
        matchEndState.setEnabled(false);
        if (gameplayState != null) {
            stateManager.detach(gameplayState);
        }
        gameplayState = new GameplayAppState();
        stateManager.attach(gameplayState);
        audioManager.playRandomMatchMusic();
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

    /** Called by the gameplay state once a side reaches the winning score; awards credits on a player win. */
    public void endMatch(boolean playerWon, int playerScore, int opponentScore) {
        gameplayState.setEnabled(false);
        audioManager.stopMusic();
        audioManager.playSfx(playerWon ? "match_win.ogg" : "match_defeat.ogg");

        int reward = 0;
        if (playerWon) {
            reward = ThreadLocalRandom.current().nextInt(GameConstants.MATCH_REWARD_MIN, GameConstants.MATCH_REWARD_MAX + 1);
            profile.addCurrency(reward);
            saveProfile();
        }

        matchEndState.setResult(playerWon, reward, playerScore, opponentScore);
        matchEndState.setEnabled(true);
    }

    public void showStore() {
        mainMenuState.setEnabled(false);
        matchEndState.setEnabled(false);
        storeState.setEnabled(true);
        audioManager.playMenuMusic();
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

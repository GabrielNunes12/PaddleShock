package com.paddleshock.app;

import java.awt.Dimension;
import java.awt.Toolkit;
import java.util.concurrent.ThreadLocalRandom;

import com.jme3.app.SimpleApplication;
import com.jme3.system.AppSettings;
import com.simsilica.lemur.GuiGlobals;
import com.simsilica.lemur.style.BaseStyles;

import java.io.IOException;
import java.net.SocketException;

import com.paddleshock.GameConstants;
import com.paddleshock.audio.AudioManager;
import com.paddleshock.data.PlayerProfile;
import com.paddleshock.data.SaveManager;
import com.paddleshock.net.NetClient;
import com.paddleshock.net.NetHost;
import com.paddleshock.net.RankClient;
import com.paddleshock.net.RankState;
import com.paddleshock.settings.GameSettings;
import com.paddleshock.steam.SteamManager;
import com.paddleshock.ui.LoadoutState;
import com.paddleshock.ui.MainMenuState;
import com.paddleshock.ui.MatchEndState;
import com.paddleshock.ui.MultiplayerState;
import com.paddleshock.ui.OptionsState;
import com.paddleshock.ui.PauseState;
import com.paddleshock.ui.SplashState;
import com.paddleshock.ui.StoreState;

/** App shell: owns save data and switches between the menu/gameplay app states. */
public class PaddleShockApp extends SimpleApplication {

    private PlayerProfile profile;
    /** This player's rank as of just before the current joined match started - see
     *  {@link #enterJoinedMatch} / {@link #endRankedJoinerMatch}. */
    private volatile RankState preMatchRank;
    private GameSettings gameSettings;
    private AudioManager audioManager;
    private SteamManager steamManager;

    private SplashState splashState;
    private MainMenuState mainMenuState;
    private PauseState pauseState;
    private OptionsState optionsState;
    private StoreState storeState;
    private MatchEndState matchEndState;
    private LoadoutState loadoutState;
    private MultiplayerState multiplayerState;
    private GameplayAppState gameplayState;

    @Override
    public void simpleInitApp() {
        flyCam.setEnabled(false);
        setDisplayStatView(false);
        setDisplayFps(false);
        // jME's SimpleApplication binds Escape to quitting the app by default; we use
        // Escape for our own pause menu instead, so drop that binding.
        inputManager.deleteMapping(INPUT_MAPPING_EXIT);

        steamManager = new SteamManager();

        profile = SaveManager.loadProfile();
        // getPlayerId() lazily generates one on a save that predates it - persist that
        // immediately so it doesn't silently regenerate (and orphan any ranked-ladder history
        // tied to the old id) on the next launch.
        profile.getPlayerId();
        SaveManager.saveProfile(profile);
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
        loadoutState = new LoadoutState();
        multiplayerState = new MultiplayerState();

        stateManager.attach(splashState);
        stateManager.attach(mainMenuState);
        stateManager.attach(pauseState);
        stateManager.attach(optionsState);
        stateManager.attach(storeState);
        stateManager.attach(matchEndState);
        stateManager.attach(loadoutState);
        stateManager.attach(multiplayerState);

        mainMenuState.setEnabled(false);
        pauseState.setEnabled(false);
        optionsState.setEnabled(false);
        storeState.setEnabled(false);
        matchEndState.setEnabled(false);
        loadoutState.setEnabled(false);
        multiplayerState.setEnabled(false);
    }

    @Override
    public void simpleUpdate(float tpf) {
        steamManager.update();
    }

    @Override
    public void destroy() {
        steamManager.shutdown();
        super.destroy();
    }

    public SteamManager getSteamManager() {
        return steamManager;
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
        loadoutState.setEnabled(false);
        multiplayerState.setEnabled(false);
        mainMenuState.setEnabled(true);
        audioManager.playMenuMusic();
    }

    /** Shows the HOST/JOIN LAN multiplayer screen (wired up from the main menu's MULTIPLAYER button). */
    public void showMultiplayer() {
        mainMenuState.setEnabled(false);
        multiplayerState.setEnabled(true);
    }

    /** Shown before every match (fresh or rematch) to confirm/change loadout and buy from a store modal. */
    public void showLoadout() {
        mainMenuState.setEnabled(false);
        matchEndState.setEnabled(false);
        loadoutState.setEnabled(true);
        audioManager.playMenuMusic();
    }

    public void startMatchVsAI() {
        mainMenuState.setEnabled(false);
        matchEndState.setEnabled(false);
        loadoutState.setEnabled(false);
        if (gameplayState != null) {
            stateManager.detach(gameplayState);
        }
        gameplayState = new GameplayAppState();
        stateManager.attach(gameplayState);
        audioManager.playRandomMatchMusic();
    }

    /** Binds a UDP socket on {@code port} (0 = let the OS pick a free port) for a LAN
     *  listen-server match and returns it; the caller ({@link MultiplayerState}) shows the
     *  local IP/port and waits for a joiner before actually entering the match via
     *  {@link #enterHostedMatch(NetHost)}. */
    public NetHost startHostMatch(int port) throws SocketException {
        return new NetHost(port, profile.getPlayerId());
    }

    /** Connects to a LAN host at {@code hostAddress}:{@code port} and returns the client; the
     *  caller ({@link MultiplayerState}) waits for the handshake to complete before actually
     *  entering the match via {@link #enterJoinedMatch(NetClient)}. */
    public NetClient joinMatch(String hostAddress, int port) throws IOException {
        return new NetClient(hostAddress, port, profile.getPlayerId());
    }

    /** Connects to a host via an AWS lobby code instead of a typed IP:port - see
     *  {@link NetClient#connectByLobbyCode}. */
    public NetClient joinMatchByLobbyCode(String code) throws IOException {
        return NetClient.connectByLobbyCode(code, profile.getPlayerId());
    }

    /** Enters the match as the listen-server host, once a joiner has connected to {@code netHost}. */
    public void enterHostedMatch(NetHost netHost) {
        mainMenuState.setEnabled(false);
        multiplayerState.setEnabled(false);
        if (gameplayState != null) {
            stateManager.detach(gameplayState);
        }
        gameplayState = new GameplayAppState(netHost);
        stateManager.attach(gameplayState);
        audioManager.playRandomMatchMusic();
    }

    /** Enters the match as the joiner, once the handshake with {@code netClient}'s host has completed. */
    public void enterJoinedMatch(NetClient netClient) {
        mainMenuState.setEnabled(false);
        multiplayerState.setEnabled(false);
        if (gameplayState != null) {
            stateManager.detach(gameplayState);
        }
        gameplayState = new GameplayAppState(netClient);
        stateManager.attach(gameplayState);
        audioManager.playRandomMatchMusic();

        // Snapshot this player's rank now, before the match: the joiner has no way to learn its
        // own LP delta from the host's report the way endRankedHostMatch does directly (that
        // response is never relayed back over the game's own protocol) - endRankedJoinerMatch
        // computes the delta itself by diffing against this baseline instead.
        preMatchRank = null;
        String playerId = profile.getPlayerId();
        Thread thread = new Thread(() -> {
            try {
                preMatchRank = RankClient.getRank(playerId);
            } catch (IOException e) {
                preMatchRank = null; // endRankedJoinerMatch falls back to "no delta shown"
            }
        }, "rank-prefetch");
        thread.setDaemon(true);
        thread.start();
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
        int reward = endMatchCommon(playerWon, playerScore, opponentScore);
        matchEndState.setResult(playerWon, reward, playerScore, opponentScore);
        matchEndState.setEnabled(true);
    }

    /** Same as {@link #endMatch}, but for the HOST side of a ranked multiplayer match: also
     *  reports the result for both players (host is the sole reporter - it already owns the
     *  authoritative simulation, so this isn't a new trust boundary) and shows the LP/rank
     *  change once that call returns. {@code joinerPlayerId} may be empty if the joiner connected
     *  without one (an older client) - the report is skipped in that case, LAN play still works.
     *  {@code lobbyCode} is {@code NetHost.getLobbyCode()} for a lobby-code (internet) match, or
     *  {@code null} for a direct IP:port LAN match - see {@code RankClient.reportMatchResult} for
     *  what the backend does with it. */
    public void endRankedHostMatch(boolean playerWon, int playerScore, int opponentScore,
            String joinerPlayerId, String lobbyCode) {
        int reward = endMatchCommon(playerWon, playerScore, opponentScore);
        matchEndState.setRankedResult(playerWon, reward, playerScore, opponentScore);
        matchEndState.setEnabled(true);

        if (joinerPlayerId == null || joinerPlayerId.isEmpty()) {
            matchEndState.reportRankResult(null);
            return;
        }
        String hostPlayerId = profile.getPlayerId();
        Thread thread = new Thread(() -> {
            RankState result = null;
            try {
                String matchId = java.util.UUID.randomUUID().toString();
                result = RankClient.reportMatchResult(matchId, hostPlayerId, joinerPlayerId, playerWon, lobbyCode)
                        .getHost();
            } catch (IOException e) {
                // offline, or the rank service is unreachable - the match itself already
                // completed normally, so just show "rank unavailable" rather than fail anything.
            }
            matchEndState.reportRankResult(result);
        }, "rank-report");
        thread.setDaemon(true);
        thread.start();
    }

    /** Same as {@link #endMatch}, but for the JOINER side of a ranked multiplayer match: the
     *  host already reported the result for both players, so this just re-fetches this player's
     *  own updated rank for display. */
    public void endRankedJoinerMatch(boolean playerWon, int playerScore, int opponentScore) {
        int reward = endMatchCommon(playerWon, playerScore, opponentScore);
        matchEndState.setRankedResult(playerWon, reward, playerScore, opponentScore);
        matchEndState.setEnabled(true);

        String playerId = profile.getPlayerId();
        Thread thread = new Thread(() -> {
            RankState fetched = null;
            try {
                // enterJoinedMatch's own prefetch thread may genuinely not have finished yet -
                // an unrealistically fast match (or just an unlucky HTTP round trip) can outrun
                // it. Wait briefly for it rather than treating "not yet set" as "unavailable" and
                // silently showing a 0 delta for a real rank change.
                RankState baseline = waitForPreMatchRank();
                int baselineGames = baseline == null ? -1 : baseline.getWins() + baseline.getLosses();

                // The host's own reportMatchResult call runs independently on a different
                // machine, triggered by the same match-over event this side just reacted to -
                // it may not have finished writing yet either. Poll briefly for this player's
                // game count to actually move rather than risk showing the stale pre-match state.
                for (int attempt = 0; attempt < 6; attempt++) {
                    fetched = RankClient.getRank(playerId);
                    if (baselineGames < 0 || fetched.getWins() + fetched.getLosses() != baselineGames) {
                        break;
                    }
                    fetched = null;
                    Thread.sleep(400);
                }
                if (fetched == null) {
                    fetched = RankClient.getRank(playerId); // gave up waiting - show whatever's there
                }
                matchEndState.reportRankResult(fetched.withDeltaFrom(baseline));
            } catch (IOException e) {
                matchEndState.reportRankResult(null); // offline, or the rank service is unreachable
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                matchEndState.reportRankResult(null);
            }
        }, "rank-fetch");
        thread.setDaemon(true);
        thread.start();
    }

    /** Blocks (on the calling background thread - never the render thread) up to ~2s for
     *  {@link #enterJoinedMatch}'s rank prefetch to land, in case the match ended before it did. */
    private RankState waitForPreMatchRank() throws InterruptedException {
        for (int i = 0; i < 10 && preMatchRank == null; i++) {
            Thread.sleep(200);
        }
        return preMatchRank;
    }

    private int endMatchCommon(boolean playerWon, int playerScore, int opponentScore) {
        gameplayState.setEnabled(false);
        audioManager.stopMusic();
        audioManager.playSfx(playerWon ? "match_win.ogg" : "match_defeat.ogg");

        int reward = 0;
        if (playerWon) {
            reward = ThreadLocalRandom.current().nextInt(GameConstants.MATCH_REWARD_MIN, GameConstants.MATCH_REWARD_MAX + 1);
            profile.addCurrency(reward);
            saveProfile();
        }
        return reward;
    }

    public void showStore() {
        mainMenuState.setEnabled(false);
        matchEndState.setEnabled(false);
        storeState.showFull(this::showMainMenu);
        storeState.setEnabled(true);
        audioManager.playMenuMusic();
    }

    /** Opens the store as a dimmed modal on top of whatever's currently shown (match setup), without navigating away. */
    public void showStoreModal(Runnable onClose) {
        storeState.showAsModal(onClose);
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

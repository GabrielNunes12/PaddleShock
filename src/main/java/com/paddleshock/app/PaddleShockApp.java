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
import com.paddleshock.net.NetProtocol;
import com.paddleshock.net.RankClient;
import com.paddleshock.net.RankState;
import com.paddleshock.settings.GameSettings;
import com.paddleshock.steam.SteamManager;
import com.paddleshock.ui.LeaderboardState;
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
    private com.paddleshock.ui.HowToPlayState howToPlayState;
    private LeaderboardState leaderboardState;
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
        howToPlayState = new com.paddleshock.ui.HowToPlayState();
        leaderboardState = new LeaderboardState();

        stateManager.attach(splashState);
        stateManager.attach(mainMenuState);
        stateManager.attach(pauseState);
        stateManager.attach(optionsState);
        stateManager.attach(storeState);
        stateManager.attach(matchEndState);
        stateManager.attach(loadoutState);
        stateManager.attach(multiplayerState);
        stateManager.attach(howToPlayState);
        stateManager.attach(leaderboardState);

        mainMenuState.setEnabled(false);
        pauseState.setEnabled(false);
        optionsState.setEnabled(false);
        storeState.setEnabled(false);
        matchEndState.setEnabled(false);
        loadoutState.setEnabled(false);
        multiplayerState.setEnabled(false);
        howToPlayState.setEnabled(false);
        leaderboardState.setEnabled(false);
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
        howToPlayState.setEnabled(false);
        leaderboardState.setEnabled(false);
        mainMenuState.setEnabled(true);
        audioManager.playMenuMusic();
    }

    /** Shows the "HOW TO PLAY" screen: automatically once, right after the splash screen, for any
     *  save that hasn't seen it yet (see {@link com.paddleshock.data.PlayerProfile#hasSeenTutorial()}),
     *  and any time afterward from the main menu's own button. Viewing it (via its own GOT IT
     *  button) marks the profile as seen and saves it before running {@code backAction}. */
    public void showHowToPlay(Runnable backAction) {
        howToPlayState.setBackAction(backAction);
        splashState.setEnabled(false);
        mainMenuState.setEnabled(false);
        howToPlayState.setEnabled(true);
    }

    /** Shows the HOST/JOIN LAN multiplayer screen (wired up from the main menu's MULTIPLAYER button). */
    public void showMultiplayer() {
        mainMenuState.setEnabled(false);
        multiplayerState.setEnabled(true);
    }

    /** Shows the ranked ladder standings screen (wired up from the main menu's LEADERBOARD button). */
    public void showLeaderboard() {
        mainMenuState.setEnabled(false);
        leaderboardState.setEnabled(true);
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
    public NetHost startHostMatch(int port, boolean ranked) throws SocketException {
        return new NetHost(port, profile.getPlayerId(), ranked);
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
        gameplayState = new GameplayAppState(netHost, netHost.isRanked());
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
        boolean ranked = netClient.isRanked();
        gameplayState = new GameplayAppState(netClient, ranked);
        stateManager.attach(gameplayState);
        audioManager.playRandomMatchMusic();

        preMatchRank = null;
        if (!ranked) {
            // Unranked match (the host chose UNRANKED) - no ladder call needed, endMatch handles
            // match-end the same way single-player does.
            return;
        }
        // Snapshot this player's rank now, before the match: the joiner has no way to learn its
        // own LP delta from the host's report the way endRankedHostMatch does directly (that
        // response is never relayed back over the game's own protocol) - endRankedJoinerMatch
        // computes the delta itself by diffing against this baseline instead.
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

    public GameplayAppState getGameplayState() {
        return gameplayState;
    }

    /** Resumes a multiplayer rematch on the SAME connection/GameplayAppState instead of tearing
     *  the match down and rebuilding it - see {@code MatchEndState}'s rematch negotiation, which
     *  calls this only once both sides have agreed. */
    public void resumeMultiplayerRematch() {
        matchEndState.setEnabled(false);
        gameplayState.startNewMatch();
        gameplayState.setEnabled(true);
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
        int reward = endMatchCommon(playerWon, playerScore, opponentScore);
        matchEndState.setResult(playerWon, reward, playerScore, opponentScore);
        matchEndState.setEnabled(true);
    }

    /** Same as {@link #endMatch}, but for the HOST side of a ranked multiplayer match: also
     *  reports the result for both players (host is the sole reporter - it already owns the
     *  authoritative simulation, so this isn't a new trust boundary) and shows the LP/rank
     *  change once that call returns. If the joiner connected without a player id (an older
     *  client), the report is skipped, LAN play still works. {@code netHost.getLobbyCode()} is
     *  passed through so the backend can verify the report is backed by a real lobby session for
     *  internet matches (it's {@code null}, and therefore unverified, for a direct IP:port LAN
     *  match - see {@code RankClient.reportMatchResult}); once the report succeeds, the joiner's
     *  own authoritative result is relayed back over the still-open connection so it can show the
     *  real number instead of guessing via {@code withDeltaFrom} - see {@link #endRankedJoinerMatch}. */
    public void endRankedHostMatch(boolean playerWon, int playerScore, int opponentScore, NetHost netHost) {
        int reward = endMatchCommon(playerWon, playerScore, opponentScore);
        matchEndState.setRankedResult(playerWon, reward, playerScore, opponentScore);
        matchEndState.setEnabled(true);

        String joinerPlayerId = netHost == null ? "" : netHost.getJoinerPlayerId();
        if (joinerPlayerId == null || joinerPlayerId.isEmpty()) {
            matchEndState.reportRankResult(null);
            return;
        }
        String hostPlayerId = profile.getPlayerId();
        String lobbyCode = netHost.getLobbyCode();
        Thread thread = new Thread(() -> {
            RankState result = null;
            try {
                String matchId = java.util.UUID.randomUUID().toString();
                RankClient.MatchReportResult report =
                        RankClient.reportMatchResult(matchId, hostPlayerId, joinerPlayerId, playerWon, lobbyCode);
                result = report.getHost();
                // Relay the joiner's own authoritative result back over the still-open connection
                // so it can show the real number instead of guessing via withDeltaFrom - see
                // endRankedJoinerMatch. Best-effort: if the joiner already disconnected, NetHost
                // silently drops this and the joiner's own fallback kicks in.
                netHost.sendRankResult(report.getJoiner());
            } catch (IOException e) {
                // offline, or the rank service is unreachable - the match itself already
                // completed normally, so just show "rank unavailable" rather than fail anything.
            }
            matchEndState.reportRankResult(result);
        }, "rank-report");
        thread.setDaemon(true);
        thread.start();
    }

    /** Same as {@link #endRankedHostMatch}, but for a mid-match joiner disconnect/timeout: the
     *  host reports a forfeit win for itself (only if this was actually a ranked match - i.e. the
     *  joiner connected with a player id at all) and shows a real "opponent disconnected" notice
     *  rather than a plain win screen. */
    public void endRankedHostMatchByForfeit(int playerScore, int opponentScore, NetHost netHost, boolean wasRanked) {
        int reward = endMatchCommon(true, playerScore, opponentScore);
        String joinerPlayerId = netHost == null ? "" : netHost.getJoinerPlayerId();
        boolean ranked = wasRanked && joinerPlayerId != null && !joinerPlayerId.isEmpty();

        if (ranked) {
            matchEndState.setRankedResult(true, reward, playerScore, opponentScore);
        } else {
            matchEndState.setResult(true, reward, playerScore, opponentScore);
        }
        matchEndState.setExtraNotice("Opponent disconnected - win awarded by forfeit");
        matchEndState.setEnabled(true);

        if (!ranked) {
            return;
        }
        String hostPlayerId = profile.getPlayerId();
        String lobbyCode = netHost.getLobbyCode();
        Thread thread = new Thread(() -> {
            RankState result = null;
            try {
                String matchId = java.util.UUID.randomUUID().toString();
                // The joiner is gone - no relay is possible or needed; it never shows a ranked
                // result at all for a timeout (see handleJoinerConnectionLost).
                result = RankClient.reportMatchResult(matchId, hostPlayerId, joinerPlayerId, true, lobbyCode).getHost();
            } catch (IOException e) {
                // offline, or the rank service is unreachable - the forfeit itself still stands.
            }
            matchEndState.reportRankResult(result);
        }, "rank-report-forfeit");
        thread.setDaemon(true);
        thread.start();
    }

    /** A joiner whose host vanished mid-match: show a real "connection lost" dead end instead of
     *  freezing on the last snapshot forever. Deliberately does NOT report anything to the ranked
     *  ladder - only the host reports match results (see the ranked-ladder trust model in {@code
     *  aws/README.md}); a joiner can't verify anything the host isn't also seeing. */
    public void handleJoinerConnectionLost(int playerScore, int opponentScore) {
        gameplayState.setEnabled(false);
        audioManager.stopMusic();
        matchEndState.setConnectionLost(playerScore, opponentScore);
        matchEndState.setEnabled(true);
    }

    /** Same as {@link #endMatch}, but for the JOINER side of a ranked multiplayer match: the
     *  host already reported the result for both players, so this just re-fetches this player's
     *  own updated rank for display. */
    public void endRankedJoinerMatch(boolean playerWon, int playerScore, int opponentScore, NetClient netClient) {
        int reward = endMatchCommon(playerWon, playerScore, opponentScore);
        matchEndState.setRankedResult(playerWon, reward, playerScore, opponentScore);
        matchEndState.setEnabled(true);

        String playerId = profile.getPlayerId();
        Thread thread = new Thread(() -> {
            // Prefer the host's own relayed authoritative result (see NetHost.sendRankResult /
            // endRankedHostMatch) - it's the actual Lambda response, not a guess. Only fall back
            // to the guess-based diff below if the relay never arrives (the host's report call
            // failed entirely, or the connection dropped right after match-end).
            RankState relayed = waitForRelayedRankResult(netClient);
            if (relayed != null) {
                matchEndState.reportRankResult(relayed);
                return;
            }

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

    /** Polls for the host's relayed {@code TYPE_RANK_RESULT} for a few seconds, or gives up and
     *  returns {@code null} (the caller then falls back to the guess-based diff). Runs on the
     *  calling background thread, never the render thread. */
    private RankState waitForRelayedRankResult(NetClient netClient) {
        if (netClient == null) {
            return null;
        }
        for (int i = 0; i < 15; i++) { // ~3s at 200ms
            NetProtocol.RankResultMessage msg = netClient.pollRelayedRankResult();
            if (msg != null) {
                return RankState.fromRelay(msg.tier(), msg.division(), msg.lp(), msg.wins(), msg.losses(),
                        msg.lpChange(), msg.promoted(), msg.demoted(), msg.promoSeriesResult());
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
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

package com.paddleshock.app;

import java.awt.Dimension;
import java.awt.Toolkit;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.jme3.app.SimpleApplication;
import com.jme3.system.AppSettings;
import com.simsilica.lemur.GuiGlobals;
import com.simsilica.lemur.style.BaseStyles;

import java.io.IOException;
import java.net.SocketException;

import com.paddleshock.GameConstants;
import com.paddleshock.audio.AudioManager;
import com.paddleshock.data.MatchHistoryEntry;
import com.paddleshock.data.PlayerProfile;
import com.paddleshock.data.SaveManager;
import com.paddleshock.diagnostics.CrashReporter;
import com.paddleshock.diagnostics.NetLog;
import com.paddleshock.net.NetClient;
import com.paddleshock.net.NetHost;
import com.paddleshock.net.NetProtocol;
import com.paddleshock.net.RankClient;
import com.paddleshock.net.RankState;
import com.paddleshock.net.TournamentClient;
import com.paddleshock.settings.GameSettings;
import com.paddleshock.steam.SteamManager;
import com.paddleshock.ui.LeaderboardState;
import com.paddleshock.ui.LoadoutState;
import com.paddleshock.ui.MainMenuState;
import com.paddleshock.ui.MatchEndState;
import com.paddleshock.ui.MultiplayerState;
import com.paddleshock.ui.OptionsState;
import com.paddleshock.ui.PauseState;
import com.paddleshock.ui.ProfileState;
import com.paddleshock.ui.SplashState;
import com.paddleshock.ui.StoreState;
import com.paddleshock.ui.TournamentState;

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
    private ProfileState profileState;
    private TournamentState tournamentState;
    private GameplayAppState gameplayState;

    /** Set while the currently active {@link GameplayAppState} match is one bracket pairing of a
     *  live tournament, so {@link #endMatch} knows to also report the result to the tournament
     *  backend - see {@link #setActiveTournamentContext}. Cleared once reported (or once the
     *  match-end path that would report it is not reached, e.g. a plain quit-to-menu). A tournament
     *  match otherwise runs through the exact same unranked HOST/JOINER match-end path as a normal
     *  LAN match - this is the only extra bit of state it needs. */
    private volatile TournamentMatchContext activeTournamentContext;

    /** See {@link #activeTournamentContext}. {@code selfPlayerId}/{@code opponentPlayerId} are
     *  this pairing's two player ids (whichever order - the winner is derived from {@code
     *  playerWon} at report time, not from p1/p2 order). */
    public record TournamentMatchContext(String code, int roundIndex, int matchIndex,
            String selfPlayerId, String opponentPlayerId) {
    }

    @Override
    public void simpleInitApp() {
        flyCam.setEnabled(false);
        setDisplayStatView(false);
        setDisplayFps(false);
        // jME's SimpleApplication binds Escape to quitting the app by default; we use
        // Escape for our own pause menu instead, so drop that binding.
        inputManager.deleteMapping(INPUT_MAPPING_EXIT);

        steamManager = new SteamManager();

        // See com.paddleshock.diagnostics.CrashReporter - lets a crash report note whether a
        // match was in progress and what mode, read lazily at crash time (never eagerly).
        CrashReporter.setContextSupplier(this::describeMatchStateForCrashReport);

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
        profileState = new ProfileState();
        tournamentState = new TournamentState();

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
        stateManager.attach(profileState);
        stateManager.attach(tournamentState);

        mainMenuState.setEnabled(false);
        pauseState.setEnabled(false);
        optionsState.setEnabled(false);
        storeState.setEnabled(false);
        matchEndState.setEnabled(false);
        loadoutState.setEnabled(false);
        multiplayerState.setEnabled(false);
        howToPlayState.setEnabled(false);
        leaderboardState.setEnabled(false);
        profileState.setEnabled(false);
        tournamentState.setEnabled(false);
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

    /** jME's own render-thread error path (see {@code LegacyApplication.handleError}): called
     *  instead of letting a render-thread exception propagate as an uncaught exception, so it
     *  would otherwise bypass {@link Thread#setDefaultUncaughtExceptionHandler}. Route it through
     *  the same local crash reporting as every other thread before falling back to jME's own
     *  handling (logging + stopping the app). */
    @Override
    public void handleError(String message, Throwable throwable) {
        CrashReporter.reportRenderThreadError(message, throwable);
        super.handleError(message, throwable);
    }

    /** Best-effort description of current match state for {@link CrashReporter} - whether a match
     *  is in progress and which mode (single-player/host/joiner), if cheaply available. Read
     *  lazily at crash time, so this must never assume any particular init order has completed. */
    private String describeMatchStateForCrashReport() {
        GameplayAppState state = gameplayState;
        if (state == null || !state.isEnabled()) {
            return "no match in progress";
        }
        String mode = switch (state.getMode()) {
            case SINGLE_PLAYER -> "single-player";
            case HOST -> "host";
            case JOINER -> "joiner";
            case SPECTATOR -> "spectator";
        };
        return mode + " match in progress";
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
        profileState.setEnabled(false);
        tournamentState.setEnabled(false);
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
        matchEndState.setEnabled(false);
        tournamentState.setEnabled(false);
        multiplayerState.setEnabled(true);
    }

    /** Shows the tournament create/join/bracket screen (wired up from the Multiplayer screen's
     *  TOURNAMENT button). */
    public void showTournament() {
        multiplayerState.setEnabled(false);
        tournamentState.setEnabled(true);
    }

    /** Records that the match about to start (or already running) is one bracket pairing of a
     *  live tournament - see {@link #activeTournamentContext}. Called by {@link TournamentState}
     *  right before handing off to {@link #enterHostedMatch}/{@link #enterJoinedMatch}. */
    public void setActiveTournamentContext(TournamentMatchContext context) {
        activeTournamentContext = context;
    }

    /** Shows the ranked ladder standings screen (wired up from the main menu's LEADERBOARD button). */
    public void showLeaderboard() {
        mainMenuState.setEnabled(false);
        leaderboardState.setEnabled(true);
    }

    /** Shows the player's own profile: display name, current rank, and local match history
     *  (wired up from the main menu's PROFILE button). */
    public void showProfile() {
        mainMenuState.setEnabled(false);
        profileState.setEnabled(true);
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
        return joinMatch(hostAddress, port, false);
    }

    /** {@code spectator} - see {@code MultiplayerState}'s JOINING view spectate toggle and
     *  {@link NetClient#isSpectator()}. A spectator's loadout is irrelevant, so an empty one is
     *  sent rather than this player's own equipped kit. */
    public NetClient joinMatch(String hostAddress, int port, boolean spectator) throws IOException {
        List<String> loadout = spectator ? List.of() : profile.getLoadout();
        return new NetClient(hostAddress, port, profile.getPlayerId(), loadout, spectator);
    }

    /** Connects to a host via an AWS lobby code instead of a typed IP:port - see
     *  {@link NetClient#connectByLobbyCode}. */
    public NetClient joinMatchByLobbyCode(String code) throws IOException {
        return joinMatchByLobbyCode(code, false);
    }

    /** {@code spectator} - see {@link #joinMatch(String, int, boolean)}. */
    public NetClient joinMatchByLobbyCode(String code, boolean spectator) throws IOException {
        List<String> loadout = spectator ? List.of() : profile.getLoadout();
        return NetClient.connectByLobbyCode(code, profile.getPlayerId(), loadout, spectator);
    }

    /** Enters the match as the listen-server host, once a joiner has connected to {@code netHost}. */
    public void enterHostedMatch(NetHost netHost) {
        mainMenuState.setEnabled(false);
        multiplayerState.setEnabled(false);
        tournamentState.setEnabled(false);
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
        tournamentState.setEnabled(false);
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
                NetLog.log("rank-prefetch failed for player " + playerId, e);
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
        activeTournamentContext = null;
        mainMenuState.setEnabled(true);
    }

    /** Called by the gameplay state once a side reaches the winning score; awards credits on a
     *  player win. {@code opponentPlayerId} is the real opponent's ranked-ladder id for an
     *  unranked LAN/lobby match (known via HELLO on the host side, WELCOME on the joiner side -
     *  see {@code NetHost#getJoinerPlayerId}/{@code NetClient#getHostPlayerId}), used to update the
     *  local rival tracker; {@code null} for single-player, which has no real opponent. */
    public void endMatch(boolean playerWon, int playerScore, int opponentScore, String opponentPlayerId) {
        String mode = matchModeFor(gameplayState);
        int reward = endMatchCommon(playerWon, playerScore, opponentScore);
        matchEndState.setResult(playerWon, reward, playerScore, opponentScore);
        matchEndState.setEnabled(true);
        recordMatchHistory(mode, playerScore, opponentScore, playerWon, 0, opponentPlayerId);
        reportTournamentResultIfActive(playerWon);
    }

    /** If this match was one bracket pairing of a live tournament (see
     *  {@link #activeTournamentContext}), also reports the result to the tournament backend -
     *  idempotent server-side, so no coordination is needed with the opponent's own client also
     *  calling this. Best-effort: a failure here doesn't affect the match that already completed
     *  normally. The context is cleared immediately (not just after the call returns) so a
     *  subsequent unrelated match (e.g. a single-player match played right after) never re-reports it. */
    private void reportTournamentResultIfActive(boolean playerWon) {
        TournamentMatchContext context = activeTournamentContext;
        if (context == null) {
            return;
        }
        activeTournamentContext = null;
        String winnerId = playerWon ? context.selfPlayerId() : context.opponentPlayerId();
        Thread thread = new Thread(() -> {
            try {
                TournamentClient.reportTournamentMatchResult(context.code(), context.roundIndex(),
                        context.matchIndex(), winnerId, context.selfPlayerId());
            } catch (IOException e) {
                NetLog.log("tournament match report failed", e);
            }
        }, "tournament-report");
        thread.setDaemon(true);
        thread.start();
    }

    /** "vs AI" / "LAN Host" / "LAN Join" for a completed match's {@link GameplayAppState#getMode()} -
     *  used to label a {@link MatchHistoryEntry}. Ranked matches are always labeled "Ranked"
     *  instead, from the ranked-specific match-end methods below. */
    private String matchModeFor(GameplayAppState state) {
        return switch (state.getMode()) {
            case SINGLE_PLAYER -> "vs AI";
            case HOST -> "LAN Host";
            case JOINER -> "LAN Join";
            // Never actually reaches recordMatchHistory - a spectator's match-end goes through
            // endSpectatedMatch instead, which never calls this - but the switch must still be
            // exhaustive.
            case SPECTATOR -> "Spectator";
        };
    }

    /** Appends a match to the local match history, updates the local rival tracker if the real
     *  opponent's playerId is known ({@code opponentPlayerId} null/blank for single-player, which
     *  skips the rival update entirely), and persists the profile once for both - see
     *  {@code PlayerProfile#addMatchHistoryEntry}/{@code PlayerProfile#recordRivalResult}. No
     *  display-name hint is threaded through here (there's no cross-network display-name system
     *  yet), so a rival always falls back to the shortened-id display until one is added. */
    private void recordMatchHistory(String mode, int playerScore, int opponentScore, boolean won, int lpChange,
            String opponentPlayerId) {
        profile.addMatchHistoryEntry(new MatchHistoryEntry(System.currentTimeMillis(), mode, playerScore, opponentScore, won, lpChange));
        if (opponentPlayerId != null && !opponentPlayerId.isBlank()) {
            profile.recordRivalResult(opponentPlayerId, null, won);
        }
        saveProfile();
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
            recordMatchHistory("Ranked", playerScore, opponentScore, playerWon, 0, null);
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
                NetLog.log("ranked match report failed (host)", e);
            }
            matchEndState.reportRankResult(result);
            recordMatchHistory("Ranked", playerScore, opponentScore, playerWon,
                    result == null ? 0 : result.getLpChange(), joinerPlayerId);
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
            // Still an unranked LAN/lobby match with a known opponent (the joiner sent an id in
            // HELLO, just wasn't playing ranked) - record it against the rival tracker too.
            recordMatchHistory("LAN Host", playerScore, opponentScore, true, 0, joinerPlayerId);
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
                NetLog.log("ranked forfeit report failed (host)", e);
            }
            matchEndState.reportRankResult(result);
            recordMatchHistory("Ranked", playerScore, opponentScore, true,
                    result == null ? 0 : result.getLpChange(), joinerPlayerId);
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

    /** A spectator's watched match is over - either it actually ended (the final snapshot's
     *  {@code FLAG_MATCH_OVER}) or the host connection was lost. Deliberately does NOT go through
     *  {@link #endMatch}/{@link #endRankedJoinerMatch}/{@code MatchEndState}'s rematch negotiation
     *  at all (those exist for the two real participants only) - a spectator was never in the
     *  match, so nothing here ever touches {@link PlayerProfile} (no match-history entry, no rival
     *  tracker update, no ranked report/fetch). Just tears down the gameplay state and drops the
     *  viewer back on the Multiplayer screen. */
    public void endSpectatedMatch() {
        if (gameplayState != null) {
            stateManager.detach(gameplayState);
            gameplayState = null;
        }
        audioManager.stopMusic();
        showMultiplayer();
    }

    /** Same as {@link #endMatch}, but for the JOINER side of a ranked multiplayer match: the
     *  host already reported the result for both players, so this just re-fetches this player's
     *  own updated rank for display. */
    public void endRankedJoinerMatch(boolean playerWon, int playerScore, int opponentScore, NetClient netClient) {
        int reward = endMatchCommon(playerWon, playerScore, opponentScore);
        matchEndState.setRankedResult(playerWon, reward, playerScore, opponentScore);
        matchEndState.setEnabled(true);

        String playerId = profile.getPlayerId();
        // The host's ranked-ladder playerId, learned from WELCOME (see NetProtocol.TYPE_WELCOME /
        // NetClient#getHostPlayerId) - "" for an older host that didn't send one, in which case
        // recordMatchHistory simply skips the rival update.
        String hostOpponentPlayerId = netClient == null ? null : netClient.getHostPlayerId();
        Thread thread = new Thread(() -> {
            // Prefer the host's own relayed authoritative result (see NetHost.sendRankResult /
            // endRankedHostMatch) - it's the actual Lambda response, not a guess. Only fall back
            // to the guess-based diff below if the relay never arrives (the host's report call
            // failed entirely, or the connection dropped right after match-end).
            RankState relayed = waitForRelayedRankResult(netClient);
            if (relayed != null) {
                matchEndState.reportRankResult(relayed);
                recordMatchHistory("Ranked", playerScore, opponentScore, playerWon, relayed.getLpChange(), hostOpponentPlayerId);
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
                RankState delta = fetched.withDeltaFrom(baseline);
                matchEndState.reportRankResult(delta);
                recordMatchHistory("Ranked", playerScore, opponentScore, playerWon, delta.getLpChange(), hostOpponentPlayerId);
            } catch (IOException e) {
                matchEndState.reportRankResult(null); // offline, or the rank service is unreachable
                recordMatchHistory("Ranked", playerScore, opponentScore, playerWon, 0, hostOpponentPlayerId);
                NetLog.log("ranked rank-fetch failed (joiner)", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                matchEndState.reportRankResult(null);
                recordMatchHistory("Ranked", playerScore, opponentScore, playerWon, 0, hostOpponentPlayerId);
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

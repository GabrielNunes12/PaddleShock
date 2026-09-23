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
import com.paddleshock.data.ProfileStore;
import com.paddleshock.data.SaveManager;
import com.paddleshock.diagnostics.CrashReporter;
import com.paddleshock.diagnostics.NetLog;
import com.paddleshock.net.InviteClient;
import com.paddleshock.net.InviteService;
import com.paddleshock.net.NetClient;
import com.paddleshock.net.NetHost;
import com.paddleshock.net.RankClient;
import com.paddleshock.net.RankService;
import com.paddleshock.net.TournamentClient;
import com.paddleshock.net.TournamentService;
import com.paddleshock.settings.GameSettings;
import com.paddleshock.steam.SteamManager;
import com.paddleshock.tour.TourOpponent;
import com.paddleshock.tour.WorldTour;
import com.paddleshock.ui.CreditsState;
import com.paddleshock.ui.WorldTourState;
import com.paddleshock.ui.FriendsState;
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

/** App shell: owns save data and switches between the menu/gameplay app states. Implements
 *  {@link Navigator}/{@link PlayerContext} so UI states can depend on those narrow contracts
 *  instead of this concrete class - see their docs. */
public class PaddleShockApp extends SimpleApplication implements Navigator, PlayerContext {

    // Composition root for these four services - see the class docs on RankService/InviteService/
    // TournamentService/ProfileStore for why they're interfaces rather than static classes.
    private final ProfileStore profileStore = new SaveManager();
    private final RankService rankService = new RankClient();
    private final InviteService inviteService = new InviteClient();
    private final TournamentService tournamentService = new TournamentClient();

    private PlayerProfile profile;
    /** Owns the ranked-ladder report/relay/forfeit orchestration - see its class docs for why
     *  that's pulled out of this class. Constructed in {@link #simpleInitApp} once {@link
     *  #matchEndState} exists. */
    private RankedMatchCoordinator rankedMatchCoordinator;
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
    private CreditsState creditsState;
    private WorldTourState worldTourState;
    private LeaderboardState leaderboardState;
    private ProfileState profileState;
    private TournamentState tournamentState;
    private FriendsState friendsState;
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

        profile = profileStore.loadProfile();
        // getPlayerId() lazily generates one on a save that predates it - persist that
        // immediately so it doesn't silently regenerate (and orphan any ranked-ladder history
        // tied to the old id) on the next launch.
        profile.getPlayerId();
        profileStore.saveProfile(profile);
        gameSettings = profileStore.loadSettings();
        com.paddleshock.i18n.I18n.setLanguage(gameSettings.getLanguage());
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
        creditsState = new CreditsState();
        worldTourState = new WorldTourState();
        leaderboardState = new LeaderboardState();
        profileState = new ProfileState();
        tournamentState = new TournamentState();
        friendsState = new FriendsState();

        rankedMatchCoordinator = new RankedMatchCoordinator(rankService, matchEndState,
                this::endMatchCommon, this::recordMatchHistory);

        stateManager.attach(splashState);
        stateManager.attach(mainMenuState);
        stateManager.attach(pauseState);
        stateManager.attach(optionsState);
        stateManager.attach(storeState);
        stateManager.attach(matchEndState);
        stateManager.attach(loadoutState);
        stateManager.attach(multiplayerState);
        stateManager.attach(howToPlayState);
        stateManager.attach(creditsState);
        stateManager.attach(worldTourState);
        stateManager.attach(leaderboardState);
        stateManager.attach(profileState);
        stateManager.attach(tournamentState);
        stateManager.attach(friendsState);

        mainMenuState.setEnabled(false);
        pauseState.setEnabled(false);
        optionsState.setEnabled(false);
        storeState.setEnabled(false);
        matchEndState.setEnabled(false);
        loadoutState.setEnabled(false);
        multiplayerState.setEnabled(false);
        howToPlayState.setEnabled(false);
        creditsState.setEnabled(false);
        worldTourState.setEnabled(false);
        leaderboardState.setEnabled(false);
        profileState.setEnabled(false);
        tournamentState.setEnabled(false);
        friendsState.setEnabled(false);
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
        profileStore.saveProfile(profile);
    }

    public GameSettings getGameSettings() {
        return gameSettings;
    }

    public AudioManager getAudioManager() {
        return audioManager;
    }

    public void saveGameSettings() {
        profileStore.saveSettings(gameSettings);
    }

    public RankService getRankService() {
        return rankService;
    }

    public InviteService getInviteService() {
        return inviteService;
    }

    public TournamentService getTournamentService() {
        return tournamentService;
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
        creditsState.setEnabled(false);
        worldTourState.setEnabled(false);
        leaderboardState.setEnabled(false);
        profileState.setEnabled(false);
        tournamentState.setEnabled(false);
        friendsState.setEnabled(false);
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

    /** Shows the CREDITS screen (third-party asset/library attribution) from the main menu. */
    public void showCredits(Runnable backAction) {
        creditsState.setBackAction(backAction);
        mainMenuState.setEnabled(false);
        creditsState.setEnabled(true);
    }

    /** Shows the World Tour ladder (main menu CTA, or back from a tour match's result screen). */
    public void showWorldTour() {
        if (gameplayState != null) {
            stateManager.detach(gameplayState);
            gameplayState = null;
        }
        mainMenuState.setEnabled(false);
        matchEndState.setEnabled(false);
        // Disable first so a re-show while already open still rebuilds with fresh progress.
        worldTourState.setEnabled(false);
        worldTourState.setEnabled(true);
        audioManager.playMenuMusic();
    }

    /** Starts a World Tour match against {@code opponent} with the player's equipped gear. */
    public void startTourMatch(TourOpponent opponent) {
        worldTourState.setEnabled(false);
        matchEndState.setEnabled(false);
        if (gameplayState != null) {
            stateManager.detach(gameplayState);
        }
        gameplayState = new GameplayAppState(opponent);
        stateManager.attach(gameplayState);
        audioManager.playRandomMatchMusic();
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

    /** Shows the local friends list screen (wired up from the main menu's FRIENDS button). */
    public void showFriends() {
        mainMenuState.setEnabled(false);
        friendsState.setEnabled(true);
    }

    /** Accepts a pending invite: navigates straight into Multiplayer's JOINING flow with
     *  {@code lobbyCode} pre-filled and immediately attempts to connect - reuses the exact
     *  existing join-by-code code path ({@link MultiplayerState#acceptInviteAndConnect}) rather
     *  than reinventing it. Called from the main menu's invite banner. */
    public void acceptInvite(String lobbyCode) {
        mainMenuState.setEnabled(false);
        multiplayerState.setEnabled(true);
        multiplayerState.acceptInviteAndConnect(lobbyCode);
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

        rankedMatchCoordinator.clearPreMatchRank();
        if (!ranked) {
            // Unranked match (the host chose UNRANKED) - no ladder call needed, endMatch handles
            // match-end the same way single-player does.
            return;
        }
        rankedMatchCoordinator.beginRankPrefetch(profile.getPlayerId());
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
        TourOpponent tourOpponent = gameplayState.getTourOpponent();
        if (tourOpponent != null) {
            endTourMatch(tourOpponent, playerWon, playerScore, opponentScore);
            return;
        }
        String mode = matchModeFor(gameplayState);
        int reward = endMatchCommon(playerWon, playerScore, opponentScore);
        matchEndState.setResult(playerWon, reward, playerScore, opponentScore);
        matchEndState.setEnabled(true);
        recordMatchHistory(mode, playerScore, opponentScore, playerWon, 0, opponentPlayerId);
        reportTournamentResultIfActive(playerWon);
    }

    /** World Tour match end: a first win against {@code opponent} pays its one-time reward and
     *  unlocks the next opponent; a replay win pays the normal random reward - see
     *  {@link WorldTour#winReward}. */
    private void endTourMatch(TourOpponent opponent, boolean playerWon, int playerScore, int opponentScore) {
        beginMatchEnd(playerWon);
        java.util.Set<String> beatenBefore = profile.getTourBeatenIds();
        int reward = 0;
        if (playerWon) {
            reward = WorldTour.winReward(beatenBefore, opponent, randomMatchReward());
            profile.addCurrency(reward);
            profile.markTourBeaten(opponent.id());
        }
        boolean firstWin = playerWon && !beatenBefore.contains(opponent.id());
        matchEndState.setResult(playerWon, reward, playerScore, opponentScore);
        matchEndState.setTourResult(opponent, firstWin);
        matchEndState.setEnabled(true);
        recordMatchHistory("World Tour", playerScore, opponentScore, playerWon, 0, null);
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
                tournamentService.reportTournamentMatchResult(context.code(), context.roundIndex(),
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
        rankedMatchCoordinator.endRankedHostMatch(playerWon, playerScore, opponentScore, netHost, profile.getPlayerId());
    }

    /** Same as {@link #endRankedHostMatch}, but for a mid-match joiner disconnect/timeout: the
     *  host reports a forfeit win for itself (only if this was actually a ranked match - i.e. the
     *  joiner connected with a player id at all) and shows a real "opponent disconnected" notice
     *  rather than a plain win screen. */
    public void endRankedHostMatchByForfeit(int playerScore, int opponentScore, NetHost netHost, boolean wasRanked) {
        rankedMatchCoordinator.endRankedHostMatchByForfeit(playerScore, opponentScore, netHost, wasRanked, profile.getPlayerId());
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
        rankedMatchCoordinator.endRankedJoinerMatch(playerWon, playerScore, opponentScore, netClient, profile.getPlayerId());
    }

    private int endMatchCommon(boolean playerWon, int playerScore, int opponentScore) {
        beginMatchEnd(playerWon);

        int reward = 0;
        if (playerWon) {
            reward = randomMatchReward();
            profile.addCurrency(reward);
            saveProfile();
        }
        return reward;
    }

    /** Stops the match and plays the win/defeat sting - shared by every match-end path. */
    private void beginMatchEnd(boolean playerWon) {
        gameplayState.setEnabled(false);
        audioManager.stopMusic();
        audioManager.playSfx(playerWon ? "match_win.ogg" : "match_defeat.ogg");
    }

    private static int randomMatchReward() {
        return ThreadLocalRandom.current().nextInt(GameConstants.MATCH_REWARD_MIN, GameConstants.MATCH_REWARD_MAX + 1);
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

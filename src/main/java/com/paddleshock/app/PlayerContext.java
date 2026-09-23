package com.paddleshock.app;

import java.io.IOException;
import java.net.SocketException;

import com.paddleshock.audio.AudioManager;
import com.paddleshock.data.PlayerProfile;
import com.paddleshock.net.InviteService;
import com.paddleshock.net.NetClient;
import com.paddleshock.net.NetHost;
import com.paddleshock.net.RankService;
import com.paddleshock.net.TournamentService;
import com.paddleshock.settings.GameSettings;
import com.paddleshock.steam.SteamManager;

/**
 * Player/profile/settings/match-lifecycle surface a UI state needs from the app shell -
 * everything {@link Navigator} isn't. Implemented by {@link PaddleShockApp}; see that interface's
 * class docs for why this is split out as its own contract instead of a UI state depending on the
 * concrete app class directly.
 */
public interface PlayerContext {

    SteamManager getSteamManager();

    PlayerProfile getProfile();

    void saveProfile();

    /** Re-checks profile-state achievements (e.g. after a purchase) and grants any newly earned. */
    void checkAchievements();

    GameSettings getGameSettings();

    AudioManager getAudioManager();

    void saveGameSettings();

    RankService getRankService();

    InviteService getInviteService();

    TournamentService getTournamentService();

    void startMatchVsAI();

    NetHost startHostMatch(int port, boolean ranked) throws SocketException;

    NetClient joinMatch(String hostAddress, int port) throws IOException;

    NetClient joinMatch(String hostAddress, int port, boolean spectator) throws IOException;

    NetClient joinMatchByLobbyCode(String code) throws IOException;

    NetClient joinMatchByLobbyCode(String code, boolean spectator) throws IOException;

    void enterHostedMatch(NetHost netHost);

    void enterJoinedMatch(NetClient netClient);

    GameplayAppState getGameplayState();

    void resumeMultiplayerRematch();

    void endMatch(boolean playerWon, int playerScore, int opponentScore, String opponentPlayerId);

    void endRankedHostMatch(boolean playerWon, int playerScore, int opponentScore, NetHost netHost);

    void endRankedHostMatchByForfeit(int playerScore, int opponentScore, NetHost netHost, boolean wasRanked);

    void handleJoinerConnectionLost(int playerScore, int opponentScore);

    void endSpectatedMatch();

    void endRankedJoinerMatch(boolean playerWon, int playerScore, int opponentScore, NetClient netClient);

    void applyDisplaySettings();
}

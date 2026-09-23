package com.paddleshock.app;

/**
 * Screen-transition surface a UI state needs from the app shell - which screen to show next, and
 * the handful of navigation-adjacent bits of state (the pending invite/tournament context) that
 * travel with a transition. Implemented by {@link PaddleShockApp}; a UI state depends on this
 * interface (via {@code (Navigator) getApplication()}) instead of the concrete app class, so it
 * can be tested or reasoned about against a narrow, mockable contract rather than the entire god
 * object. See {@link PlayerContext} for the other half (profile/settings/match lifecycle) of what
 * {@link PaddleShockApp} exposes to UI states.
 */
public interface Navigator {

    void showMainMenu();

    void showHowToPlay(Runnable backAction);

    void showCredits(Runnable backAction);

    void showWorldTour();

    void startTourMatch(com.paddleshock.tour.TourOpponent opponent);

    void showMultiplayer();

    void showTournament();

    void setActiveTournamentContext(PaddleShockApp.TournamentMatchContext context);

    void showLeaderboard();

    void showProfile();

    void showFriends();

    void acceptInvite(String lobbyCode);

    void showLoadout();

    void showPause();

    void resumeMatch();

    void quitToMainMenu();

    void showStore();

    void showStoreModal(Runnable onClose);

    void showOptions(Runnable backAction);
}

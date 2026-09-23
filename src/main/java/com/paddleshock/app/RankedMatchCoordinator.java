package com.paddleshock.app;

import java.io.IOException;
import java.util.UUID;

import com.paddleshock.diagnostics.NetLog;
import com.paddleshock.net.NetClient;
import com.paddleshock.net.NetHost;
import com.paddleshock.net.NetProtocol;
import com.paddleshock.net.RankClient;
import com.paddleshock.net.RankService;
import com.paddleshock.net.RankState;
import com.paddleshock.ui.MatchEndState;

/**
 * Owns the ranked-ladder side of match-end: reporting a result (or forfeit) to {@link RankService}
 * for the HOST side, relaying/polling for the authoritative delta on the JOINER side, and the
 * pre-match rank snapshot the joiner needs to compute that delta. Pulled out of
 * {@link PaddleShockApp} so this orchestration - which depends only on {@link RankService} and
 * {@link MatchEndState}, never on rendering/input/scene state - has its own small, focused,
 * independently testable home instead of living inside the app god-object. {@link PaddleShockApp}
 * still exposes the same {@code endRanked*} methods {@link GameplayAppState} calls (that's a
 * separate step - see the coupling notes on {@link PaddleShockApp}); they just delegate here now.
 *
 * <p>{@code endMatchCommon}/{@code recordMatchHistory} stay on {@link PaddleShockApp} (they're
 * generic match-lifecycle bookkeeping shared by every match-end path, not ranked-specific), and are
 * threaded through as the two small callback interfaces below instead of duplicating them here.
 */
final class RankedMatchCoordinator {

    /** See {@code PaddleShockApp.endMatchCommon} - disables the gameplay state, plays win/lose SFX,
     *  and awards currency on a win, returning the reward amount. */
    interface MatchEndCommon {
        int apply(boolean playerWon, int playerScore, int opponentScore);
    }

    /** See {@code PaddleShockApp.recordMatchHistory} - appends the local match-history entry and
     *  updates the rival tracker/persists the profile. */
    interface MatchHistoryRecorder {
        void record(String mode, int playerScore, int opponentScore, boolean won, int lpChange, String opponentPlayerId);
    }

    private final RankService rankService;
    private final MatchEndState matchEndState;
    private final MatchEndCommon matchEndCommon;

    /** Notified (on whatever thread the rank call completed on) with every non-null rank result,
     *  e.g. for rank achievements. Optional. */
    private volatile java.util.function.Consumer<RankState> rankListener;
    private final MatchHistoryRecorder matchHistoryRecorder;

    /** This player's rank as of just before the current joined match started - see
     *  {@link #beginRankPrefetch}/{@link #endRankedJoinerMatch}. */
    private volatile RankState preMatchRank;

    RankedMatchCoordinator(RankService rankService, MatchEndState matchEndState,
            MatchEndCommon matchEndCommon, MatchHistoryRecorder matchHistoryRecorder) {
        this.rankService = rankService;
        this.matchEndState = matchEndState;
        this.matchEndCommon = matchEndCommon;
        this.matchHistoryRecorder = matchHistoryRecorder;
    }

    /** Clears any stale snapshot from a previous match - call this on every {@code enterJoinedMatch},
     *  ranked or not, before conditionally calling {@link #beginRankPrefetch}. */
    void clearPreMatchRank() {
        preMatchRank = null;
    }

    /** Snapshot this player's rank now, before a ranked match starts: the joiner has no way to
     *  learn its own LP delta from the host's report the way {@link #endRankedHostMatch} does
     *  directly (that response is never relayed back over the game's own protocol) -
     *  {@link #endRankedJoinerMatch} computes the delta itself by diffing against this baseline
     *  instead. Runs on its own background thread; never call from the render thread's caller and
     *  then block on the result. */
    void beginRankPrefetch(String playerId) {
        Thread thread = new Thread(() -> {
            try {
                preMatchRank = rankService.getRank(playerId);
            } catch (IOException e) {
                preMatchRank = null; // endRankedJoinerMatch falls back to "no delta shown"
                NetLog.log("rank-prefetch failed for player " + playerId, e);
            }
        }, "rank-prefetch");
        thread.setDaemon(true);
        thread.start();
    }

    /** Same as {@code PaddleShockApp.endMatch}, but for the HOST side of a ranked multiplayer
     *  match: also reports the result for both players (host is the sole reporter - it already
     *  owns the authoritative simulation, so this isn't a new trust boundary) and shows the LP/rank
     *  change once that call returns. If the joiner connected without a player id (an older
     *  client), the report is skipped, LAN play still works. {@code netHost.getLobbyCode()} is
     *  passed through so the backend can verify the report is backed by a real lobby session for
     *  internet matches (it's {@code null}, and therefore unverified, for a direct IP:port LAN
     *  match - see {@code aws/README.md}); once the report succeeds, the joiner's own authoritative
     *  result is relayed back over the still-open connection so it can show the real number instead
     *  of guessing via {@code withDeltaFrom} - see {@link #endRankedJoinerMatch}. */
    void endRankedHostMatch(boolean playerWon, int playerScore, int opponentScore, NetHost netHost, String hostPlayerId) {
        int reward = matchEndCommon.apply(playerWon, playerScore, opponentScore);
        matchEndState.setRankedResult(playerWon, reward, playerScore, opponentScore);
        matchEndState.setEnabled(true);

        String joinerPlayerId = netHost == null ? "" : netHost.getJoinerPlayerId();
        if (joinerPlayerId == null || joinerPlayerId.isEmpty()) {
            reportRank(null);
            matchHistoryRecorder.record("Ranked", playerScore, opponentScore, playerWon, 0, null);
            return;
        }
        String lobbyCode = netHost.getLobbyCode();
        Thread thread = new Thread(() -> {
            RankState result = null;
            try {
                String matchId = UUID.randomUUID().toString();
                RankClient.MatchReportResult report =
                        rankService.reportMatchResult(matchId, hostPlayerId, joinerPlayerId, playerWon, lobbyCode);
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
            reportRank(result);
            matchHistoryRecorder.record("Ranked", playerScore, opponentScore, playerWon,
                    result == null ? 0 : result.getLpChange(), joinerPlayerId);
        }, "rank-report");
        thread.setDaemon(true);
        thread.start();
    }

    /** Same as {@link #endRankedHostMatch}, but for a mid-match joiner disconnect/timeout: the host
     *  reports a forfeit win for itself (only if this was actually a ranked match - i.e. the joiner
     *  connected with a player id at all) and shows a real "opponent disconnected" notice rather
     *  than a plain win screen. */
    void endRankedHostMatchByForfeit(int playerScore, int opponentScore, NetHost netHost, boolean wasRanked, String hostPlayerId) {
        int reward = matchEndCommon.apply(true, playerScore, opponentScore);
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
            matchHistoryRecorder.record("LAN Host", playerScore, opponentScore, true, 0, joinerPlayerId);
            return;
        }
        String lobbyCode = netHost.getLobbyCode();
        Thread thread = new Thread(() -> {
            RankState result = null;
            try {
                String matchId = UUID.randomUUID().toString();
                // The joiner is gone - no relay is possible or needed; it never shows a ranked
                // result at all for a timeout (see PaddleShockApp.handleJoinerConnectionLost).
                result = rankService.reportMatchResult(matchId, hostPlayerId, joinerPlayerId, true, lobbyCode).getHost();
            } catch (IOException e) {
                // offline, or the rank service is unreachable - the forfeit itself still stands.
                NetLog.log("ranked forfeit report failed (host)", e);
            }
            reportRank(result);
            matchHistoryRecorder.record("Ranked", playerScore, opponentScore, true,
                    result == null ? 0 : result.getLpChange(), joinerPlayerId);
        }, "rank-report-forfeit");
        thread.setDaemon(true);
        thread.start();
    }

    /** Same as {@code PaddleShockApp.endMatch}, but for the JOINER side of a ranked multiplayer
     *  match: the host already reported the result for both players, so this just re-fetches this
     *  player's own updated rank for display. */
    void endRankedJoinerMatch(boolean playerWon, int playerScore, int opponentScore, NetClient netClient, String playerId) {
        int reward = matchEndCommon.apply(playerWon, playerScore, opponentScore);
        matchEndState.setRankedResult(playerWon, reward, playerScore, opponentScore);
        matchEndState.setEnabled(true);

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
                reportRank(relayed);
                matchHistoryRecorder.record("Ranked", playerScore, opponentScore, playerWon, relayed.getLpChange(), hostOpponentPlayerId);
                return;
            }

            RankState fetched = null;
            try {
                // enterJoinedMatch's own prefetch thread (see beginRankPrefetch) may genuinely not
                // have finished yet - an unrealistically fast match (or just an unlucky HTTP round
                // trip) can outrun it. Wait briefly for it rather than treating "not yet set" as
                // "unavailable" and silently showing a 0 delta for a real rank change.
                RankState baseline = waitForPreMatchRank();
                int baselineGames = baseline == null ? -1 : baseline.getWins() + baseline.getLosses();

                // The host's own reportMatchResult call runs independently on a different machine,
                // triggered by the same match-over event this side just reacted to - it may not
                // have finished writing yet either. Poll briefly for this player's game count to
                // actually move rather than risk showing the stale pre-match state.
                for (int attempt = 0; attempt < 6; attempt++) {
                    fetched = rankService.getRank(playerId);
                    if (baselineGames < 0 || fetched.getWins() + fetched.getLosses() != baselineGames) {
                        break;
                    }
                    fetched = null;
                    Thread.sleep(400);
                }
                if (fetched == null) {
                    fetched = rankService.getRank(playerId); // gave up waiting - show whatever's there
                }
                RankState delta = fetched.withDeltaFrom(baseline);
                reportRank(delta);
                matchHistoryRecorder.record("Ranked", playerScore, opponentScore, playerWon, delta.getLpChange(), hostOpponentPlayerId);
            } catch (IOException e) {
                reportRank(null); // offline, or the rank service is unreachable
                matchHistoryRecorder.record("Ranked", playerScore, opponentScore, playerWon, 0, hostOpponentPlayerId);
                NetLog.log("ranked rank-fetch failed (joiner)", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                reportRank(null);
                matchHistoryRecorder.record("Ranked", playerScore, opponentScore, playerWon, 0, hostOpponentPlayerId);
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
     *  {@link #beginRankPrefetch}'s rank prefetch to land, in case the match ended before it did. */
    private RankState waitForPreMatchRank() throws InterruptedException {
        for (int i = 0; i < 10 && preMatchRank == null; i++) {
            Thread.sleep(200);
        }
        return preMatchRank;
    }

    public void setRankListener(java.util.function.Consumer<RankState> listener) {
        this.rankListener = listener;
    }

    /** Hands a rank result to the match-end screen, and to the rank listener if it's real. */
    private void reportRank(RankState state) {
        matchEndState.reportRankResult(state);
        java.util.function.Consumer<RankState> listener = rankListener;
        if (state != null && listener != null) {
            listener.accept(state);
        }
    }
}

package com.paddleshock.data;

/** One local head-to-head record against another player, keyed by their ranked-ladder player id
 *  (see {@link PlayerProfile#recordRivalResult}) - plain Gson-serializable data, no behavior
 *  beyond a couple of display-formatting helpers, same style as {@link MatchHistoryEntry}. */
public final class RivalRecord {

    private String opponentPlayerId;
    /** Best display-name hint ever captured for this opponent (currently just a shortened id
     *  fallback, since there's no real display-name system yet - see
     *  {@code RankClient.LeaderboardEntry#shortId()}). May be updated on later matches if a
     *  better hint becomes available. */
    private String opponentDisplayNameHint;
    private int wins;
    private int losses;
    private long lastPlayedAt;

    public RivalRecord(String opponentPlayerId, String opponentDisplayNameHint) {
        this.opponentPlayerId = opponentPlayerId;
        this.opponentDisplayNameHint = opponentDisplayNameHint;
    }

    public String getOpponentPlayerId() {
        return opponentPlayerId;
    }

    public String getOpponentDisplayNameHint() {
        return opponentDisplayNameHint;
    }

    void setOpponentDisplayNameHint(String hint) {
        this.opponentDisplayNameHint = hint;
    }

    public int getWins() {
        return wins;
    }

    public int getLosses() {
        return losses;
    }

    public long getLastPlayedAt() {
        return lastPlayedAt;
    }

    int getGamesPlayed() {
        return wins + losses;
    }

    void recordResult(boolean won, long timestamp) {
        if (won) {
            wins++;
        } else {
            losses++;
        }
        lastPlayedAt = timestamp;
    }

    /** Opponent's display-name hint if one was ever captured, otherwise a shortened id fallback -
     *  same shortening convention as {@code RankClient.LeaderboardEntry#shortId()}. */
    public String displayName() {
        if (opponentDisplayNameHint != null && !opponentDisplayNameHint.isBlank()) {
            return opponentDisplayNameHint;
        }
        String id = opponentPlayerId == null ? "" : opponentPlayerId.replace("-", "");
        return "Player-" + (id.length() >= 8 ? id.substring(0, 8) : id).toUpperCase();
    }

    /** "3W-1L" style summary. */
    public String formatRecord() {
        return wins + "W-" + losses + "L";
    }
}

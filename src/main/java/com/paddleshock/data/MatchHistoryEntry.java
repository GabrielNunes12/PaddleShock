package com.paddleshock.data;

/** One row of a player's local match history (see {@link PlayerProfile#addMatchHistoryEntry}) -
 *  plain Gson-serializable data, no behavior beyond a couple of display-formatting helpers. */
public final class MatchHistoryEntry {

    private long timestamp;
    private String mode;
    private int playerScore;
    private int opponentScore;
    private boolean won;
    /** LP gained/lost by this match; 0 for a non-ranked match, or a ranked match whose LP change
     *  was never actually resolved (e.g. the rank service was unreachable at match-end). */
    private int lpChange;

    public MatchHistoryEntry(long timestamp, String mode, int playerScore, int opponentScore, boolean won, int lpChange) {
        this.timestamp = timestamp;
        this.mode = mode;
        this.playerScore = playerScore;
        this.opponentScore = opponentScore;
        this.won = won;
        this.lpChange = lpChange;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getMode() {
        return mode;
    }

    public int getPlayerScore() {
        return playerScore;
    }

    public int getOpponentScore() {
        return opponentScore;
    }

    public boolean isWon() {
        return won;
    }

    public int getLpChange() {
        return lpChange;
    }

    /** "WIN 10-4" / "LOSS 6-10 (-14 LP)" style one-line summary for the profile screen. */
    public String formatResult() {
        String result = (won ? "WIN " : "LOSS ") + playerScore + "-" + opponentScore;
        if (lpChange != 0) {
            result += " (" + (lpChange > 0 ? "+" : "") + lpChange + " LP)";
        }
        return result;
    }
}

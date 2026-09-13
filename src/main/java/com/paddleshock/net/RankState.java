package com.paddleshock.net;

import com.paddleshock.rank.RankTier;

/**
 * Wire shape returned by both the {@code getRank} and {@code reportMatchResult} lobby-Lambda
 * actions (see {@code aws/README.md} and {@link RankClient}). The trailing fields
 * ({@code lpChange}, {@code promoted}, {@code demoted}, {@code promoSeriesResult}) are only
 * populated on a match-result response - {@code null}/0 on a plain {@code getRank}.
 */
public final class RankState {

    /** Present only while a 100 LP promotion series is in progress. */
    public static final class Promo {
        private int wins;
        private int losses;

        public int getWins() {
            return wins;
        }

        public int getLosses() {
            return losses;
        }
    }

    private String tier;
    private int division;
    private int lp;
    private int wins;
    private int losses;
    private Promo promo;

    private Integer lpChange;
    private Boolean promoted;
    private Boolean demoted;
    private String promoSeriesResult;

    public RankTier getTier() {
        return RankTier.valueOf(tier);
    }

    public int getDivision() {
        return division;
    }

    public int getLp() {
        return lp;
    }

    public int getWins() {
        return wins;
    }

    public int getLosses() {
        return losses;
    }

    public boolean isInPromoSeries() {
        return promo != null;
    }

    public Promo getPromo() {
        return promo;
    }

    /** LP gained (positive) or lost (negative) by the match this state was returned for; 0 if
     *  this came from a plain {@link RankClient#getRank}, not a match report. */
    public int getLpChange() {
        return lpChange == null ? 0 : lpChange;
    }

    public boolean wasPromoted() {
        return Boolean.TRUE.equals(promoted);
    }

    public boolean wasDemoted() {
        return Boolean.TRUE.equals(demoted);
    }

    /** {@code "started"}, {@code "ongoing"}, {@code "won"}, {@code "lost"}, or {@code null} if no
     *  promotion series activity happened on the match this state was returned for. */
    public String getPromoSeriesResult() {
        return promoSeriesResult;
    }

    /** Human-readable "TIER DIVISION" label, e.g. "Silver II", for display. */
    public String formatLabel() {
        return getTier().getDisplayName() + " " + RankTier.divisionToRoman(division);
    }

    /** Reconstructs a display-ready {@link RankState} from a relayed {@link NetProtocol#TYPE_RANK_RESULT}
     *  packet - the joiner's own authoritative post-match state as the host's {@code reportMatchResult}
     *  call actually returned it, rather than a locally-guessed diff (see {@link #withDeltaFrom}). */
    public static RankState fromRelay(String tier, int division, int lp, int wins, int losses,
            int lpChange, boolean promoted, boolean demoted, String promoSeriesResult) {
        RankState state = new RankState();
        state.tier = tier;
        state.division = division;
        state.lp = lp;
        state.wins = wins;
        state.losses = losses;
        state.lpChange = lpChange;
        state.promoted = promoted;
        state.demoted = demoted;
        state.promoSeriesResult = promoSeriesResult == null || promoSeriesResult.isEmpty() ? null : promoSeriesResult;
        return state;
    }

    /**
     * Computes a display-ready copy of this (post-match) state with {@code lpChange}/
     * {@code promoted}/{@code demoted} filled in by diffing against {@code baseline} (the same
     * player's pre-match state). Used by the joiner side of a ranked match, which - unlike the
     * host - never receives an authoritative delta over the wire (the host's
     * {@code reportMatchResult} response isn't relayed back through the game's own protocol; see
     * {@code PaddleShockApp#endRankedJoinerMatch}). {@code promoSeriesResult} is left {@code
     * null} since only the server actually knows the promo-series step-by-step outcome.
     */
    public RankState withDeltaFrom(RankState baseline) {
        RankState copy = new RankState();
        copy.tier = this.tier;
        copy.division = this.division;
        copy.lp = this.lp;
        copy.wins = this.wins;
        copy.losses = this.losses;
        copy.promo = this.promo;
        if (baseline == null) {
            return copy;
        }
        if (this.tier.equals(baseline.tier) && this.division == baseline.division) {
            copy.lpChange = this.lp - baseline.lp;
        } else {
            boolean promoted = RankTier.valueOf(this.tier).ordinal() > RankTier.valueOf(baseline.tier).ordinal()
                    || (this.tier.equals(baseline.tier) && this.division < baseline.division);
            copy.promoted = promoted;
            copy.demoted = !promoted;
        }
        return copy;
    }
}

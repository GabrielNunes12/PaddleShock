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

    // Peak-rank tracking (see aws/README.md "Seasonal peak-rank reward"): the best tier/division/lp
    // reached so far THIS season. Old server records predate these fields; Gson leaves them null/0
    // in that case, which getters below treat as "no peak recorded" rather than crashing.
    private String peakTier;
    private int peakDivision;
    private int peakLp;

    // A one-time snapshot of the PREVIOUS season's peak, captured server-side once right before
    // each season reset. lastSeasonNumber is null until this record has lived through at least one
    // rollover since peak-tracking existed.
    private String lastSeasonPeakTier;
    private int lastSeasonPeakDivision;
    private int lastSeasonPeakLp;
    private Integer lastSeasonNumber;

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

    /** The best tier reached so far this season, or {@code null} if the server never recorded a
     *  peak for this record (predates the peak-tracking feature and hasn't played a match since). */
    public RankTier getPeakTier() {
        return peakTier == null ? null : RankTier.valueOf(peakTier);
    }

    public int getPeakDivision() {
        return peakDivision;
    }

    public int getPeakLp() {
        return peakLp;
    }

    /** The previous season's peak tier, or {@code null} if this record hasn't lived through a
     *  season rollover since peak-tracking existed (see {@link #getLastSeasonNumber()}). */
    public RankTier getLastSeasonPeakTier() {
        return lastSeasonPeakTier == null ? null : RankTier.valueOf(lastSeasonPeakTier);
    }

    public int getLastSeasonPeakDivision() {
        return lastSeasonPeakDivision;
    }

    public int getLastSeasonPeakLp() {
        return lastSeasonPeakLp;
    }

    /** The season number the {@code lastSeasonPeak*} fields describe, or {@code null} if none has
     *  been recorded yet. */
    public Integer getLastSeasonNumber() {
        return lastSeasonNumber;
    }

    /** "TIER DIVISION" label for the previous season's peak, e.g. "Gold II", or {@code null} if
     *  there isn't one recorded yet - see {@link #getLastSeasonNumber()}. */
    public String formatLastSeasonPeakLabel() {
        RankTier tier = getLastSeasonPeakTier();
        return tier == null ? null : tier.getDisplayName() + " " + RankTier.divisionToRoman(lastSeasonPeakDivision);
    }

    /**
     * "TIER DIVISION" label for the tier/division a single win away during an active promotion
     * series - the next-better division within this tier, or division IV of the next tier up
     * when already at division I. Used for pre-match "win to advance to X" framing; meaningful
     * only when {@link #isInPromoSeries()}. {@code null} if already at the ceiling (Diamond I) -
     * there's nowhere higher to name.
     */
    public String nextPromoLabel() {
        RankTier tier = getTier();
        if (division > 1) {
            return tier.getDisplayName() + " " + RankTier.divisionToRoman(division - 1);
        }
        RankTier[] tiers = RankTier.values();
        int nextOrdinal = tier.ordinal() + 1;
        if (nextOrdinal >= tiers.length) {
            return null;
        }
        return tiers[nextOrdinal].getDisplayName() + " " + RankTier.divisionToRoman(4);
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

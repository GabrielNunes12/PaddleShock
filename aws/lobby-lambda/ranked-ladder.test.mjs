import { test, describe } from "node:test";
import assert from "node:assert/strict";

import {
    TIERS,
    MAX_LP_WIN,
    MAX_LP_LOSS,
    PROMO_LOSS_CUSHION_LP,
    defaultRank,
    isMaxRank,
    lpForWinStreak,
    lpForLossStreak,
    promoteOneStep,
    demoteOneStep,
    compareRankPosition,
    isBetterRankPosition,
    applySeasonResetIfNeeded,
    applyMatchResult,
} from "./index.mjs";

describe("lpForWinStreak", () => {
    test("base amount on a fresh streak (N=1)", () => {
        assert.equal(lpForWinStreak(1), 16);
    });

    test("scales up with consecutive wins", () => {
        assert.equal(lpForWinStreak(2), 20);
        assert.equal(lpForWinStreak(3), 24);
    });

    test("caps at MAX_LP_WIN (38)", () => {
        assert.equal(MAX_LP_WIN, 38);
        assert.equal(lpForWinStreak(6), 36);
        assert.equal(lpForWinStreak(7), 38); // uncapped would be 40
        assert.equal(lpForWinStreak(100), 38);
    });
});

describe("lpForLossStreak", () => {
    test("base amount on a fresh streak (N=1)", () => {
        assert.equal(lpForLossStreak(1), 12);
    });

    test("scales up with consecutive losses", () => {
        assert.equal(lpForLossStreak(2), 15);
        assert.equal(lpForLossStreak(3), 18);
    });

    test("caps at MAX_LP_LOSS (28)", () => {
        assert.equal(MAX_LP_LOSS, 28);
        assert.equal(lpForLossStreak(6), 27);
        assert.equal(lpForLossStreak(7), 28); // uncapped would be 30
        assert.equal(lpForLossStreak(100), 28);
    });
});

describe("promoteOneStep", () => {
    test("steps up one division within a tier", () => {
        const rank = { tier: "SILVER", division: 3 };
        promoteOneStep(rank);
        assert.deepEqual(rank, { tier: "SILVER", division: 2 });
    });

    test("crosses into the next tier at division I -> next tier IV", () => {
        const rank = { tier: "COPPER", division: 1 };
        promoteOneStep(rank);
        assert.deepEqual(rank, { tier: "BRONZE", division: 4 });
    });

    test("isMaxRank identifies Diamond I as the ceiling", () => {
        assert.equal(isMaxRank({ tier: "DIAMOND", division: 1 }), true);
        assert.equal(isMaxRank({ tier: "DIAMOND", division: 2 }), false);
        assert.equal(isMaxRank({ tier: "GOLD", division: 1 }), false);
    });
});

describe("demoteOneStep", () => {
    test("steps down one division within a tier", () => {
        const rank = { tier: "GOLD", division: 2 };
        demoteOneStep(rank);
        assert.deepEqual(rank, { tier: "GOLD", division: 3 });
    });

    test("crosses down into the previous tier at division IV -> tier-below division I", () => {
        const rank = { tier: "BRONZE", division: 4 };
        demoteOneStep(rank);
        assert.deepEqual(rank, { tier: "COPPER", division: 1 });
    });

    test("is floored at Copper IV and does not go negative", () => {
        const rank = { tier: "COPPER", division: 4 };
        demoteOneStep(rank);
        assert.deepEqual(rank, { tier: "COPPER", division: 4 });
    });

    test("Copper I still demotes down to Copper II, not to Bronze", () => {
        const rank = { tier: "COPPER", division: 1 };
        demoteOneStep(rank);
        assert.deepEqual(rank, { tier: "COPPER", division: 2 });
    });
});

describe("applySeasonResetIfNeeded", () => {
    test("no-ops when already on the current season", () => {
        const rank = { tier: "GOLD", division: 2, lp: 80, promo: { wins: 1, losses: 0 }, streak: 3, season: 5 };
        const before = { ...rank };
        applySeasonResetIfNeeded(rank, 5);
        assert.deepEqual(rank, before);
    });

    test("compresses tiers above Silver down to Silver II / 50 LP", () => {
        const rank = { tier: "PLATINUM", division: 1, lp: 95, promo: null, streak: 4, season: 5 };
        applySeasonResetIfNeeded(rank, 6);
        assert.equal(rank.tier, "SILVER");
        assert.equal(rank.division, 2);
        assert.equal(rank.lp, 50);
        assert.equal(rank.season, 6);
    });

    test("caps (never raises) LP at 50 for Silver-or-below tiers", () => {
        const low = { tier: "BRONZE", division: 3, lp: 20, promo: null, streak: 0, season: 5 };
        applySeasonResetIfNeeded(low, 6);
        assert.equal(low.tier, "BRONZE"); // tier/division untouched at or below Silver
        assert.equal(low.division, 3);
        assert.equal(low.lp, 20); // already below 50, unchanged

        const high = { tier: "SILVER", division: 1, lp: 90, promo: null, streak: 0, season: 5 };
        applySeasonResetIfNeeded(high, 6);
        assert.equal(high.lp, 50); // 90 gets compressed down to the 50 cap
    });

    test("always clears streak on a season boundary", () => {
        const rank = { tier: "GOLD", division: 2, lp: 40, promo: null, streak: -5, season: 5 };
        applySeasonResetIfNeeded(rank, 6);
        assert.equal(rank.streak, 0);
    });

    // Explicit edge case called out in the test plan: a season reset landing mid-promo-series.
    test("clears an in-progress promo series on a season reset (tier above Silver)", () => {
        const rank = { tier: "PLATINUM", division: 3, lp: 100, promo: { wins: 1, losses: 0 }, streak: 6, season: 5 };
        applySeasonResetIfNeeded(rank, 6);
        assert.equal(rank.promo, null);
        assert.equal(rank.tier, "SILVER");
        assert.equal(rank.division, 2);
        assert.equal(rank.lp, 50);
    });

    test("clears an in-progress promo series on a season reset (tier at/below Silver)", () => {
        const rank = { tier: "BRONZE", division: 1, lp: 100, promo: { wins: 0, losses: 1 }, streak: -2, season: 5 };
        applySeasonResetIfNeeded(rank, 6);
        assert.equal(rank.promo, null);
        assert.equal(rank.tier, "BRONZE");
        assert.equal(rank.division, 1);
        assert.equal(rank.lp, 50); // compressed from 100 down to the 50 cap
    });
});

describe("applyMatchResult", () => {
    test("a first win from a fresh rank grants the base LP amount", () => {
        const rank = defaultRank(1);
        const summary = applyMatchResult(rank, true);
        assert.equal(summary.lpChange, 16);
        assert.equal(rank.lp, 16);
        assert.equal(rank.streak, 1);
        assert.equal(rank.wins, 1);
        assert.equal(summary.promoted, false);
        assert.equal(summary.demoted, false);
        assert.equal(summary.promoSeriesResult, null);
    });

    test("consecutive wins scale LP gain up to the cap", () => {
        // Pre-seed a streak of 6 (rather than looping from 0) so LP never crosses 100 and
        // triggers a promo series, which would otherwise stop these plain streak wins.
        const rank = { ...defaultRank(1), streak: 6 };
        const summary = applyMatchResult(rank, true);
        assert.equal(rank.streak, 7);
        assert.equal(summary.lpChange, 38); // lpForWinStreak(7) is capped at 38
    });

    test("a loss resets a win streak to a fresh losing streak of 1", () => {
        const rank = defaultRank(1);
        applyMatchResult(rank, true);
        applyMatchResult(rank, true);
        assert.equal(rank.streak, 2);
        const summary = applyMatchResult(rank, false);
        assert.equal(rank.streak, -1);
        assert.equal(summary.lpChange, -12);
    });

    test("consecutive losses scale LP loss up to the cap", () => {
        const rank = { ...defaultRank(1), lp: 100 };
        for (let i = 0; i < 10; i++) {
            applyMatchResult(rank, false);
        }
        assert.equal(rank.streak, -10);
    });

    test("reaching 100 LP starts a promo series instead of an instant promotion", () => {
        const rank = { ...defaultRank(1), tier: "GOLD", division: 2, lp: 90, streak: 5 };
        const summary = applyMatchResult(rank, true);
        assert.equal(rank.lp, 100);
        assert.equal(rank.tier, "GOLD"); // not promoted yet
        assert.equal(rank.division, 2);
        assert.deepEqual(rank.promo, { wins: 0, losses: 0 });
        assert.equal(summary.promoted, false);
        assert.equal(summary.promoSeriesResult, "started");
    });

    test("Diamond I does not start a promo series - it's the ceiling", () => {
        const rank = { ...defaultRank(1), tier: "DIAMOND", division: 1, lp: 90, streak: 5 };
        const summary = applyMatchResult(rank, true);
        assert.equal(rank.lp, 100);
        assert.equal(rank.promo, null);
        assert.equal(summary.promoSeriesResult, null);
        assert.equal(summary.promoted, false);
    });

    test("winning a promo series (2 wins) promotes and resets LP to 0", () => {
        const rank = { ...defaultRank(1), tier: "GOLD", division: 2, lp: 100, promo: { wins: 1, losses: 0 } };
        const summary = applyMatchResult(rank, true);
        assert.equal(summary.promoted, true);
        assert.equal(summary.promoSeriesResult, "won");
        assert.equal(rank.tier, "GOLD");
        assert.equal(rank.division, 1);
        assert.equal(rank.lp, 0);
        assert.equal(rank.promo, null);
    });

    test("winning a promo series can cross a tier boundary", () => {
        const rank = { ...defaultRank(1), tier: "GOLD", division: 1, lp: 100, promo: { wins: 1, losses: 0 } };
        applyMatchResult(rank, true);
        assert.equal(rank.tier, "PLATINUM");
        assert.equal(rank.division, 4);
    });

    test("losing a promo series (2 losses) drops LP to the cushion instead of demoting", () => {
        const rank = { ...defaultRank(1), tier: "GOLD", division: 2, lp: 100, promo: { wins: 0, losses: 1 } };
        const summary = applyMatchResult(rank, false);
        assert.equal(summary.promoted, false);
        assert.equal(summary.demoted, false);
        assert.equal(summary.promoSeriesResult, "lost");
        assert.equal(rank.tier, "GOLD");
        assert.equal(rank.division, 2); // stays in the division being contested
        assert.equal(rank.lp, PROMO_LOSS_CUSHION_LP);
        assert.equal(rank.promo, null);
    });

    test("a promo series in progress reports 'ongoing' and never changes LP", () => {
        const rank = { ...defaultRank(1), tier: "GOLD", division: 2, lp: 100, promo: { wins: 0, losses: 0 } };
        const summary = applyMatchResult(rank, true);
        assert.equal(summary.promoSeriesResult, "ongoing");
        assert.equal(summary.lpChange, 0);
        assert.equal(rank.lp, 100);
        assert.deepEqual(rank.promo, { wins: 1, losses: 0 });
    });

    test("dropping below 0 LP demotes a division", () => {
        const rank = { ...defaultRank(1), tier: "GOLD", division: 3, lp: 5, streak: 0 };
        const summary = applyMatchResult(rank, false); // loses 12 LP (fresh streak) -> -7
        assert.equal(summary.demoted, true);
        assert.equal(rank.tier, "GOLD");
        assert.equal(rank.division, 4);
        assert.equal(rank.lp, 0);
    });

    test("demotion is floored at Copper IV - no further demotion, and demoted:false", () => {
        const rank = { ...defaultRank(1), tier: "COPPER", division: 4, lp: 5, streak: 0 };
        const summary = applyMatchResult(rank, false);
        assert.equal(summary.demoted, false);
        assert.equal(rank.tier, "COPPER");
        assert.equal(rank.division, 4);
        assert.equal(rank.lp, 0);
    });

    test("TIERS ladder is ordered Copper through Diamond", () => {
        assert.deepEqual(TIERS, ["COPPER", "BRONZE", "SILVER", "GOLD", "PLATINUM", "DIAMOND"]);
    });
});

describe("compareRankPosition / isBetterRankPosition", () => {
    test("higher tier is better regardless of division/lp", () => {
        const gold = { tier: "GOLD", division: 4, lp: 0 };
        const silver = { tier: "SILVER", division: 1, lp: 99 };
        assert.equal(isBetterRankPosition(gold, silver), true);
        assert.equal(isBetterRankPosition(silver, gold), false);
    });

    test("within a tier, lower division number is better", () => {
        const divOne = { tier: "GOLD", division: 1, lp: 0 };
        const divFour = { tier: "GOLD", division: 4, lp: 99 };
        assert.equal(isBetterRankPosition(divOne, divFour), true);
    });

    test("within tier/division, higher lp is better", () => {
        const higher = { tier: "GOLD", division: 2, lp: 80 };
        const lower = { tier: "GOLD", division: 2, lp: 20 };
        assert.equal(isBetterRankPosition(higher, lower), true);
        assert.equal(compareRankPosition(higher, higher), 0);
    });
});

describe("peak-rank tracking (applyMatchResult)", () => {
    test("a fresh rank's peak starts at its starting position", () => {
        const rank = defaultRank(1);
        assert.equal(rank.peakTier, "COPPER");
        assert.equal(rank.peakDivision, 4);
        assert.equal(rank.peakLp, 0);
    });

    test("peak advances across several wins", () => {
        const rank = defaultRank(1);
        applyMatchResult(rank, true); // lp 16
        assert.equal(rank.peakTier, "COPPER");
        assert.equal(rank.peakDivision, 4);
        assert.equal(rank.peakLp, 16);

        applyMatchResult(rank, true); // lp 36, streak 2
        assert.equal(rank.peakLp, 36);
    });

    test("peak advances through a promotion", () => {
        const rank = { ...defaultRank(1), tier: "GOLD", division: 2, lp: 100, promo: { wins: 1, losses: 0 } };
        applyMatchResult(rank, true); // wins the series -> GOLD I, lp 0
        assert.equal(rank.tier, "GOLD");
        assert.equal(rank.division, 1);
        assert.equal(rank.peakTier, "GOLD");
        assert.equal(rank.peakDivision, 1);
        assert.equal(rank.peakLp, 0);
    });

    test("peak does NOT decrease on a loss", () => {
        const rank = defaultRank(1);
        applyMatchResult(rank, true);
        applyMatchResult(rank, true);
        const peakBefore = { tier: rank.peakTier, division: rank.peakDivision, lp: rank.peakLp };
        applyMatchResult(rank, false); // rank.lp drops, but peak should hold
        assert.equal(rank.peakTier, peakBefore.tier);
        assert.equal(rank.peakDivision, peakBefore.division);
        assert.equal(rank.peakLp, peakBefore.lp);
    });

    test("peak does NOT decrease across a demotion", () => {
        const rank = { ...defaultRank(1), tier: "GOLD", division: 3, lp: 5, streak: 0, peakTier: "GOLD", peakDivision: 3, peakLp: 90 };
        applyMatchResult(rank, false); // demotes to GOLD IV, lp 0
        assert.equal(rank.tier, "GOLD");
        assert.equal(rank.division, 4);
        // peak must remain the pre-demotion best, not regress to the new (worse) position
        assert.equal(rank.peakTier, "GOLD");
        assert.equal(rank.peakDivision, 3);
        assert.equal(rank.peakLp, 90);
    });

    test("old records with no peak fields (undefined) don't crash and adopt current position", () => {
        const rank = { tier: "SILVER", division: 3, lp: 40, wins: 0, losses: 0, promo: null, streak: 0, season: 1 };
        assert.equal(rank.peakTier, undefined);
        applyMatchResult(rank, true);
        assert.equal(rank.peakTier, "SILVER");
        assert.ok(typeof rank.peakDivision === "number");
        assert.ok(typeof rank.peakLp === "number");
    });
});

describe("season rollover snapshots the outgoing peak (applySeasonResetIfNeeded)", () => {
    test("captures outgoing peak into lastSeasonPeak*/lastSeasonNumber, live peak resets to new season's landing spot", () => {
        const rank = { ...defaultRank(5), tier: "PLATINUM", division: 2, lp: 60, peakTier: "PLATINUM", peakDivision: 1, peakLp: 80 };
        applySeasonResetIfNeeded(rank, 6);

        assert.equal(rank.lastSeasonPeakTier, "PLATINUM");
        assert.equal(rank.lastSeasonPeakDivision, 1);
        assert.equal(rank.lastSeasonPeakLp, 80);
        assert.equal(rank.lastSeasonNumber, 5); // the OUTGOING season

        // Tier is above Silver, so the reset compresses down to Silver II / 50 LP - the live peak
        // must reset to match that landing spot, not carry over the old season's peak.
        assert.equal(rank.tier, "SILVER");
        assert.equal(rank.division, 2);
        assert.equal(rank.lp, 50);
        assert.equal(rank.peakTier, "SILVER");
        assert.equal(rank.peakDivision, 2);
        assert.equal(rank.peakLp, 50);
    });

    test("a record with no peak ever recorded snapshots nothing (old record, no reward owed)", () => {
        const rank = { tier: "GOLD", division: 2, lp: 40, wins: 0, losses: 0, promo: null, streak: 0, season: 5 };
        applySeasonResetIfNeeded(rank, 6);
        assert.equal(rank.lastSeasonPeakTier, undefined);
        assert.equal(rank.lastSeasonNumber, undefined);
        // live peak is still established fresh at the new landing spot going forward
        assert.equal(rank.peakTier, rank.tier);
        assert.equal(rank.peakDivision, rank.division);
        assert.equal(rank.peakLp, rank.lp);
    });

    test("a second consecutive season rollover re-snapshots using the most recent peak", () => {
        const rank = { ...defaultRank(5), tier: "BRONZE", division: 2, lp: 30, peakTier: "BRONZE", peakDivision: 1, peakLp: 70 };
        applySeasonResetIfNeeded(rank, 6);
        assert.equal(rank.lastSeasonPeakTier, "BRONZE");
        assert.equal(rank.lastSeasonNumber, 5);

        // Simulate some play in season 6 reaching a new peak, then roll to season 7.
        rank.peakTier = "SILVER";
        rank.peakDivision = 3;
        rank.peakLp = 10;
        applySeasonResetIfNeeded(rank, 7);
        assert.equal(rank.lastSeasonPeakTier, "SILVER");
        assert.equal(rank.lastSeasonPeakDivision, 3);
        assert.equal(rank.lastSeasonPeakLp, 10);
        assert.equal(rank.lastSeasonNumber, 6);
    });
});

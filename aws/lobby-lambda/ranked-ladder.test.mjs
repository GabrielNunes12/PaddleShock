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

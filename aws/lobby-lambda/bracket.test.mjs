import { test, describe } from "node:test";
import assert from "node:assert/strict";

import { buildFirstRound, buildNextRound, roundIsComplete } from "./index.mjs";

describe("buildFirstRound", () => {
    test("pairs shuffled players in order into match slots", () => {
        const players = [
            { playerId: "a", displayNameHint: null },
            { playerId: "b", displayNameHint: null },
            { playerId: "c", displayNameHint: null },
            { playerId: "d", displayNameHint: null },
        ];
        const round = buildFirstRound(players);
        assert.deepEqual(round, [
            { p1: "a", p2: "b", winner: null, lobbyCode: null },
            { p1: "c", p2: "d", winner: null, lobbyCode: null },
        ]);
    });
});

describe("roundIsComplete", () => {
    test("false while any match lacks a winner", () => {
        const round = [
            { p1: "a", p2: "b", winner: "a", lobbyCode: null },
            { p1: "c", p2: "d", winner: null, lobbyCode: null },
        ];
        assert.equal(roundIsComplete(round), false);
    });

    test("true once every match has a winner", () => {
        const round = [
            { p1: "a", p2: "b", winner: "a", lobbyCode: null },
            { p1: "c", p2: "d", winner: "d", lobbyCode: null },
        ];
        assert.equal(roundIsComplete(round), true);
    });
});

describe("buildNextRound", () => {
    test("pairs winners of consecutive matches in order (4-player bracket, round 1 -> 2)", () => {
        const completedRound = [
            { p1: "a", p2: "b", winner: "a", lobbyCode: null },
            { p1: "c", p2: "d", winner: "d", lobbyCode: null },
        ];
        const nextRound = buildNextRound(completedRound);
        assert.deepEqual(nextRound, [
            { p1: "a", p2: "d", winner: null, lobbyCode: null },
        ]);
    });

    test("pairs winners in order for an 8-player bracket's first round (4 matches -> 2)", () => {
        const completedRound = [
            { p1: "a", p2: "b", winner: "a", lobbyCode: null },
            { p1: "c", p2: "d", winner: "d", lobbyCode: null },
            { p1: "e", p2: "f", winner: "e", lobbyCode: null },
            { p1: "g", p2: "h", winner: "h", lobbyCode: null },
        ];
        const nextRound = buildNextRound(completedRound);
        assert.deepEqual(nextRound, [
            { p1: "a", p2: "d", winner: null, lobbyCode: null },
            { p1: "e", p2: "h", winner: null, lobbyCode: null },
        ]);
    });

    test("returns null when the completed round was the final (single-match) round", () => {
        const finalRound = [{ p1: "a", p2: "d", winner: "a", lobbyCode: null }];
        assert.equal(buildNextRound(finalRound), null);
    });
});

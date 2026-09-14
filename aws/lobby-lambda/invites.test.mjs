import { test, describe } from "node:test";
import assert from "node:assert/strict";

import { appendInviteCapped } from "./index.mjs";

describe("appendInviteCapped", () => {
    test("appends to an empty/missing list", () => {
        const result = appendInviteCapped(null, { fromPlayerId: "a", fromNameHint: null, lobbyCode: "ABC123", sentAt: 1 });
        assert.deepEqual(result, [{ fromPlayerId: "a", fromNameHint: null, lobbyCode: "ABC123", sentAt: 1 }]);
    });

    test("appends to an existing list, preserving order", () => {
        const existing = [
            { fromPlayerId: "a", fromNameHint: null, lobbyCode: "AAA111", sentAt: 1 },
            { fromPlayerId: "b", fromNameHint: null, lobbyCode: "BBB222", sentAt: 2 },
        ];
        const result = appendInviteCapped(existing, { fromPlayerId: "c", fromNameHint: null, lobbyCode: "CCC333", sentAt: 3 });
        assert.deepEqual(result, [
            { fromPlayerId: "a", fromNameHint: null, lobbyCode: "AAA111", sentAt: 1 },
            { fromPlayerId: "b", fromNameHint: null, lobbyCode: "BBB222", sentAt: 2 },
            { fromPlayerId: "c", fromNameHint: null, lobbyCode: "CCC333", sentAt: 3 },
        ]);
    });

    test("drops the oldest entry once past the 5-item cap", () => {
        const existing = [1, 2, 3, 4, 5].map((n) => ({ fromPlayerId: `p${n}`, fromNameHint: null, lobbyCode: `CODE${n}`, sentAt: n }));
        const result = appendInviteCapped(existing, { fromPlayerId: "p6", fromNameHint: null, lobbyCode: "CODE6", sentAt: 6 });
        assert.equal(result.length, 5);
        assert.deepEqual(result.map((i) => i.fromPlayerId), ["p2", "p3", "p4", "p5", "p6"]);
    });

    test("caps correctly even when several appends happen past the cap in sequence", () => {
        let invites = [];
        for (let i = 1; i <= 8; i++) {
            invites = appendInviteCapped(invites, { fromPlayerId: `p${i}`, fromNameHint: null, lobbyCode: `CODE${i}`, sentAt: i });
        }
        assert.equal(invites.length, 5);
        assert.deepEqual(invites.map((i) => i.fromPlayerId), ["p4", "p5", "p6", "p7", "p8"]);
    });
});

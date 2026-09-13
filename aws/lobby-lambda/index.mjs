import { DynamoDBClient } from "@aws-sdk/client-dynamodb";
import { DynamoDBDocumentClient, PutCommand, UpdateCommand, GetCommand } from "@aws-sdk/lib-dynamodb";

// Rendezvous matchmaking + ranked ladder for PaddleShock. Gameplay itself stays peer-to-peer
// over UDP (see NetHost/NetClient) - this Lambda only brokers lobby codes and tracks rank state;
// it never sees or relays any game traffic.

const TABLE = process.env.TABLE_NAME;
const RANKS_TABLE = process.env.RANKS_TABLE_NAME;
const client = DynamoDBDocumentClient.from(new DynamoDBClient({}));

const CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no ambiguous 0/O/1/I
const LOBBY_TTL_SECONDS = 600;

function randomCode(length = 6) {
    let code = "";
    for (let i = 0; i < length; i++) {
        code += CODE_CHARS[Math.floor(Math.random() * CODE_CHARS.length)];
    }
    return code;
}

function response(statusCode, body) {
    return {
        statusCode,
        headers: { "content-type": "application/json" },
        body: JSON.stringify(body),
    };
}

async function handleCreate(body) {
    if (!body.addr) {
        return response(400, { error: "missing addr" });
    }
    const now = Math.floor(Date.now() / 1000);
    for (let attempt = 0; attempt < 5; attempt++) {
        const code = randomCode();
        try {
            await client.send(new PutCommand({
                TableName: TABLE,
                Item: { code, hostAddr: body.addr, joinerAddr: null, ttl: now + LOBBY_TTL_SECONDS },
                ConditionExpression: "attribute_not_exists(code)",
            }));
            return response(200, { code });
        } catch (e) {
            if (e.name !== "ConditionalCheckFailedException") {
                throw e;
            }
            // code collision - loop and try a new random code
        }
    }
    return response(500, { error: "could not allocate a lobby code, try again" });
}

async function handleJoin(body) {
    if (!body.code || !body.addr) {
        return response(400, { error: "missing code/addr" });
    }
    try {
        const result = await client.send(new UpdateCommand({
            TableName: TABLE,
            Key: { code: body.code },
            UpdateExpression: "SET joinerAddr = :a",
            ConditionExpression: "attribute_exists(code)",
            ExpressionAttributeValues: { ":a": body.addr },
            ReturnValues: "ALL_NEW",
        }));
        return response(200, { hostAddr: result.Attributes.hostAddr });
    } catch (e) {
        if (e.name === "ConditionalCheckFailedException") {
            return response(404, { error: "lobby not found or expired" });
        }
        throw e;
    }
}

async function handlePoll(body) {
    if (!body.code) {
        return response(400, { error: "missing code" });
    }
    const result = await client.send(new GetCommand({ TableName: TABLE, Key: { code: body.code } }));
    if (!result.Item) {
        return response(404, { error: "lobby not found or expired" });
    }
    return response(200, { joinerAddr: result.Item.joinerAddr ?? null });
}

// ---------------------------------------------------------------------------------------------
// Ranked ladder: copper -> bronze -> silver -> gold -> platinum -> diamond, 4 divisions each
// (IV..I, stored as 4..1), 0-100 LP per division. Reaching 100 LP starts a best-of-3 promotion
// series instead of an instant promotion (except at Diamond I, the ceiling); dropping below 0 LP
// demotes a division (floored at Copper IV). Trust model: the HOST reports results for BOTH
// players in one call, since the host already owns the authoritative match simulation - this is
// the same trust boundary the P2P architecture already relies on for the match itself, not a new
// one. Not proof against two colluding accounts, but not self-reportable by a lone player either.
// ---------------------------------------------------------------------------------------------

const TIERS = ["COPPER", "BRONZE", "SILVER", "GOLD", "PLATINUM", "DIAMOND"];
const LP_PER_WIN = 20;
const LP_PER_LOSS = 15;
const PROMO_LOSS_CUSHION_LP = 75;
const SEASON_ANCHOR_EPOCH = 1735689600; // 2025-01-01T00:00:00Z
const SEASON_DURATION_SECONDS = 21 * 24 * 60 * 60; // 21 days
const MATCH_IDEMPOTENCY_TTL_SECONDS = 3600;

function currentSeason() {
    return Math.floor((Date.now() / 1000 - SEASON_ANCHOR_EPOCH) / SEASON_DURATION_SECONDS);
}

function defaultRank(season) {
    return { tier: "COPPER", division: 4, lp: 0, wins: 0, losses: 0, promo: null, season };
}

function isMaxRank(rank) {
    return rank.tier === "DIAMOND" && rank.division === 1;
}

function promoteOneStep(rank) {
    rank.division -= 1;
    if (rank.division < 1) {
        rank.tier = TIERS[TIERS.indexOf(rank.tier) + 1];
        rank.division = 4;
    }
}

function demoteOneStep(rank) {
    if (rank.tier === "COPPER" && rank.division === 4) {
        return; // floor - can't demote further
    }
    rank.division += 1;
    if (rank.division > 4) {
        rank.tier = TIERS[TIERS.indexOf(rank.tier) - 1];
        rank.division = 1;
    }
}

/** Soft-resets a rank record into a fresh season if it's stale, mutating it in place. */
function applySeasonResetIfNeeded(rank, season) {
    if (rank.season === season) {
        return;
    }
    if (TIERS.indexOf(rank.tier) > TIERS.indexOf("SILVER")) {
        rank.tier = "SILVER";
        rank.division = 2;
        rank.lp = 50;
    } else {
        rank.lp = Math.min(rank.lp, 50);
    }
    rank.promo = null;
    rank.season = season;
}

/** Applies one match result to a rank record, mutating it in place. Returns a small summary of
 *  what happened (LP delta, promotion/demotion/promo-series flags) for the caller to relay back
 *  to the client that reported the match. */
function applyMatchResult(rank, won) {
    if (rank.promo) {
        if (won) {
            rank.promo.wins += 1;
        } else {
            rank.promo.losses += 1;
        }
        if (rank.promo.wins >= 2) {
            promoteOneStep(rank);
            rank.lp = 0;
            rank.promo = null;
            return { lpChange: 0, promoted: true, demoted: false, promoSeriesResult: "won" };
        }
        if (rank.promo.losses >= 2) {
            rank.lp = PROMO_LOSS_CUSHION_LP;
            rank.promo = null;
            return { lpChange: 0, promoted: false, demoted: false, promoSeriesResult: "lost" };
        }
        return { lpChange: 0, promoted: false, demoted: false, promoSeriesResult: "ongoing" };
    }

    if (won) {
        rank.wins += 1;
        rank.lp += LP_PER_WIN;
        if (rank.lp >= 100) {
            rank.lp = 100;
            if (!isMaxRank(rank)) {
                rank.promo = { wins: 0, losses: 0 };
                return { lpChange: LP_PER_WIN, promoted: false, demoted: false, promoSeriesResult: "started" };
            }
        }
        return { lpChange: LP_PER_WIN, promoted: false, demoted: false, promoSeriesResult: null };
    }

    rank.losses += 1;
    rank.lp -= LP_PER_LOSS;
    if (rank.lp < 0) {
        rank.lp = 0;
        const wasFloor = rank.tier === "COPPER" && rank.division === 4;
        demoteOneStep(rank);
        return { lpChange: -LP_PER_LOSS, promoted: false, demoted: !wasFloor, promoSeriesResult: null };
    }
    return { lpChange: -LP_PER_LOSS, promoted: false, demoted: false, promoSeriesResult: null };
}

async function loadRank(playerId, season) {
    const result = await client.send(new GetCommand({ TableName: RANKS_TABLE, Key: { playerId } }));
    const rank = result.Item ? { ...result.Item } : defaultRank(season);
    applySeasonResetIfNeeded(rank, season);
    return rank;
}

async function saveRank(playerId, rank) {
    await client.send(new PutCommand({ TableName: RANKS_TABLE, Item: { playerId, ...rank } }));
}

async function handleGetRank(body) {
    if (!body.playerId) {
        return response(400, { error: "missing playerId" });
    }
    const rank = await loadRank(body.playerId, currentSeason());
    return response(200, rank);
}

async function handleReportMatchResult(body) {
    const { hostPlayerId, joinerPlayerId, hostWon, matchId } = body;
    if (!hostPlayerId || !joinerPlayerId || typeof hostWon !== "boolean" || !matchId) {
        return response(400, { error: "missing hostPlayerId/joinerPlayerId/hostWon/matchId" });
    }

    // Idempotency guard: a retried report for the same matchId (e.g. a client-side timeout retry)
    // must not double-apply LP changes. The marker reuses this table with a "match:" prefixed key
    // - a plain string PK, no schema change needed - and expires on its own via the TTL attribute.
    const now = Math.floor(Date.now() / 1000);
    try {
        await client.send(new PutCommand({
            TableName: RANKS_TABLE,
            Item: { playerId: `match:${matchId}`, ttl: now + MATCH_IDEMPOTENCY_TTL_SECONDS },
            ConditionExpression: "attribute_not_exists(playerId)",
        }));
    } catch (e) {
        if (e.name === "ConditionalCheckFailedException") {
            return response(409, { error: "match result already reported" });
        }
        throw e;
    }

    const season = currentSeason();
    const hostRank = await loadRank(hostPlayerId, season);
    const joinerRank = await loadRank(joinerPlayerId, season);
    const hostSummary = applyMatchResult(hostRank, hostWon);
    const joinerSummary = applyMatchResult(joinerRank, !hostWon);
    await saveRank(hostPlayerId, hostRank);
    await saveRank(joinerPlayerId, joinerRank);

    return response(200, {
        host: { ...hostRank, ...hostSummary },
        joiner: { ...joinerRank, ...joinerSummary },
    });
}

export const handler = async (event) => {
    let body;
    try {
        body = JSON.parse(event.body || "{}");
    } catch {
        return response(400, { error: "malformed JSON body" });
    }

    switch (body.action) {
        case "create": return handleCreate(body);
        case "join": return handleJoin(body);
        case "poll": return handlePoll(body);
        case "getRank": return handleGetRank(body);
        case "reportMatchResult": return handleReportMatchResult(body);
        default: return response(400, { error: "unknown action" });
    }
};

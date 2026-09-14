import { DynamoDBClient } from "@aws-sdk/client-dynamodb";
import { DynamoDBDocumentClient, PutCommand, UpdateCommand, GetCommand, DeleteCommand, ScanCommand } from "@aws-sdk/lib-dynamodb";

// Rendezvous matchmaking + ranked ladder for PaddleShock. Gameplay itself stays peer-to-peer
// over UDP (see NetHost/NetClient) - this Lambda only brokers lobby codes and tracks rank state;
// it never sees or relays any game traffic.

const TABLE = process.env.TABLE_NAME;
const RANKS_TABLE = process.env.RANKS_TABLE_NAME;
const TOURNAMENTS_TABLE = process.env.TOURNAMENTS_TABLE_NAME;
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
                // hostPlayerId/joinerPlayerId let reportMatchResult later verify a report is
                // backed by a real session between these two players (see handleReportMatchResult)
                // instead of trusting whatever ids a caller sends it directly.
                Item: {
                    code,
                    hostAddr: body.addr,
                    hostPlayerId: body.playerId ?? null,
                    joinerAddr: null,
                    joinerPlayerId: null,
                    ttl: now + LOBBY_TTL_SECONDS,
                },
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
        // ConditionExpression closes a race: without it, two near-simultaneous joins on the same
        // code would both succeed and the second would silently clobber the first's joinerAddr,
        // stranding the first joiner with no error. Only allow the write through if nobody has
        // joined yet (joinerAddr is still the null handleCreate set it to) or if this is the same
        // joiner retrying (same addr) - anything else (a genuinely different second joiner) loses
        // the race and gets a clear error instead of silently overwriting the winner.
        const result = await client.send(new UpdateCommand({
            TableName: TABLE,
            Key: { code: body.code },
            UpdateExpression: "SET joinerAddr = :a, joinerPlayerId = :p",
            ConditionExpression: "attribute_exists(code) AND (joinerAddr = :nullAddr OR joinerAddr = :a)",
            ExpressionAttributeValues: { ":a": body.addr, ":p": body.playerId ?? null, ":nullAddr": null },
            ReturnValues: "ALL_NEW",
        }));
        return response(200, { hostAddr: result.Attributes.hostAddr });
    } catch (e) {
        if (e.name === "ConditionalCheckFailedException") {
            const existing = await client.send(new GetCommand({ TableName: TABLE, Key: { code: body.code } }));
            if (!existing.Item) {
                return response(404, { error: "lobby not found or expired" });
            }
            return response(409, { error: "lobby already has a joiner" });
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
// LP per win/loss scales with the player's current streak rather than a flat amount: a cold
// start (streak reset to 1, e.g. just off a loss, a promotion, or a season reset) earns the base
// amount, and each additional consecutive win adds a bonus, capped at MAX_LP_WIN - "prove it
// again" every time form resets, "climb faster" while it's hot. Losses scale the same way in
// reverse (a losing streak costs progressively more, capped at MAX_LP_LOSS) so a bad run doesn't
// erase progress in one hit but also doesn't get cheaper the longer it goes.
const BASE_LP_WIN = 16;
const STREAK_BONUS_PER_WIN = 4;
const MAX_LP_WIN = 38;
const BASE_LP_LOSS = 12;
const STREAK_PENALTY_PER_LOSS = 3;
const MAX_LP_LOSS = 28;
const PROMO_LOSS_CUSHION_LP = 75;
const SEASON_ANCHOR_EPOCH = 1735689600; // 2025-01-01T00:00:00Z
const SEASON_DURATION_SECONDS = 21 * 24 * 60 * 60; // 21 days
const MATCH_IDEMPOTENCY_TTL_SECONDS = 3600;

function currentSeason() {
    return Math.floor((Date.now() / 1000 - SEASON_ANCHOR_EPOCH) / SEASON_DURATION_SECONDS);
}

function defaultRank(season) {
    return { tier: "COPPER", division: 4, lp: 0, wins: 0, losses: 0, promo: null, streak: 0, season };
}

/** LP for the Nth consecutive win (N=1 is a fresh streak, right after a loss/promotion/reset). */
function lpForWinStreak(streak) {
    return Math.min(MAX_LP_WIN, BASE_LP_WIN + (streak - 1) * STREAK_BONUS_PER_WIN);
}

/** LP lost for the Nth consecutive loss (N=1 is a fresh losing streak). */
function lpForLossStreak(streak) {
    return Math.min(MAX_LP_LOSS, BASE_LP_LOSS + (streak - 1) * STREAK_PENALTY_PER_LOSS);
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
    rank.streak = 0;
    rank.season = season;
}

/** Applies one match result to a rank record, mutating it in place. Returns a small summary of
 *  what happened (LP delta, promotion/demotion/promo-series flags) for the caller to relay back
 *  to the client that reported the match. */
function applyMatchResult(rank, won) {
    rank.streak = rank.streak || 0; // old records predate this field

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
        rank.streak = rank.streak > 0 ? rank.streak + 1 : 1;
        const gain = lpForWinStreak(rank.streak);
        rank.wins += 1;
        rank.lp += gain;
        if (rank.lp >= 100) {
            rank.lp = 100;
            if (!isMaxRank(rank)) {
                rank.promo = { wins: 0, losses: 0 };
                return { lpChange: gain, promoted: false, demoted: false, promoSeriesResult: "started" };
            }
        }
        return { lpChange: gain, promoted: false, demoted: false, promoSeriesResult: null };
    }

    rank.streak = rank.streak < 0 ? rank.streak - 1 : -1;
    const loss = lpForLossStreak(-rank.streak);
    rank.losses += 1;
    rank.lp -= loss;
    if (rank.lp < 0) {
        rank.lp = 0;
        const wasFloor = rank.tier === "COPPER" && rank.division === 4;
        demoteOneStep(rank);
        return { lpChange: -loss, promoted: false, demoted: !wasFloor, promoSeriesResult: null };
    }
    return { lpChange: -loss, promoted: false, demoted: false, promoSeriesResult: null };
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

const LEADERBOARD_MAX_LIMIT = 100;
const LEADERBOARD_SCAN_CAP = 1000; // enough for a hobby-scale ladder; revisit with a GSI if this grows

/** Top players by tier (best first), then division (lower/better first), then LP (higher first).
 *  A full table scan is fine at this scale (see LEADERBOARD_SCAN_CAP) - a real leaderboard at
 *  meaningful scale would want a GSI instead of scanning the whole table on every request. */
async function handleGetLeaderboard(body) {
    const limit = Math.min(LEADERBOARD_MAX_LIMIT, Math.max(1, Number(body.limit) || 20));
    const result = await client.send(new ScanCommand({
        TableName: RANKS_TABLE,
        Limit: LEADERBOARD_SCAN_CAP,
    }));
    const season = currentSeason();
    const entries = (result.Items || [])
        // "match:"/"rate:" prefixed keys are internal bookkeeping (idempotency/rate-limit
        // markers), not real player records - they lack a "tier" field entirely.
        .filter((item) => typeof item.tier === "string")
        .map((item) => {
            const rank = { ...item };
            applySeasonResetIfNeeded(rank, season); // display-only - never persisted here
            return rank;
        })
        .sort((a, b) => {
            const tierDiff = TIERS.indexOf(b.tier) - TIERS.indexOf(a.tier);
            if (tierDiff !== 0) {
                return tierDiff;
            }
            if (a.division !== b.division) {
                return a.division - b.division; // lower division number = better (I beats IV)
            }
            return b.lp - a.lp;
        })
        .slice(0, limit)
        .map((rank) => ({
            playerId: rank.playerId,
            tier: rank.tier,
            division: rank.division,
            lp: rank.lp,
            wins: rank.wins,
            losses: rank.losses,
        }));
    return response(200, { entries });
}

async function handleReportMatchResult(body) {
    const { hostPlayerId, joinerPlayerId, hostWon, matchId, code } = body;
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

    // Forgery guard: tie the report to a real lobby session so a caller can't just POST an
    // arbitrary victim playerId and a fabricated result directly (repeating with a fresh matchId
    // each time to dodge the idempotency guard above). Only lobby-code matches have a `code` to
    // check against - direct IP:port LAN matches never touch this table at all, so there is
    // nothing to verify for those and this check is skipped (see aws/README.md "Trust model" for
    // that accepted tradeoff). When a code IS supplied, both ids must match the session that was
    // actually brokered for it.
    if (code) {
        const lobby = await client.send(new GetCommand({ TableName: TABLE, Key: { code } }));
        if (!lobby.Item) {
            return response(403, { error: "no matching lobby session (missing, expired, or already consumed)" });
        }
        if (lobby.Item.hostPlayerId !== hostPlayerId || lobby.Item.joinerPlayerId !== joinerPlayerId) {
            return response(403, { error: "hostPlayerId/joinerPlayerId does not match the lobby session" });
        }
    }

    const season = currentSeason();
    const hostRank = await loadRank(hostPlayerId, season);
    const joinerRank = await loadRank(joinerPlayerId, season);
    const hostSummary = applyMatchResult(hostRank, hostWon);
    const joinerSummary = applyMatchResult(joinerRank, !hostWon);
    await saveRank(hostPlayerId, hostRank);
    await saveRank(joinerPlayerId, joinerRank);

    // Consume the lobby record on success so it can't be replayed for a second fraudulent report
    // (e.g. reusing the same code/ids with a fresh matchId to farm LP again).
    if (code) {
        await client.send(new DeleteCommand({ TableName: TABLE, Key: { code } }));
    }

    return response(200, {
        host: { ...hostRank, ...hostSummary },
        joiner: { ...joinerRank, ...joinerSummary },
    });
}

// ---------------------------------------------------------------------------------------------
// Tournaments: a lightweight single-elimination bracket orchestrator layered on top of the
// existing lobby-code plumbing. This only sequences WHO plays WHOM and WHEN - it never touches
// gameplay networking itself. Each individual bracket match still gets its own real lobby code
// from the existing create/join actions; this table just tracks the bracket shape and which
// lobby code (if any) has been published for each pairing so the p2 side can find it.
// ---------------------------------------------------------------------------------------------

const TOURNAMENT_TTL_SECONDS = 6 * 60 * 60; // 6 hours - generous enough for a full bracket to play out
const VALID_TOURNAMENT_SIZES = [4, 8];

function shuffled(array) {
    const result = [...array];
    for (let i = result.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [result[i], result[j]] = [result[j], result[i]];
    }
    return result;
}

/** Builds the first round's pairings from a (already shuffled) player list. */
function buildFirstRound(players) {
    const round = [];
    for (let i = 0; i < players.length; i += 2) {
        round.push({ p1: players[i].playerId, p2: players[i + 1].playerId, winner: null, lobbyCode: null });
    }
    return round;
}

/** Given a round where every match has a winner, returns either the next round's pairings (winners
 *  of match 0 vs match 1, match 2 vs match 3, etc.) or, if this was the final (single-match) round,
 *  null to signal the bracket is complete. Pure - exported for unit testing (see
 *  ranked-ladder.test.mjs-style tests in bracket.test.mjs). */
function buildNextRound(completedRound) {
    if (completedRound.length === 1) {
        return null; // final round just played - bracket is complete, no next round
    }
    const winners = completedRound.map((match) => match.winner);
    const nextRound = [];
    for (let i = 0; i < winners.length; i += 2) {
        nextRound.push({ p1: winners[i], p2: winners[i + 1], winner: null, lobbyCode: null });
    }
    return nextRound;
}

/** True once every match slot in a round has a winner recorded. */
function roundIsComplete(round) {
    return round.every((match) => match.winner !== null);
}

async function loadTournament(code) {
    const result = await client.send(new GetCommand({ TableName: TOURNAMENTS_TABLE, Key: { code } }));
    return result.Item ?? null;
}

async function handleCreateTournament(body) {
    const { hostPlayerId, maxPlayers } = body;
    if (!hostPlayerId || !VALID_TOURNAMENT_SIZES.includes(maxPlayers)) {
        return response(400, { error: "missing hostPlayerId or invalid maxPlayers (must be 4 or 8)" });
    }
    const now = Math.floor(Date.now() / 1000);
    for (let attempt = 0; attempt < 5; attempt++) {
        const code = randomCode();
        try {
            await client.send(new PutCommand({
                TableName: TOURNAMENTS_TABLE,
                Item: {
                    code,
                    hostPlayerId,
                    maxPlayers,
                    players: [{ playerId: hostPlayerId, displayNameHint: body.displayNameHint ?? null }],
                    status: "OPEN",
                    bracket: null,
                    champion: null,
                    createdAt: now,
                    ttl: now + TOURNAMENT_TTL_SECONDS,
                },
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
    return response(500, { error: "could not allocate a tournament code, try again" });
}

async function handleJoinTournament(body) {
    const { code, playerId } = body;
    if (!code || !playerId) {
        return response(400, { error: "missing code/playerId" });
    }

    for (let attempt = 0; attempt < 5; attempt++) {
        const tournament = await loadTournament(code);
        if (!tournament) {
            return response(404, { error: "tournament not found or expired" });
        }
        if (tournament.players.some((p) => p.playerId === playerId)) {
            return response(200, tournament); // already joined - idempotent success
        }
        if (tournament.status !== "OPEN") {
            return response(409, { error: "tournament is no longer open" });
        }
        if (tournament.players.length >= tournament.maxPlayers) {
            return response(409, { error: "tournament is full" });
        }
        try {
            // Same race-safety pattern as handleJoin: the ConditionExpression only allows the
            // append through if the tournament still exists and still has room, closing the race
            // where two near-simultaneous joins would both read "room available" and both append,
            // overfilling the bracket. A losing racer retries against the freshly-read state.
            const result = await client.send(new UpdateCommand({
                TableName: TOURNAMENTS_TABLE,
                Key: { code },
                UpdateExpression: "SET players = list_append(players, :newPlayer)",
                ConditionExpression: "size(players) < :max AND attribute_exists(code)",
                ExpressionAttributeValues: {
                    ":newPlayer": [{ playerId, displayNameHint: body.displayNameHint ?? null }],
                    ":max": tournament.maxPlayers,
                },
                ReturnValues: "ALL_NEW",
            }));
            return response(200, result.Attributes);
        } catch (e) {
            if (e.name !== "ConditionalCheckFailedException") {
                throw e;
            }
            // lost the race (someone else appended between our read and write, or it's now full)
            // - loop, re-fetch fresh state, and re-check/retry.
        }
    }
    return response(409, { error: "could not join tournament, too much contention - try again" });
}

async function handleStartTournament(body) {
    const { code, hostPlayerId } = body;
    if (!code || !hostPlayerId) {
        return response(400, { error: "missing code/hostPlayerId" });
    }
    const tournament = await loadTournament(code);
    if (!tournament) {
        return response(404, { error: "tournament not found or expired" });
    }
    if (tournament.hostPlayerId !== hostPlayerId) {
        return response(403, { error: "only the host can start the tournament" });
    }
    if (tournament.status !== "OPEN") {
        return response(409, { error: "tournament already started or complete" });
    }
    if (tournament.players.length !== tournament.maxPlayers) {
        return response(409, { error: "tournament is not full yet" });
    }

    const shuffledPlayers = shuffled(tournament.players);
    const firstRound = buildFirstRound(shuffledPlayers);
    try {
        const result = await client.send(new UpdateCommand({
            TableName: TOURNAMENTS_TABLE,
            Key: { code },
            UpdateExpression: "SET #status = :inProgress, bracket = :bracket, players = :players",
            ExpressionAttributeNames: { "#status": "status" },
            ExpressionAttributeValues: {
                ":inProgress": "IN_PROGRESS",
                ":bracket": { rounds: [firstRound] },
                ":players": shuffledPlayers,
                ":open": "OPEN",
            },
            ConditionExpression: "attribute_exists(code) AND #status = :open",
            ReturnValues: "ALL_NEW",
        }));
        return response(200, result.Attributes);
    } catch (e) {
        if (e.name === "ConditionalCheckFailedException") {
            return response(409, { error: "tournament state changed, try again" });
        }
        throw e;
    }
}

function validMatchSlot(tournament, roundIndex, matchIndex) {
    if (!tournament.bracket) {
        return null;
    }
    const round = tournament.bracket.rounds[roundIndex];
    if (!round) {
        return null;
    }
    const match = round[matchIndex];
    if (!match) {
        return null;
    }
    return match;
}

async function handleSetTournamentMatchLobbyCode(body) {
    const { code, roundIndex, matchIndex, playerId, lobbyCode } = body;
    if (!code || typeof roundIndex !== "number" || typeof matchIndex !== "number" || !playerId || !lobbyCode) {
        return response(400, { error: "missing code/roundIndex/matchIndex/playerId/lobbyCode" });
    }
    const tournament = await loadTournament(code);
    if (!tournament) {
        return response(404, { error: "tournament not found or expired" });
    }
    const match = validMatchSlot(tournament, roundIndex, matchIndex);
    if (!match) {
        return response(400, { error: "invalid roundIndex/matchIndex" });
    }
    if (match.p1 !== playerId) {
        return response(403, { error: "only the p1 side of this match may publish a lobby code" });
    }

    tournament.bracket.rounds[roundIndex][matchIndex].lobbyCode = lobbyCode;
    await client.send(new PutCommand({ TableName: TOURNAMENTS_TABLE, Item: tournament }));
    return response(200, tournament);
}

async function handleReportTournamentMatchResult(body) {
    const { code, roundIndex, matchIndex, winnerPlayerId, reporterPlayerId } = body;
    if (!code || typeof roundIndex !== "number" || typeof matchIndex !== "number" || !winnerPlayerId || !reporterPlayerId) {
        return response(400, { error: "missing code/roundIndex/matchIndex/winnerPlayerId/reporterPlayerId" });
    }
    const tournament = await loadTournament(code);
    if (!tournament) {
        return response(404, { error: "tournament not found or expired" });
    }
    const match = validMatchSlot(tournament, roundIndex, matchIndex);
    if (!match) {
        return response(400, { error: "invalid roundIndex/matchIndex" });
    }
    if (reporterPlayerId !== match.p1 && reporterPlayerId !== match.p2) {
        return response(403, { error: "reporterPlayerId is not a participant in this match" });
    }
    if (winnerPlayerId !== match.p1 && winnerPlayerId !== match.p2) {
        return response(403, { error: "winnerPlayerId is not a participant in this match" });
    }

    // Idempotency: a retried report for a slot that already has a winner is a no-op, same
    // tolerance handleReportMatchResult already gives ranked match reports.
    if (match.winner !== null) {
        return response(200, tournament);
    }

    const round = tournament.bracket.rounds[roundIndex];
    round[matchIndex].winner = winnerPlayerId;

    if (roundIsComplete(round)) {
        const nextRound = buildNextRound(round);
        if (nextRound === null) {
            tournament.status = "COMPLETE";
            tournament.champion = winnerPlayerId;
        } else {
            tournament.bracket.rounds.push(nextRound);
        }
    }

    await client.send(new PutCommand({ TableName: TOURNAMENTS_TABLE, Item: tournament }));
    return response(200, tournament);
}

async function handleGetTournamentState(body) {
    if (!body.code) {
        return response(400, { error: "missing code" });
    }
    const tournament = await loadTournament(body.code);
    if (!tournament) {
        return response(404, { error: "tournament not found or expired" });
    }
    return response(200, tournament);
}

// ---------------------------------------------------------------------------------------------
// Rate limiting: the Function URL is public/unauthenticated with no API Gateway in front of it,
// so there is no reliable source IP to key off (that only becomes available behind API Gateway,
// which is out of scope here - see aws/README.md). Instead this throttles per playerId (the one
// identifier every meaningful action already carries) with a simple fixed-window counter stored
// in the ranks table under a "rate:" prefixed key, expiring on its own via the TTL attribute -
// same pattern as the match-idempotency marker above. Not perfect (an attacker with many fake
// playerIds isn't slowed down, and `poll`, which carries no playerId, isn't covered at all) but a
// real deterrent against a naive scripted flood using one or a few ids.
// ---------------------------------------------------------------------------------------------

const RATE_LIMIT_WINDOW_SECONDS = 30;
const RATE_LIMIT_MAX_REQUESTS = 10;

/** Returns false if playerId has exceeded the request budget for the current window. */
async function checkRateLimit(playerId) {
    const windowStart = Math.floor(Date.now() / 1000 / RATE_LIMIT_WINDOW_SECONDS);
    const key = `rate:${playerId}:${windowStart}`;
    const now = Math.floor(Date.now() / 1000);
    const result = await client.send(new UpdateCommand({
        TableName: RANKS_TABLE,
        Key: { playerId: key },
        // "ttl" is a DynamoDB reserved keyword - needs an ExpressionAttributeNames alias to use
        // it inside an UpdateExpression (unlike Put/GetCommand, which reference it as a plain
        // item attribute name and are unaffected).
        UpdateExpression: "ADD reqCount :one SET #ttl = if_not_exists(#ttl, :ttl)",
        ExpressionAttributeNames: { "#ttl": "ttl" },
        ExpressionAttributeValues: { ":one": 1, ":ttl": now + RATE_LIMIT_WINDOW_SECONDS + 5 },
        ReturnValues: "UPDATED_NEW",
    }));
    return result.Attributes.reqCount <= RATE_LIMIT_MAX_REQUESTS;
}

/** The identifier to rate-limit this request by, or null if the action carries none (currently
 *  just `poll`/`getTournamentState`, which are intentionally left unthrottled - see comment
 *  above). Covers the tournament actions' id fields too (hostPlayerId/playerId already handled;
 *  reporterPlayerId is tournament-report-specific). */
function rateLimitIdFor(body) {
    return body.playerId || body.hostPlayerId || body.reporterPlayerId || null;
}

// Exported purely so the ranked-ladder LP math can be unit-tested in isolation (see
// ranked-ladder.test.mjs) without touching DynamoDB - these functions never call the AWS SDK.
export {
    TIERS,
    BASE_LP_WIN,
    STREAK_BONUS_PER_WIN,
    MAX_LP_WIN,
    BASE_LP_LOSS,
    STREAK_PENALTY_PER_LOSS,
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
    buildFirstRound,
    buildNextRound,
    roundIsComplete,
};

export const handler = async (event) => {
    let body;
    try {
        body = JSON.parse(event.body || "{}");
    } catch {
        return response(400, { error: "malformed JSON body" });
    }

    const rateLimitId = rateLimitIdFor(body);
    if (rateLimitId) {
        const allowed = await checkRateLimit(rateLimitId);
        if (!allowed) {
            return response(429, { error: "too many requests, slow down" });
        }
    }

    switch (body.action) {
        case "create": return handleCreate(body);
        case "join": return handleJoin(body);
        case "poll": return handlePoll(body);
        case "getRank": return handleGetRank(body);
        case "getLeaderboard": return handleGetLeaderboard(body);
        case "reportMatchResult": return handleReportMatchResult(body);
        case "createTournament": return handleCreateTournament(body);
        case "joinTournament": return handleJoinTournament(body);
        case "startTournament": return handleStartTournament(body);
        case "setTournamentMatchLobbyCode": return handleSetTournamentMatchLobbyCode(body);
        case "reportTournamentMatchResult": return handleReportTournamentMatchResult(body);
        case "getTournamentState": return handleGetTournamentState(body);
        default: return response(400, { error: "unknown action" });
    }
};

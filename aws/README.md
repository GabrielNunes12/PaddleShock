# PaddleShock AWS backend (matchmaking + ranked ladder)

Scope: a tiny AWS backend with two jobs. (1) Matchmaking - hands two players a short lobby code so
they can exchange public `ip:port` and connect directly over UDP; gameplay itself stays
peer-to-peer (see `src/main/java/com/paddleshock/net/`), nothing here touches game traffic. (2)
Ranked ladder - tracks each player's Copper-through-Diamond rank server-side (see "Ranked ladder"
below) so it isn't just a locally-editable save-file number.

## What's deployed (account 920394550355, region us-east-1)

- **DynamoDB table** `paddleshock-lobbies` - PK `code` (String), TTL attribute `ttl` (items
  auto-expire ~10 min after creation, so stale lobbies clean themselves up for free).
- **IAM role** `paddleshock-lobby-lambda-role` - trusts `lambda.amazonaws.com`, has
  `AWSLambdaBasicExecutionRole` (CloudWatch Logs) plus an inline policy scoped to
  `PutItem`/`GetItem`/`UpdateItem` on just the `paddleshock-lobbies` table ARN.
- **Lambda function** `paddleshock-lobby` (Node.js 20.x, source in `lobby-lambda/index.mjs`) -
  handles `create` / `join` / `poll` actions via a single JSON-body handler. Verified working via
  direct `aws lambda invoke` (returns a real lobby code, writes to DynamoDB correctly).
- **Lambda Function URL** `https://2mjcwpgb6sesrjy36nm6qxdmpu0wbvrb.lambda-url.us-east-1.on.aws/` -
  `AuthType=NONE` (public/unauthenticated - fine, since it only ever brokers ephemeral,
  non-sensitive lobby codes and ip:port pairs) with a resource policy granting both
  `lambda:InvokeFunctionUrl` and `lambda:InvokeFunction` to `Principal: "*"` (see "Resolved
  blocker" below for why both are required). **Verified live end-to-end** (create/join/poll all
  round-trip correctly).

## Resolved blocker (2026-09-13)

The Function URL initially returned `403 Forbidden` for all anonymous requests despite
`AuthType=NONE` and a resource policy granting `lambda:InvokeFunctionUrl`. Root cause per
[AWS's Function URL auth docs](https://docs.aws.amazon.com/lambda/latest/dg/urls-auth.html):
**"Starting in October 2025, new function URLs will require both `lambda:InvokeFunctionUrl` and
`lambda:InvokeFunction` permissions."** We'd only granted the first. Not an account-age
restriction as initially suspected - fixed by adding the second permission:

```
aws lambda add-permission \
  --function-name paddleshock-lobby \
  --statement-id UrlPolicyInvokeFunction \
  --action lambda:InvokeFunction \
  --principal "*" \
  --invoked-via-function-url \
  --region us-east-1
```

(`--invoked-via-function-url` sets the `lambda:InvokedViaFunctionUrl` condition so this
permission only applies to Function URL calls, not other invocation paths.)

## Protocol

Single POST endpoint, JSON body, `action` field selects behavior:

- `{"action":"create","addr":"<host's public ip:port>"}` -> `{"code":"ABC123"}`
- `{"action":"join","code":"ABC123","addr":"<joiner's public ip:port>"}` -> `{"hostAddr":"..."}`
- `{"action":"poll","code":"ABC123"}` (host polls this) -> `{"joinerAddr": null | "..."}`
- `{"action":"getRank","playerId":"<uuid>"}` -> `{"tier","division","lp","wins","losses","promo","season"}`
- `{"action":"reportMatchResult","matchId":"<uuid>","hostPlayerId":"<uuid>","joinerPlayerId":"<uuid>","hostWon":true|false}`
  -> `{"host":{...rank fields...,"lpChange","promoted","demoted","promoSeriesResult"},"joiner":{...same...}}`

## Ranked ladder

Copper -> Bronze -> Silver -> Gold -> Platinum -> Diamond, 4 divisions each (IV..I, stored/wired
as 4..1), 0-100 LP per division - see `RankTier`/`RankState`/`RankClient` in
`src/main/java/com/paddleshock/net/` and `RankTier` in `src/main/java/com/paddleshock/rank/`.

- **Identity**: each `PlayerProfile` gets a random UUID (`getPlayerId()`), generated once and
  persisted with the rest of the save - no accounts/login. Two players on the same machine with
  isolated `~/.paddleshock` dirs (or `-Duser.home` overrides, used for testing) get distinct ids;
  two instances sharing one save file will collide (see "Trust model" below - a real concern only
  for same-machine testing, never for two actual separate players).
- **Progression**: LP per win/loss scales with the player's current streak rather than a flat
  amount - a fresh streak (right after a loss, a promotion, or a season reset) earns/costs the
  base amount, and each additional consecutive win/loss adds a bonus/penalty, capped in both
  directions (`BASE_LP_WIN`=16, `+4`/win, cap 38; `BASE_LP_LOSS`=12, `+3`/loss, cap 28 - all in the
  Lambda). "Prove it again" every time form resets, "climb faster" while it's hot; a bad run costs
  progressively more too, capped so it's not devastating in one hit. `streak` is a signed int on
  the rank record (positive = win streak, negative = loss streak), reset to 0 on a season reset.
  Live-verified via direct API calls: a win streak produced 16, 20, 24, 28, 32, ... capping at 38;
  a loss streak produced 12, 15, 18, 21, 24, ... capping at 28 - both confirmed in a real full
  10-point match too, not just synthetic API calls.
  Reaching 100 LP starts a best-of-3 promotion series instead of an instant promotion (except at
  Diamond I, the ceiling) - 2 series wins promotes a division (LP resets to 0), 2 series losses
  cancels the series (LP resets to 75, a cushion below the cap rather than an immediate re-trigger).
  Dropping below 0 LP demotes a division, floored at Copper IV (never demotes below the very start).
- **Seasons**: the season number is `floor((now - anchor) / 21 days)` - a pure function of time,
  no cron job or extra storage needed. A rank record with a stale season number gets soft-reset on
  its next read: tiers above Silver compress down to Silver II/50 LP, tiers at or below Silver just
  cap their LP at 50 - never a full wipe.
- **Trust model**: the HOST reports match results for BOTH players in one call, since the host
  already owns the authoritative match simulation (`MatchSimulation` runs only on the host - see
  the main session's Phase 1 multiplayer notes) - this is the same trust boundary the P2P
  architecture already relies on for the match itself, not a new one. Not proof against two
  colluding accounts, but not self-reportable by a lone player either. The joiner shares its
  player id with the host via an extended `TYPE_HELLO` payload (see `NetProtocol`/`NetHost`).
- **Idempotency**: `reportMatchResult` requires a fresh `matchId` per match; a retried call for
  the same id (e.g. a client-side timeout retry) is rejected rather than double-applying LP. The
  marker reuses the ranks table (`match:<id>` as the `playerId` key) with the existing TTL
  mechanism, no extra table needed.
- **Display**: the joiner has no way to receive the host's authoritative LP delta over the wire
  (the host's `reportMatchResult` response is never relayed back through the game's own UDP
  protocol) - `enterJoinedMatch` snapshots the joiner's own rank just before the match starts, and
  `endRankedJoinerMatch` diffs the post-match rank against that baseline instead, with a short
  retry-and-wait loop on both ends (see the doc comments on both methods in `PaddleShockApp`) to
  ride out the inherent race between "this side's match-over event" and "the host's independent
  report call landing server-side" - both triggered by the same event, on two different machines,
  with no ordering guarantee between them. Live-verified with debug instrumentation during
  development: the fix does correctly wait out the race rather than silently showing a 0 LP
  change; a delta is spelled out as "N gained"/"N lost" rather than a +/- sign, since a "+" glyph
  in the match-end screen's font is easy to misread as a dash at that size (confirmed via a false
  alarm during testing - the underlying math was correct the whole time).
- **Not built**: no UI yet for browsing the ladder/leaderboard, no unranked-vs-ranked distinction
  (every multiplayer match is currently a ranked one), no demotion-protection grace games.

## Client-side progress

- **Done (Phase B)**: `StunClient` (`src/main/java/com/paddleshock/net/StunClient.java`) - a
  minimal RFC 5389 STUN binding client. `NetHost`/`NetClient` now run discovery on their own
  `DatagramSocket` at construction, before starting their background receive thread (required -
  STUN discovery does its own blocking `socket.receive()` calls, so it must finish first or it'd
  race the receive thread for incoming packets). Exposed via `getPublicAddress()` on both.
  Live-verified against real Google STUN servers (~70ms, consistent across runs) and regression
  -tested against the full two-instance LAN host/join flow (unaffected).
- **Done (Phase C)**: `LobbyClient` (`src/main/java/com/paddleshock/net/LobbyClient.java`) wraps
  the 3 Lambda actions over `java.net.http.HttpClient`. `NetHost.registerLobby()` and the new
  `NetClient.connectByLobbyCode(String)` (a static factory - it must discover its own public
  address on the real game socket BEFORE it can register as a joiner and learn the host's
  address, so it can't use the existing instance constructor, which needs the host address up
  front) wire this into the actual connect flow. `MultiplayerState`'s JOIN field now accepts
  either an IP:port (unchanged) or a lobby code (detected by the absence of a `:`, normalized to
  uppercase since codes are generated uppercase and DynamoDB keys are case-sensitive); the HOST
  screen shows the LAN address as before plus an "Internet code" once background registration
  completes. All background lobby calls are generation-guarded (an in-flight call superseded by
  a newer attempt, or by leaving the screen, closes its own result instead of leaking a socket).
  **Live-verified**: a real lobby code was generated, entered on a second instance (lowercase, to
  confirm normalization), resolved via the Lambda to the correct host address, and the joiner
  reached "Connecting...". The actual UDP handshake did not complete in this test - see below,
  this is expected, not a Phase C defect.
- **Done (Phase D)**: `NetHost.pollAndPunchUntilJoined(String lobbyCode)` polls the Lambda's
  `poll` action (built in Phase A, unused until now) every 1.5s for up to 30s until a joiner's
  public address appears, then sends toward it every 300ms for up to 8s - reacting to an inbound
  HELLO alone was never enough, since a joiner's first HELLOs can be dropped by the HOST's own
  NAT before the host has sent anything outbound to the joiner. Runs on the same background
  thread that already does lobby registration, continuing after the code is shown so it doesn't
  block the UI. `MultiplayerState` now also resends `NetClient`'s HELLO every 0.3s instead of
  only once at construction, so the joiner is still retrying when the host's punch eventually
  opens a path. Regression-tested: the full LAN (IP:port) host/join flow still works end to end
  with the background punch thread running concurrently, and produced no exceptions.
- **Done (Phase E) - real cross-network validation, 2026-09-13**: a same-machine test can't prove
  hole-punching works (its only possible failure mode is NAT **hairpinning** - routing a packet
  addressed to your own public IP back into your own LAN - a router feature distinct from
  **hole-punching** between two genuinely different networks). So this ran the actual production
  `NetHost`/`NetClient` code (via two tiny CLI probes, no jME/graphics involved - see
  `HostProbe`/`JoinProbe`, not committed, they're just thin wrappers calling the real classes) as
  two separate processes on two real, different networks: `HostProbe` on the developer's home
  network, `JoinProbe` on a temporary AWS EC2 instance (spun up, tested, and torn down within
  minutes - t3.micro, terminated immediately after, no lasting cost). Result:
  ```
  [home network]  PROBE lobby code: 7DGPVJ
                  PROBE hasJoiner after punch window: true
                  PROBE RESULT: SUCCESS
  [EC2, different network]  PROBE joining via code: 7DGPVJ
                             PROBE RESULT: SUCCESS
  ```
  Both sides agree: the joiner's HELLO reached the host, and the host's WELCOME reached the
  joiner, entirely over the internet via a lobby code, with zero manual IP sharing or port
  forwarding. **This is the real, working feature** - LAN-over-internet multiplayer via AWS
  lobby codes and active UDP hole-punching, confirmed end to end.

See the main session's design doc discussion (not committed) for the full phase breakdown
(A: AWS infra, B: STUN client, C: lobby UI, D: active punching, E: cross-network QA [this]).

## Redeploying the Lambda after code changes

```
cd aws/lobby-lambda
npm install
# Windows PowerShell:
Compress-Archive -Path index.mjs,node_modules,package.json -DestinationPath function.zip -Force
aws lambda update-function-code --function-name paddleshock-lobby --zip-file fileb://function.zip --region us-east-1
```

# PaddleShock Improvement Plan (research draft — not yet actioned)

Produced by three independent subagent research passes (Senior Dev, Senior QA, Game Designer),
each exploring the real codebase directly (not working from a summary) and reporting back
separately without seeing each other's findings. This document just compiles and cross-references
their output — nothing here has been implemented, and nothing should be treated as decided.
Read it, push back on anything that doesn't hold up, and tell me what (if anything) to act on.

---

## Cross-cutting priorities (found independently by 2+ agents)

These converged from three agents working blind to each other, which is a stronger signal than
any one report alone.

### 1. Zero automated tests, despite JUnit already being wired into the build
**Dev + QA both flagged this independently, unprompted.** `build.gradle.kts` declares
`org.junit.jupiter:junit-jupiter` and configures `tasks.test`, but there is no `src/test`
directory anywhere in the repo — it's unused scaffolding. `MatchSimulation` (ball physics,
scoring, power-ups) has zero jME dependencies specifically so it's testable, and the ranked
ladder's LP math (streak scaling, promotion series, season resets — all pure functions in
`aws/lobby-lambda/index.mjs`) is exactly the kind of logic that's cheap to test and expensive to
hand-verify. QA notes this is currently done via manual "live-verified" runs instead. Effort: S.
Impact: High — pays back on every future change and would have caught some of the edge cases
below (season-reset-during-promo-series, tier-boundary display bugs).

### 2. Mid-match disconnect/timeout has no handling at all
**Dev + QA both traced this through the actual netcode and confirmed it's real, not
hypothetical.** Neither `NetHost` nor `NetClient` has a heartbeat, last-seen timestamp, or
timeout. If a joiner's process dies or network drops, the host keeps applying the last-received
(stale) input forever with no "opponent disconnected" state — the paddle drifts into a clamp and
sits there. Symmetrically, a joiner whose host vanishes just stares at a frozen last snapshot
indefinitely. No forfeit is ever reported to the ranked ladder either way. Effort: M. Impact:
High — this is a certainty in real play, not an edge case.

### 3. The ranked ladder's trust model has a real, concrete exploit path
**Dev flagged the trust boundary; QA went further and found it's actually exploitable, not just
"not provably fair."** `reportMatchResult` accepts `hostPlayerId`, `joinerPlayerId`, and
`hostWon` as plain client-supplied values with **no verification these two players were ever
actually in a lobby/match together**. `playerId` is just a self-assigned random UUID with no
auth. QA's finding: anyone can call this endpoint directly with a victim's known player ID as
`joinerPlayerId` and `hostWon: true`, using a fresh random `matchId` each time to dodge the
idempotency guard, to farm LP for themselves or grief someone else's rank down — with zero rate
limiting anywhere in the Lambda. This is the most serious single finding across all three reports.
Effort: M. Impact: High.

### 4. No rate limiting / auth / input bounds on the public AWS Lambda
**Dev + QA both flagged this.** The Function URL is intentionally public/unauthenticated for
lobby brokering, but there's no throttling (API Gateway usage plan, WAF, reserved concurrency),
and QA notes `getRank` will happily create/return a record for *any* string passed as
`playerId` — letting anyone scrape other players' rank data with no limit. At real scale this is
both a cost exposure (DynamoDB write flooding) and a privacy/integrity one. Effort: S–M. Impact:
Medium-High.

### 5. The ranked ladder backend is fully built and live-verified, but invisible to players
**Dev + Design both flagged this as a big missed opportunity, from different angles.** There is
no leaderboard, no standings screen, no way to see your own rank/percentile anywhere except the
post-match summary line. Design calls this "data already paid for and currently invisible."
Effort: M. Impact: High — this is a large chunk of already-completed engineering work sitting
unused.

### 6. Every multiplayer match is unconditionally ranked — there's no casual/practice mode
**Dev + Design both flagged this.** `GameplayAppState` always routes multiplayer match-end
through the ranked path; there is no toggle anywhere. Two friends who just want to play, or a
brand-new player's very first online match, get thrown straight onto the ladder with real LP on
the line. Effort: S–M. Impact: High for onboarding and casual retention.

### 7. The joiner's post-match LP display is a timing-dependent workaround, not a real fix
**Dev flagged the architecture; QA found the concrete failure mode.** The host's authoritative
`reportMatchResult` response is never relayed to the joiner over the game's own protocol, so the
joiner instead snapshots its own rank before the match and diffs after — a race between two
independent network calls with "no ordering guarantee" (the code's own words). QA's addition:
if the host's report call itself fails (network blip, Lambda cold start — 5s timeout), **the
host's LP update is silently lost** while the joiner still shows its own locally-computed guess,
so the two players' clients can visibly disagree after the same match. Effort: S–M (relay the
real delta through `NetProtocol` instead of inferring it). Impact: Medium.

---

## Everything else, by source

### From the Senior Dev pass (technical/architecture)
- No CI pipeline at all — no automated gate before merging today.
- Save file encryption key is hardcoded/shared across installs (already tracked as an accepted
  risk in `tasks.md`, flagged here only because it compounds with the ranked-trust issue above).
- No save schema versioning — works today via ad hoc defensive null checks, won't scale.
- No crash reporting or telemetry — network/netcode failures in the field would be invisible.
- STUN discovery relies solely on Google's public servers with no self-hosted fallback.
- No TURN/relay fallback for symmetric NAT — structurally can't traverse it (known, documented
  limitation; some real fraction of players, especially on mobile hotspots, simply can't connect
  via lobby codes today).
- Steamworks integration is dormant but well-built (defensive optional init) — a "flip the
  switch" item once the $100 Steam Direct fee is paid, not a rewrite.
- No reconnect support: a joiner whose NAT mapping changes mid-match (e.g. a Wi-Fi drop) can
  never resume — there's no re-identify-and-rejoin path.

**Dev's own top 3:** automated tests + CI; mid-match disconnect/forfeit handling; ranked ladder
UI + unranked mode.

### From the Senior QA pass (stability/quality)
- **Lobby-join race**: if two clients call `join` on the same lobby code near-simultaneously, the
  second silently overwrites the first's address in DynamoDB — the host ends up punching toward
  whichever address landed last, and the other joiner can be silently stranded with just a hang,
  no error.
- A joiner can send **any** power-up catalog ID in its input packets, regardless of what it
  actually owns/has equipped — the host has no server-side ownership check, only the UI enforces
  it. Low stakes today (cosmetic unlock, not currency) but a real validation gap.
- **Save writes aren't atomic** — `Files.write` goes straight over the old file with no
  write-temp-then-rename. A crash mid-write corrupts the save; the game correctly detects this
  (GCM auth tag) but then just silently resets to a fresh profile with no recovery, wiping
  currency/purchases/rank identity.
- No save schema version field, separate from the migration-scalability concern above — no way
  to detect a genuinely incompatible future format vs. a corrupted file.
- Minor: a narrow, currently-harmless race window in `NetHost.handleHello` between setting the
  joiner's address and its player ID.
- Minor: no defensive socket cleanup if `NetHost`/`NetClient` construction throws unexpectedly
  (latent, not observed live).
- The QA test harness itself has known gaps worth closing: it shares one save file between both
  test instances unless overridden, and the one real cross-NAT hole-punch validation (via a
  temporary EC2 peer) was manual and one-off, not a repeatable automated test — QA specifically
  calls this out as the highest-leverage testing investment given how many findings above live in
  exactly those code paths.

**QA's own top 3:** fix the ranked-report trust/idempotency gap (the exploit, not just the
"no mutual confirmation" caveat); add disconnect/timeout handling; stand up the unit test suite.

### From the Game Designer pass (player experience/content)
- **"Rematch" is broken for multiplayer**: the button always routes to the single-player loadout
  flow, even after a ranked online match — there is no way to immediately replay the same
  opponent. Confirmed via direct code read, not a guess.
- The multiplayer screen's own title/subtitle ("MULTIPLAYER (LAN)... no matchmaking, same
  network") actively misdescribes the feature, which also supports AWS-brokered internet play —
  this could make players think internet play isn't supported at all.
- No tutorial/onboarding of any kind — a first-time player goes from a 5-button main menu
  straight into ranked play against a stranger with no explanation of controls or power-ups.
- AI has exactly one difficulty level (a single hardcoded speed constant) — no ramp for new
  players, no ceiling for players who outgrow it before going ranked.
- No colorblind-safe cue for power-ups — buff/debuff is signaled by green vs. red/orange with no
  alternate (icon/shape) cue.
- Content roster is real but narrow: 11 unlockable items total (3 paddles, 2 tables, 2 balls, 4
  power-ups beyond the free starters), each a single stat-tradeoff axis; ~10-20 wins per unlock.
  Levels are genuinely mechanically distinct (gravity/wind/bounce differ per level), not just
  reskins — that part holds up well.
- No friends list or shareable invite link — only a lobby code communicated out-of-band.
- No spectator mode.
- No pre-match "this is a promo match" framing — the tension is only narrated after the fact on
  the match-end screen.
- Store/loadout screens don't surface "X more wins to afford Y" progress framing.
- The single-player AI always mirrors the player's own loadout rather than having its own kit —
  reduces variety, compounds with the single-difficulty issue.

**Design's own top 3:** unranked/practice queue; leaderboard/standings screen; first-time
onboarding.

---

## A note on sequencing, if useful

Not a recommendation to act on any of this — just an observation while compiling: the automated
test suite (item 1) is the one thing that makes almost everything else cheaper and safer to build
correctly, including the ranked-integrity fix (item 3) and disconnect handling (item 2), both of
which touch exactly the kind of pure, deterministic logic (`MatchSimulation`, the Lambda's rank
math, `NetProtocol` encode/decode) that's trivial to pin down with tests before changing. Items 3
and 2 are the two findings that read as "will definitely bite a real player," as opposed to
"would be nice." Everything else is either already-known/tracked (Steam, symmetric NAT) or
genuinely a judgment call on where to spend effort next (leaderboard vs. tutorial vs. unranked
mode vs. content expansion) — that judgment is yours to make, not something the research passes
or I should be deciding.

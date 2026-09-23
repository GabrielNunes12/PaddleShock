# Behavior-changing power-ups

- Status: approved (requested 2026-09-23)

## Problem / goal
All four existing power-ups scale a paddle's size or speed. Add three that change what happens
in a rally instead.

## Scope
### Included
- **Curveball** (buff, 10 s charge): your next paddle hit gets maximum spin - in your swipe
  direction, or away from the opponent's paddle if you hit it still. Consumed by that hit.
- **Shield** (buff, 10 s): the next ball that would score against you rebounds off your goal
  line instead. Consumed by that block. Shown as a glowing bar across your goal.
- **Ghost Ball** (debuff, 6 s): the ball is invisible to the target while it's over the middle
  of the table (|z| < 3.5). An AI target can't track it there either (it keeps chasing where
  it last saw the ball).
- Store: power-up cards get a one-line description (all 7); categories with more than 4 items
  page with < > arrows instead of shrinking cards (7 power-ups don't fit one row at 720p).
- Late World Tour opponents get the new power-ups in their kits.
- Networked: new state rides in the snapshot (an optional trailing effects byte + a shield-block
  flag bit); older peers ignore it. New enum values are appended, so existing power-up ordinals
  on the wire don't change.

### Explicitly out of scope
- Magnet / freeze (deferred), per-power-up art, spectator-specific ghost view (spectators always
  see the ball).

## Requirements
| # | Requirement | Acceptance criterion |
|---|---|---|
| 1 | Curveball | Unit: charged hit sets |spin| = MAX in swipe direction; still hit curves away from the opponent paddle; consumed after one hit |
| 2 | Shield | Unit: a ball past the shielded goal rebounds, no point scored, shield consumed; the next one scores |
| 3 | Ghost | Unit: hidden only for the target, only inside the zone, only while active |
| 4 | Wire compat | Protocol tests: effects round-trip; old-length packet decodes as no effects |
| 5 | Store paging | Unit: page math; screenshot of both power-up pages |
| 6 | Visible | Screenshots: shield bar in a live match; ghosted ball hidden mid-table |

## Technical approach
- `PowerUpType` gains the three (with `PaddleModifier.NONE`); `PowerUpManager` gains
  `hasEffect(side, type)` / `consumeEffect(side, type)`.
- `MatchSimulation`: curveball on contact, shield on would-be goals, `isBallHiddenFor(side)`.
- `TickResult.shieldBlocked`; `SnapshotMessage.effects` bitfield.
- `GameplayAppState`: shield bars, ball culling for the local viewer, AI ghost handling.

## Risks / open questions
- Balance (durations, cooldowns, zone size) is unplaytested; Shield in particular could stall
  matches if cooldowns are too short.

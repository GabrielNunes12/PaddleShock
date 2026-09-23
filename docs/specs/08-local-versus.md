# Local versus (two players, one PC)

- Status: approved (requested 2026-09-23)

## Problem / goal
Two people on one PC can't play each other, and Steam Remote Play Together (which streams a
local-multiplayer game to a remote friend) has nothing to work with. Add a local two-player mode.

## Scope
### Included
- LOCAL VERSUS on the main menu: starts straight into a match on the equipped arena.
- Player 1: mouse (plus a second gamepad, if two are connected) and power-up keys 1/2/3.
  Player 2: arrow keys or the first gamepad's left stick, power-ups on 8/9/0 or gamepad A/B/X.
  A Remote Play guest arrives as a keyboard or gamepad, so this covers Remote Play Together.
- Both players use Player 1's equipped paddle stats and power-up loadout (fair), with their own
  colors; a shared side-on camera so neither player is "behind" their paddle.
- HUD: "P1 x : y P2", a controls hint for the first seconds, power-up banners naming P1/P2.
- Result screen: "PLAYER 1 WINS" / "PLAYER 2 WINS", REMATCH replays locally.
- No credits, achievements or challenge progress (one person could play both sides to farm them);
  the match is still recorded in history as "Local Versus".

### Explicitly out of scope
- Split screen, more than two players, per-player profiles/loadouts, remapping controls.

## Requirements
| # | Requirement | Acceptance criterion |
|---|---|---|
| 1 | P2 keyboard stick | Unit: held arrows -> stick vector; opposite keys cancel; diagonals normalized |
| 2 | P2 movement mapping | Unit: stick -> world delta uses the camera basis and gamepad speed |
| 3 | No farming | Unit: achievement and challenge trackers ignore LOCAL_VERSUS outcomes |
| 4 | Every mode switch handles it | Compiles with exhaustive switches; rematch/poll paths don't touch networking |
| 5 | Visible | Screenshots: the versus match with the side camera + hint, and the P2-wins screen |

## Technical approach
- `GameplayAppState.Mode.LOCAL_VERSUS`; the opponent side is driven by `SecondPlayerInput`
  (keyboard + gamepad) instead of the AI; P2 power-ups go through the sim's opponent side.
- `app/SecondPlayerInput` wraps `KeyboardStick` (pure) + joystick mapping.
- `PaddleShockApp.startLocalVersus()/endLocalVersusMatch()`; `MatchEndState` local-versus result.

## Risks / open questions
- Not tested with real gamepads or a real Remote Play session (no hardware here) - the gamepad
  path reuses the existing, previously-working joystick mapping code.

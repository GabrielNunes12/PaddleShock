# Hazard arenas

- Status: approved (requested 2026-09-23)

## Problem / goal
The four arenas each change one physics number. Add two arenas whose hazard is something you
play around, not just a constant.

## Scope
### Included
- **Pinball Palace** - two round bumpers, one in each half at z = +/-3, sliding side to side
  in mirror image (always symmetric, so neither side is favored). A low ball rebounds off them
  (reflected, slightly faster); a high ball sails over.
- **Glacier Rink** - icy paddles: a paddle's movement lags and slides on (momentum with grip),
  for both players and the AI. Hitting a rail stops the slide in that direction.
- Both arenas in the level picker, with themed decor from the existing props.
- World Tour grows to 18 opponents (3 per new arena, inserted before Space Station so The Void
  stays the final boss). Unlocking also treats an already-beaten opponent as unlocked, so a
  ladder change can never lock someone out of an opponent they've beaten.
- Networked: the bumper offset rides in the snapshot as an optional trailing float; ice is
  host-side physics and needs nothing extra.

### Explicitly out of scope
- New 3D models (bumpers are simple generated cylinders), moving nets, per-arena music.

## Requirements
| # | Requirement | Acceptance criterion |
|---|---|---|
| 1 | Bumper rebound | Unit: a low ball heading into a bumper reflects off it and speeds up (capped); a high ball passes |
| 2 | Bumpers symmetric | Unit: the two bumpers are always mirror images |
| 3 | No bumper on other arenas | Existing sim tests unchanged |
| 4 | Ice momentum | Unit: movement ramps up over several ticks; releasing input keeps sliding then stops; rail stops the slide |
| 5 | Tour still consistent | WorldTourTest (3 per arena, bosses last, ids valid) passes with 18 |
| 6 | Visible | Screenshots: level picker with 6 arenas; Pinball bumpers in a live match; Glacier arena |

## Technical approach
- `data/LevelHazard` enum (NONE, BUMPERS, ICE) on `LevelDefinition`.
- `sim/Bumpers` (pure: positions over time, collision response) and `sim/IceSlide` (pure:
  momentum per paddle); `MatchSimulation` takes the hazard.
- `GameplayAppState`: bumper geometry following the sim or the snapshot.

## Risks / open questions
- Bumper speed/size and ice grip are unplaytested guesses.

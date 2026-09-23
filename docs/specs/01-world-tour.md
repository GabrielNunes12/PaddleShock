# World Tour (single-player campaign)

- Status: approved (requested 2026-09-23)
- Related: content roadmap items 1-8 in this folder

## Problem / goal
Solo play today is one quick match vs a generic AI at 3 difficulties - no structure, no goals.
World Tour gives a solo player a ladder of named opponents to work through, arena by arena.

## Scope
### Included
- 12 opponents across the 4 existing arenas (3 each; the 3rd is the arena's boss).
- Each opponent has its own AI tuning (speed, tracking sloppiness, power-up kit + rate),
  paddle look/size, match length (first to 5/7/10) and a one-time first-win credit reward.
- Linear unlocks: opponent N unlocks when N-1 is beaten. Progress persists in the profile.
- A WORLD TOUR screen (main-menu CTA under PLAY VS AI) showing the ladder, locked/beaten
  state, and a detail panel with START.
- Match end in tour mode: a notice with the first-win reward, and RETRY / WORLD TOUR actions.

### Explicitly out of scope
- Story/cutscenes, voice, opponent portraits (text + color swatch only).
- Changing the quick-match AI behavior (it must stay byte-for-byte the same tuning).
- Achievements (item 2 builds on this).

## Requirements
| # | Requirement | Acceptance criterion |
|---|---|---|
| 1 | Unlock order | Unit test: only opponent 0 unlocked on a fresh profile; beating N unlocks N+1 and nothing further |
| 2 | First-win reward once | Unit test: first win pays the opponent's reward, a replay win pays the normal random reward |
| 3 | Per-opponent match length | Unit test: a sim built with winScore 5 ends at 5 |
| 4 | Quick match AI unchanged | Unit test: AiBrain with zero sloppiness moves exactly as the old inline code (clamped step toward ball) |
| 5 | Old saves load | Unit test: a profile JSON without the new field deserializes with no tour progress |
| 6 | Screen usable at 1280x720 | Real rendered screenshot of the tour screen and a tour match |
| 7 | PT-BR strings exist | Existing-style properties keys in both files (checked by test) |

## Technical approach
- `tour/TourOpponent` (record) + `tour/WorldTour` (the ordered catalog + pure unlock/reward
  rules) - plain logic, unit-tested.
- `sim/AiBrain` - the AI's paddle-step decision pulled out of `GameplayAppState` so both quick
  match and tour use one tested code path; adds an optional sinusoidal tracking error.
- `MatchSimulation` gains an optional `winScore` (defaults to `GameConstants.WIN_SCORE`).
- `PlayerProfile.tourBeatenIds` (null-safe for old saves, no schema bump needed).
- `GameplayAppState(TourOpponent)` constructor: forces the arena, opponent look and AI profile.
- `ui/WorldTourState` + `Navigator.showWorldTour()`; `PaddleShockApp.endMatch` routes tour
  results through `WorldTour` rules.

## How this will be verified
- Unit tests for WorldTour rules, AiBrain, winScore, profile compatibility.
- Real rendered screenshots (1280x720) of the tour screen and an in-progress tour match.
- Full existing suite stays green.

## Risks / open questions
- Difficulty tuning numbers are guesses; they need playtesting.

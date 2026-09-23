# Daily and weekly challenges

- Status: approved (requested 2026-09-23)

## Problem / goal
Nothing gives a player a reason to come back tomorrow. Add one daily and one weekly challenge
with a credit reward, tracked automatically from match results.

## Scope
### Included
- One daily challenge (resets at local midnight) and one weekly challenge (ISO week, resets
  Monday), both chosen deterministically from the date - no server, same challenge for everyone
  on the same day.
- Challenge kinds: win N matches; win N on a given arena; win N by 5+ points; score N points
  (wins or losses); beat the HARD quick-match AI N times; win N World Tour matches. None needs
  gear the player might not own, so a brand-new player can always complete them.
- Progress counts from every completed participant match (like achievements). Completing one
  pays its credits immediately, once, with a toast.
- Shown on the main menu under the campaign button: description, progress and reward.

### Explicitly out of scope
- Rerolling, streaks, a history of past challenges, server-side validation.

## Requirements
| # | Requirement | Acceptance criterion |
|---|---|---|
| 1 | Deterministic | Unit: same date -> same challenge; daily changes across days; weekly stable within a week |
| 2 | Always achievable | Unit: every generated challenge over a year references valid arenas and never needs owned gear |
| 3 | Progress rules | Unit per kind, including losses (points count; wins don't) |
| 4 | Pays once | Unit: completion pays the reward once; further progress pays nothing |
| 5 | Rollover | Unit: a new day's challenge starts at 0 and the old progress is pruned |
| 6 | Visible | Screenshot of the main-menu challenge panel and a completion toast |

## Technical approach
- `challenges/Challenge`, `ChallengeKind`, `ChallengeGenerator` (date -> challenges),
  `ChallengeTracker` (progress + payout over `PlayerProfile`).
- `MatchOutcome` is reused (it already has kind, result, scores, arena, AI difficulty).
- Profile: `challengeProgress` (map) + `challengesClaimed` (set), pruned to the current ids.

## Risks / open questions
- Uses the local clock, so a player can change the date to farm challenges. Accepted for a
  local, single-player reward (same stance as local saves in tasks.md).

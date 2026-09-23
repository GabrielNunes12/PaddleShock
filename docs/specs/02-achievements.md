# Achievements (local + Steam)

- Status: approved (requested 2026-09-23)

## Problem / goal
Players have no goals beyond the next win, and the Steam store page shows no achievements.
Add 15 achievements that track locally (so offline/non-Steam play counts) and mirror to Steam.

## Scope
### Included
- 15 achievements: match feats (first win, shutout, 1-point win, beat HARD AI, first online
  win), milestones (25/100 wins, 100 power-ups used, win on every arena), World Tour (first
  boss, tour complete), ranked (reach Gold, reach Diamond), collection (all gear, all power-ups).
- Local tracking in the profile (unlocked ids + the counters they need); retroactive check on
  startup for profile-state achievements (e.g. an existing save that already owns everything).
- Steam mirroring via `SteamUserStats.setAchievement` + `storeStats`; every local unlock is
  re-sent on startup, so unlocks earned offline reach Steam later. No-op without Steam.
- An on-screen toast when one unlocks, from any screen.
- An ACHIEVEMENTS screen (from PROFILE) listing all 15 with locked/unlocked and progress.
- `docs/steam-achievements.md`: the API names/titles/descriptions to enter in Steamworks, and
  generated 256px unlocked/locked icons for each.

### Explicitly out of scope
- Steam stats (numeric stats in Steamworks) - counters stay local.
- Hidden achievements, global unlock percentages.

## Requirements
| # | Requirement | Acceptance criterion |
|---|---|---|
| 1 | Each achievement's condition | Unit tests per condition, including the "just below" boundary for counters |
| 2 | Unlock once | Unit test: a second qualifying event returns nothing new |
| 3 | Retroactive | Unit test: profile-state check unlocks what an existing profile already qualifies for |
| 4 | Offline safe | SteamManager calls are no-ops when Steam is unavailable (never throw) |
| 5 | Old saves load | Unit test: profile JSON without the new fields has no achievements and zero counters |
| 6 | Visible | Real screenshots: the toast over the match-end screen, and the ACHIEVEMENTS screen |

## Technical approach
- `achievements/Achievement` (enum; Steam API name = `ACH_<NAME>`), `achievements/MatchOutcome`
  (record describing a finished match), `achievements/AchievementTracker` (pure rules).
- Profile fields: `unlockedAchievements`, `totalWins`, `powerUpsUsed`, `levelsWonOn`.
- `PaddleShockApp`: evaluates on every participant match end (the existing
  `recordMatchHistory` hook), on ranked results, after store purchases and at startup; routes
  new unlocks to Steam + toast.
- `ui/ToastState` (always-on overlay), `ui/AchievementsState` (list screen).

## Risks / open questions
- The achievements must be created in the Steamworks partner site with these exact API names
  before they show up on Steam; with the dev App ID 480 the Steam calls simply fail quietly.

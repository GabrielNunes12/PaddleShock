# PaddleShock

A 3D paddle/air-hockey game built with Java + jMonkeyEngine, targeting a Steam release.

## Current state: prototype

Main menu → play vs. a basic AI paddle, pause mid-match, adjust options, or visit the store.
No leaderboards/Steamworks/real multiplayer yet.

Controls:
- Mouse: move paddle (left/right and forward/back within your half)
- Esc: pause (only available vs. AI)

Features in this pass:
- Store: buy/equip paddles, tables, and balls, each with different stats (speed/size/bounciness)
- 3 power-ups that spawn on the table: Paddle Grow, Speed Boost, Slow Opponent
- Options: mouse sensitivity, brightness, sound volume (persisted, not yet wired to audio), video quality (antialiasing, requires restart to apply)
- Save data: `~/.paddleshock/profile.json` (currency + owned/equipped items) and `~/.paddleshock/settings.json`

## Running

```
./gradlew run
```

(Windows: `gradlew.bat run`)

## Planned features

- Power-ups for paddles
- Store: buy paddles, tables, balls with different stats
- Leaderboards (Steamworks)
- Multiplayer (Steamworks Networking)
- AI opponent difficulty levels
- Steam release

## Stack

- Java 21
- jMonkeyEngine 3.6
- Gradle
- Steamworks (via `steamworks4j`) — planned, not yet integrated

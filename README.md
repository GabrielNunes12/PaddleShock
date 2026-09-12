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
- Save data: `~/.paddleshock/profile.dat` (currency + owned/equipped items) and `~/.paddleshock/settings.dat`, AES-GCM encrypted (see Save data security below)

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

## Save data security

`profile.dat` and `settings.dat` are encrypted (AES-256-GCM) rather than plain JSON, so casual
editing (e.g. bumping currency in a text editor) isn't possible - a tampered or corrupted file
fails its authentication check and the game silently resets to defaults instead of crashing or
accepting the edit.

Honest limit: the decryption key is embedded in the client (`SaveCrypto.java`), like any
client-side save encryption. This stops casual editing, not a determined player with the jar in
hand and time to extract the key - there's no way around that for local single-player save data
short of server-side validation (relevant later for leaderboards/Steamworks, not for local saves).

## Third-party assets

- Textures: CC0 (public domain) from [ambientCG](https://ambientcg.com) — see `src/main/resources/Textures/CREDITS.md`
- Paddle, ball, and decorative models: CC-BY, require attribution — see `src/main/resources/Models/CREDITS.md`. Before shipping, add an in-game credits screen showing:
  > Table Tennis Paddle by jeremy [CC-BY] via Poly Pizza
  > Ping Pong table by burunduk [CC-BY] via Poly Pizza
  > Trophy by jeremy [CC-BY] via Poly Pizza
  > Tennis ball by Poly by Google [CC-BY] via Poly Pizza
  > beach ball by the_normalgamer [CC-BY] via Poly Pizza
  > Bollard by J-Toastie [CC-BY] via Poly Pizza

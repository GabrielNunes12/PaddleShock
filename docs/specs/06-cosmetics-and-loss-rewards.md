# Cosmetics and loss rewards

- Status: approved (requested 2026-09-23)

## Problem / goal
After ~110 wins a player owns everything and credits stop meaning anything; a loss pays
nothing at all. Add look-only items to keep earning toward, and a small consolation reward.

## Scope
### Included
- Three cosmetic categories, bought and equipped like gear, with a free "none" default each:
  - **Paddle skins** (5): recolor/re-texture your own paddle; stats come from the paddle item.
  - **Ball trails** (4): a fading trail behind the ball in your view.
  - **Score celebrations** (3): a particle burst at the goal when you score.
- Cosmetics affect only the local player's view - no gameplay, ranked or network change.
- Store tabs SKINS / TRAILS / CELEBRATIONS; cards tagged COSMETIC.
- Loss reward: a flat 4 credits for a completed loss (any mode that pays for a win), shown on
  the defeat screen. Quitting mid-match still pays nothing.

### Explicitly out of scope
- Showing your cosmetics to the other player online (would need a protocol change).
- Loot boxes, real-money purchases, rarity tiers.

## Requirements
| # | Requirement | Acceptance criterion |
|---|---|---|
| 1 | Buy/equip cosmetics | Unit: purchase deducts, equip only if owned, defaults owned; old saves get the defaults |
| 2 | Catalog sane | Unit: each category's first item is free, ids unique, prices > 0 otherwise |
| 3 | Loss reward | Unit: reward rule pays LOSS_REWARD on a loss, win range on a win |
| 4 | Trail | Unit: trail buffer keeps the last N positions, oldest faintest |
| 5 | Visible | Screenshots: store cosmetics tabs; a skinned paddle + trail in a match; a celebration burst; defeat screen reward |

## Technical approach
- `data/CosmeticDefinition` + `Catalog.SKINS/TRAILS/CELEBRATIONS`; `PlayerProfile` categories
  "skin", "trail", "celebration" (null-safe sets for old saves).
- `app/MatchRewards` (pure reward rule) used by every match-end path.
- `entities/BallTrail` (ring buffer + fading spheres), `entities/CelebrationBurst` (simple
  particle burst, no textures needed).

## Risks / open questions
- Prices are guesses; the trail/burst are placeholder-quality visuals made from primitives.

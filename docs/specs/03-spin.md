# Ball spin

- Status: approved (requested 2026-09-23)

## Problem / goal
Shot skill today is only "where on the paddle / which way you're moving when it arrives".
Spin adds a learnable skill: swipe sideways through the ball and it curves in flight.

## Scope
### Included
- A paddle's sideways speed at contact becomes spin (clamped), in the swipe direction.
- Spin bends the ball's sideways velocity while it's in the air and decays over ~1-2 s.
- A rail bounce reverses and halves spin (so it can't pin the ball against a rail).
- Every serve starts with zero spin. A hit with a still paddle clears spin.
- Visible: the ball model spins about its vertical axis in proportion to spin, on every client.
- Networked: spin is appended to the snapshot as an optional trailing field (older peers ignore
  it; a new client reading an old host's snapshot treats spin as 0).

### Explicitly out of scope
- Topspin/backspin (vertical), spin affecting table-bounce height.
- Replays showing spin (the replay buffer stays position/velocity only).

## Requirements
| # | Requirement | Acceptance criterion |
|---|---|---|
| 1 | Still paddle = old behavior | Existing MatchSimulation tests unchanged and green; a still-paddle hit leaves spin 0 |
| 2 | Swipe direction curves the ball that way | Unit test: +x swipe -> positive spin -> velocity.x grows over subsequent ticks |
| 3 | Clamp | Unit test: an extreme swipe yields exactly MAX_SPIN |
| 4 | Decay and serve reset | Unit tests |
| 5 | Wire compatibility | NetProtocol tests: round-trip with spin; old-length packet decodes with spin 0 |
| 6 | Visible | Real rendered frames of a curving ball |

## Technical approach
- `Paddle` records its actual (post-clamp) x displacement per `moveDelta`.
- `MatchSimulation.tick` turns that into a speed (disp / tpf) and hands it to
  `Ball.bounceOffPaddle` via `Ball.setSpin(Ball.spinFromPaddleSpeed(v))`.
- `Ball.update` applies `spin * SPIN_CURVE_ACCEL` to velocity.x and decays spin.
- `SnapshotMessage` gains `ballSpin` with a compatibility constructor for the old field list.

## Risks / open questions
- Tuning (spin per paddle speed, curve strength, decay) is a first guess; mouse speeds vary a
  lot with sensitivity, so this needs playtesting.
- The AI moves while tracking, so it puts incidental spin on returns too - intended as variety,
  but it could make HARD/boss opponents feel harsher.

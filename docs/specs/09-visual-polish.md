# Visual polish: rendering, UI consistency, impact feedback

- Status: approved (requested 2026-09-23). 3D models and textures are out of scope - the
  owner is sourcing those separately.

## Problem / goal
The game reads as cheap: flat unshadowed lighting (the ball looks like it floats), inconsistent UI
(Lemur's default teal gradients leaking through, top-left button text, text shadows), and no
feedback when things happen in a rally. Fix the presentation without touching gameplay rules.

## Scope
### 1. Rendering (scaled by the existing Video Quality setting)
| Quality | Shadows | Ambient occlusion | Bloom (glowing objects) | Ball blob shadow |
|---|---|---|---|---|
| LOW | - | - | - | yes |
| MEDIUM | 1024 | - | - | - |
| HIGH | 2048 | yes | yes | - |
| ULTRA | 4096 | yes | yes | - |
Plus on every quality: a gradient sky per arena, a cool rim light for shape definition, and
glow colors on emissive things (shield bars, bumper rings, trails, bursts). MSAA stays as today.

### 2. UI consistency
- One global override of the Lemur "glass" style: no default gradient backgrounds on
  containers, flat buttons with centered text, no text shadows, a warm hover color.
- A short fade-in whenever the visible screen changes.

### 3. Impact feedback
- Camera shake on hard hits, rail bounces and points (trauma model; toggle in Options).
- Hit-stop: a few hundredths of a second freeze on fast paddle hits (local simulations only).
- Sparks at paddle contacts, rail bounces and bumper hits; the ball "pops" on contact.
- Hit sounds vary in pitch and volume with ball speed (plus slight randomness).
- A brief screen flash + score pop when a point is scored (green yours, red theirs).

### Explicitly out of scope
- New models/textures, table markings, music/ambience changes, screen-space reflections.

## Requirements
| # | Requirement | Acceptance criterion |
|---|---|---|
| 1 | Effects scale with quality | Unit: profile per VideoQuality; never more effects at a lower quality |
| 2 | Performance | Measured frame time per quality at 1280x720 with vsync off; LOW no slower than before |
| 3 | Shake/hit-stop rules | Unit: trauma decays to 0, capped; hit-stop freezes then releases; disabled shake = 0 |
| 4 | Audio mapping | Unit: pitch/volume rise with speed, clamped |
| 5 | UI regressions | Before/after screenshots of every main screen |
| 6 | Visible | Before/after screenshots of three arenas; a hit spark + shake frame |

## Technical approach
- `settings/GraphicsProfile` (pure VideoQuality -> effect flags), `app/SceneEffects` (builds the
  FilterPostProcessor + shadow modes), `entities/SkyGradient`, `entities/BlobShadow`.
- `ui/UiStyle` applies the style overrides once at startup; `ui/FadeState` watches which UI
  screens are enabled and fades in on a change.
- `app/CameraShake`, `app/HitStop`, `audio/HitSoundMapping` (pure); `entities/ParticleBurst`
  generalizes the celebration burst for sparks.

## Risks / open questions
- Shadow/SSAO/bloom tuning is subjective; screenshots are the judge.
- Filters cost fill-rate on low-end GPUs; LOW keeps the old cheap path.

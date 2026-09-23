# Options sliders: real drag sliders for volume/sensitivity

- Status: approved (requested 2026-09-23).

## Problem / goal
SOUND VOLUME, MUSIC VOLUME and MOUSE SENS. on the OPTIONS screen are `-`/value/`+` stepper rows
(`addStepperRow`). The owner wants these three shaped like a real slider - `<--0-->` - a track
with a draggable knob plus the existing fine-step arrow buttons, so a player can drag straight to
a value instead of clicking `+`/`-` repeatedly. Brightness, video quality, fullscreen, screen
shake and resolution are discrete/enum values and stay as steppers.

## Scope
- Replace the 3 stepper rows above with `com.simsilica.lemur.Slider` (backed by
  `DefaultRangedValueModel`), keeping each value's existing range and step from `GameSettings`/the
  old `adjustX` methods:
  - Sound volume: 0.0-1.0, step 0.1, displayed as a rounded percent ("80%").
  - Music volume: 0.0-1.0, step 0.1, displayed as a rounded percent ("60%").
  - Mouse sensitivity: 0.1-5.0, step 0.1, displayed as a one-decimal multiplier ("1.0x").
- Live effects: sound volume applies to the next SFX (already reads `GameSettings` per-play);
  music volume calls `AudioManager.refreshMusicVolume()` on every change, not just on save;
  mouse sensitivity is read from settings by gameplay as before.
- Debounced persistence: dragging must not call `saveGameSettings()` (a file write) every frame.
  Save at most once ~0.5s after the value stops changing, and always flush a pending save when the
  screen closes (`onDisable`).
- Restyle Lemur's default "glass" slider look (teal gradient track/thumb) to match the game's flat
  panel/orange theme, matching the existing stepper buttons' look.
- Everything still fits inside the existing 288px-wide Options cards at 1280x720.

### Explicitly out of scope
- Brightness/video quality/fullscreen/screen shake/resolution rows (stay steppers).
- Any change to the actual audio mixing or mouse-look code that consumes these settings.

## Requirements
| # | Requirement | Acceptance criterion |
|---|---|---|
| 1 | Same ranges/steps as before | Unit: slider models built with GameSettings' existing min/max; matches old adjust deltas |
| 2 | Live apply | Dragging changes `GameSettings` immediately; music drag calls `refreshMusicVolume()` every change (harness printout) |
| 3 | Debounced save | Unit: `SaveDebounce` only fires once ~0.5s after the last change; `onDisable` always flushes a pending one |
| 4 | Value formatting | Unit: `OptionsSliderFormat.percent`/`multiplier` round/format correctly at boundaries |
| 5 | No teal gradient | Screenshot: Options screen shows panel/orange-themed sliders, no default Lemur teal |
| 6 | Fits at 1280x720 | Screenshot: AUDIO and CONTROLS cards show no clipping/overlap at the game's minimum resolution |
| 7 | i18n | Screenshot in PT-BR: labels still translated; slider itself has no new hardcoded text needing translation |

## Technical approach
- `ui/SaveDebounce` (pure, mirrors `ScreenFade`'s shape): `markChanged()`/`update(tpf)` returns
  true once 0.5s of no further changes has elapsed; `flush()` forces it and reports whether there
  was a pending change, for `onDisable`.
- `ui/OptionsSliderFormat` (pure, static): `percent(double)` and `multiplier(double)` formatting
  helpers, `Locale.ROOT` to avoid a locale-dependent decimal separator.
- `OptionsState` builds 3 `Slider`s with `DefaultRangedValueModel`s seeded from `GameSettings`,
  keeps a `VersionedReference<Double>` per slider (`RangedValueModel.createReference()`), and polls
  them in `update(float tpf)` (dragging doesn't fire click commands, so this can't be event-driven
  like the stepper buttons). On a version change: write the new value into `GameSettings`
  immediately, refresh the value label, live-refresh music if it's the music slider, and mark the
  shared `SaveDebounce` dirty. `SaveDebounce.update(tpf)` triggers the actual `saveGameSettings()`;
  `onDisable` calls `flush()` and saves if it reports a pending change.
- `ui/UiStyle` adds explicit "glass" style overrides for the `slider`, `slider.range`,
  `slider.thumb.button`, `slider.left.button` and `slider.right.button` selectors, using
  `Theme.PANEL`/`PANEL_HOVER`/`ORANGE`/`TEXT` instead of Lemur's default teal gradient.

## Risks / open questions
- Row width is tight (256px of content per card); the slider track is deliberately short/compact
  to leave room for the name and value labels - verified visually, not just by not throwing.
- `BaseAppState.update(tpf)` only runs while the state is enabled, which is exactly the screen's
  lifetime, so no separate "screen closed while dirty" case beyond `onDisable`.

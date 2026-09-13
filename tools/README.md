# Multiplayer QA test harness

`mp-test-harness.ps1` launches TWO independent PaddleShock game clients on
one Windows machine and drives each one separately (focus, click, key
press, screenshot) via Win32 UI automation. It exists ahead of real
multiplayer landing in the codebase so the harness itself is built and
verified before there's a multiplayer feature to point it at.

## Usage

```powershell
cd I:\PaddleShock
. .\tools\mp-test-harness.ps1     # dot-source to load the functions

Build-PaddleShock                  # builds via `gradlew installDist` (once, or after code changes)
$i = Start-PaddleShockInstances    # launches both instances; returns instance info for both

Focus-PaddleShockInstance  -Instances $i -Instance 1
Click-PaddleShockInstance  -Instances $i -Instance 1 -XRel 0.5 -YRel 0.485   # relative to window client rect
Send-PaddleShockKey        -Instances $i -Instance 2 -Key ENTER
Save-PaddleShockScreenshot -Instances $i -Instance 1 -Path C:\temp\p1.png
Save-PaddleShockScreenshot -Instances $i -Instance 2 -Path C:\temp\p2.png

Stop-PaddleShockInstances -Instances $i
```

Or run the whole self-test in one shot:

```powershell
powershell -File .\tools\mp-test-harness.ps1 -RunSmokeTest
```

Every helper takes `-Instances <array> -Instance <1|2>` — there is no
global "current instance" state, so both clients can be driven
independently from the same script/session.

## How two instances get launched without Gradle contention

`./gradlew run` was not used to launch the game: Gradle's daemon/task
locking does not support two concurrent `run` invocations cleanly on one
project. Instead:

1. `Build-PaddleShock` runs `gradlew installDist` once, which produces a
   plain installed app under `build/install/PaddleShock/` (a `lib/` full of
   jars, an app launcher).
2. Each instance is launched by invoking `java.exe -classpath <all jars in
   lib/> com.paddleshock.Main` directly — two ordinary, fully independent
   JVM processes, with no Gradle involved in the launch at all, so there's
   no daemon/lock contention between them.

The generated `PaddleShock.bat` launcher is deliberately **not** used to
start the instances — see "cmd.exe console window overlap" below.

## Telling the two windows apart

Both windows have the exact same title, `"PaddleShock"` — title matching
alone cannot distinguish them. Two mechanisms are used together:

- Each instance is launched with a unique `-Dharness.marker=<guid>` JVM
  system property, and the harness resolves the *real* PID of that
  specific java.exe afterwards by matching the marker in the process's
  command line (via `Get-CimInstance Win32_Process`), rather than trusting
  `Start-Process`'s own returned PID.

  **This matters more than it sounds like it should**: in live testing,
  `Start-Process -WindowStyle Hidden -PassThru` on this machine sometimes
  returned a PID that did not correspond to any real, running process —
  the actual java.exe ran under a different PID entirely. Trusting the
  naive `$proc.Id` led to `Stop-Process` "succeeding" against a PID that
  was never the game, leaving the real instance running — an orphaned
  `java.exe`, i.e. exactly the failure mode this harness has to avoid. The
  marker-based resolution sidesteps the problem instead of depending on
  that return value being reliable.

- Each resolved PID's own `MainWindowHandle` is then used for all further
  window operations for that instance (`GetWindowRect`, `SetWindowPos`,
  etc.) — never window-title lookup.

## Per-instance automation helpers

- `Focus-PaddleShockInstance` — bring a specific instance's window to the
  foreground/top of the z-order.
- `Click-PaddleShockInstance` — left-click inside a specific instance's
  window, at a position given as a 0.0–1.0 fraction of its client rect (so
  callers don't need actual screen pixel coordinates).
- `Send-PaddleShockKey` — press+release a key in a specific instance
  (`ENTER`, `ESCAPE`, `SPACE`, `TAB`, arrow keys, or any single character).
- `Save-PaddleShockScreenshot` — capture a specific instance's window to a
  PNG file via GDI `CopyFromScreen`.

All of the above take `-Instances <array-from-Start-PaddleShockInstances>
-Instance <1|2>`.

## Cleanup

`Stop-PaddleShockInstances -Instances $i`:

- Kills each tracked instance's full process tree (recursive, in case a
  future launch shape spawns children — the direct-java.exe launch this
  harness uses today normally has none).
- Restores the original `settings.dat` backup (see below).
- Runs a belt-and-suspenders sweep afterwards: any `java.exe` whose command
  line still mentions `PaddleShock` gets force-killed too, in case a PID
  was mistracked (see the PID-reliability note above) or a process was
  still in its shutdown grace period. Verified live: after a full
  launch → drive → cleanup cycle, `Get-Process java.exe` shows nothing but
  the ambient Gradle daemon(s) — zero orphaned game processes.

## Save file handling

The game's save files live at `~/.paddleshock/profile.dat` and
`~/.paddleshock/settings.dat` (AES-GCM encrypted; see the main
`README.md`). `Start-PaddleShockInstances` calls
`Backup-PaddleShockSettings`, which backs up any existing `settings.dat`
and deletes it so the next launch uses in-code defaults — windowed,
1280×720 — since **exclusive fullscreen breaks GDI screen capture**
(`CopyFromScreen` cannot read an exclusive-fullscreen swapchain). Cleanup
restores the original file via `Restore-PaddleShockSettings`.

**Both instances read/write the same save path.** This is a known,
accepted limitation for this first version of the harness: since there's
no multiplayer feature yet to actually exercise cross-save interactions,
and both instances only ever reach the main menu / local match setup
during automated smoke testing, sharing `~/.paddleshock/` is fine for now.
If a future test needs the two instances to have independent
currency/loadout state, `SaveManager`'s save path
(`src/main/java/com/paddleshock/data/SaveManager.java`) would need an
instance-specific override (e.g. reading a system property for the save
dir) — not implemented here since it wasn't a straightforward addition and
wasn't needed for the harness's own self-test.

## Known issue: GPU/focus contention on a real interactive desktop, and the fix

Launching two full LWJGL3/OpenGL windows at once on one machine, and then
automating them from a background-spawned script, ran into a real
contention issue during live verification — not hypothetical:

**What happened:** on first pass, screenshots taken via plain
`SetForegroundWindow` + `CopyFromScreen` sometimes captured a completely
different, unrelated window (e.g. a browser tab, an editor) instead of the
game — even though the harness's own "is this blank?" heuristic reported
the screenshot as valid, because that *other* window's content isn't blank
either. Root cause: this machine has a real, active interactive desktop
session — `quser` confirms an active console session, and the actual
foreground window at test time was the user's own browser. Windows'
foreground-lock rules mean a plain `SetForegroundWindow` call from a
background-spawned process (like a script launched by an automation tool)
can silently fail to actually change focus or z-order when some other
window currently owns the foreground. `CopyFromScreen` just grabs whatever
is actually composited on screen at those coordinates, so if another
window is sitting on top of the target at capture time, you silently get a
screenshot of the wrong thing.

A second, related issue: launching the game via the generated
`PaddleShock.bat` (rather than invoking `java.exe` directly) opens a
`cmd.exe` console window that renders **on top of** the game window at the
same screen position — visually confirmed, the console's OpenAL/OpenGL
log text was overlapping the main menu in a screenshot.

**Mitigations applied (both verified live, see below):**

1. **Launch `java.exe` directly**, not the `.bat` — no console-host window
   is created to begin with, so there's nothing to overlap the game
   window. (This is also just a cleaner launch shape generally.)
2. **Pulse `SetWindowPos(..., HWND_TOPMOST, ...)`** around both clicks and
   screenshots. Z-order/topmost is not subject to the same foreground-lock
   restriction as `SetForegroundWindow`, so it reliably puts the target
   window on top for the capture/click regardless of what else currently
   has focus, without permanently pinning it on top of the user's other
   work (it's set back to `HWND_NOTOPMOST` immediately after).
3. **The "phantom Alt key" trick** around `SetForegroundWindow` calls
   (tap `VK_MENU` down/up immediately before/after) — the standard Win32
   workaround that lets a background process's foreground-window request
   actually succeed in most cases, used for real keyboard-focus changes
   (`Send-PaddleShockKey`, `Focus-PaddleShockInstance`).
4. **Side-by-side window placement** (`SetWindowPos` to `x=0` / `x=1280`)
   plus a short (~4s) stagger between the two launches — reduces the
   chance of GPU/driver init contention from two GL contexts initializing
   at the exact same instant, and makes it visually obvious which window
   is which.

**Verified:** after applying (1)–(3), repeated live runs captured both
instances' actual main menu content correctly and consistently — see the
screenshots taken during verification (both showed "PADDLE SHOCK" with the
Play vs AI / Multiplayer / Store / Settings / Quit menu, not a blank frame
and not an unrelated window). A per-instance click test (clicking
"MULTIPLAYER" only in instance 2) also confirmed the two instances are
genuinely independent: instance 2 navigated to the Match Setup screen
while instance 1 remained untouched at the main menu.

**For future real multiplayer testing:** anyone extending this harness to
drive actual gameplay (not just menu navigation) should keep using the
topmost-pulse pattern for both clicks and screenshots — don't assume
`SetForegroundWindow` alone is sufficient on a real desktop with other
active applications, since that's the default environment this will run
in, not a clean dedicated CI box.

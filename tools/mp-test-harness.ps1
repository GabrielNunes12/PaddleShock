<#
.SYNOPSIS
    Multiplayer QA test harness for PaddleShock: launches TWO independent game
    client instances, drives each one separately via Win32 UI automation
    (window focus, mouse, keyboard, screenshots), and cleans both up reliably.

    Written ahead of real multiplayer landing in the codebase, so the harness
    exists and is verified before there's a feature to point it at.

.USAGE
    # From a PowerShell prompt, from anywhere (paths below are repo-relative):
    cd I:\PaddleShock
    . .\tools\mp-test-harness.ps1        # dot-source to load functions into your session

    Build-PaddleShock                     # one-time (or after code changes) build
    $procs = Start-PaddleShockInstances   # launches both instances, returns info for both

    Focus-PaddleShockInstance   -Instance 1
    Click-PaddleShockInstance   -Instance 1 -XRel 0.5 -YRel 0.5
    Send-PaddleShockKey         -Instance 2 -Key ENTER
    Save-PaddleShockScreenshot  -Instance 1 -Path C:\temp\p1.png
    Save-PaddleShockScreenshot  -Instance 2 -Path C:\temp\p2.png

    Stop-PaddleShockInstances $procs

    # Or just run the whole smoke test end to end:
    .\tools\mp-test-harness.ps1 -RunSmokeTest

.NOTES
    - Both instances launch as plain `java.exe -classpath ... com.paddleshock.Main`
      processes, using the jars from the Gradle `installDist` output
      (build/install/PaddleShock/lib), NOT via `./gradlew run` — Gradle's own
      daemon/task locking does not support two concurrent `run` invocations
      cleanly, but two independent installed-app JVMs have no such
      contention. java.exe is launched directly rather than via the
      generated PaddleShock.bat: the .bat's own cmd.exe console window was
      observed overlapping the actual game window on screen (see
      tools/README.md) — launching java.exe with a hidden console avoids
      that without affecting the separate GLFW game window.
    - Both windows share the title "PaddleShock". Instances are distinguished
      by PID at launch time: each instance's process is started individually,
      and its HWND is resolved via that specific PID's MainWindowHandle, not
      by title matching.
    - Both instances read/write the SAME save file
      (~/.paddleshock/profile.dat, ~/.paddleshock/settings.dat) since that's
      the game's only save path today. For a v1 harness this is treated as
      acceptable (see tools/README.md) — no isolated save path is set up.
    - Screenshots require windowed (non-exclusive-fullscreen) mode; GDI
      CopyFromScreen cannot capture an exclusive-fullscreen swapchain. The
      harness backs up settings.dat, forces windowed mode by deleting it
      (defaults to windowed 1280x720) before launch, and restores the
      original file during cleanup.
    - GPU/focus contention: see tools/README.md "Known issue" section. The
      mitigation used here is explicit non-overlapping window placement
      (side-by-side) plus a short stagger between the two launches.
#>

param(
    [switch]$RunSmokeTest
)

$ErrorActionPreference = 'Stop'

# ----------------------------------------------------------------------------
# Paths / constants
# ----------------------------------------------------------------------------

$Script:RepoRoot     = Split-Path -Parent $PSScriptRoot
$Script:InstallDir   = Join-Path $RepoRoot 'build\install\PaddleShock'
$Script:LauncherBat  = Join-Path $InstallDir 'bin\PaddleShock.bat'
$Script:LibDir       = Join-Path $InstallDir 'lib'
$Script:WindowTitle  = 'PaddleShock'
$Script:MainClass    = 'com.paddleshock.Main'

$Script:SaveDir        = Join-Path $env:USERPROFILE '.paddleshock'
$Script:SettingsFile   = Join-Path $SaveDir 'settings.dat'
$Script:SettingsBackup = Join-Path $SaveDir 'settings.dat.harness-backup'

# ----------------------------------------------------------------------------
# Win32 P/Invoke plumbing (window handling, input, screenshots)
# ----------------------------------------------------------------------------

if (-not ("PaddleShockHarness.Win32" -as [type])) {
    Add-Type -Namespace PaddleShockHarness -Name Win32 -MemberDefinition @'
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr hWndInsertAfter, int X, int Y, int cx, int cy, uint uFlags);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int X, int Y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, UIntPtr dwExtraInfo);
    [DllImport("user32.dll")] public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll", CharSet = CharSet.Auto)] public static extern int GetWindowText(IntPtr hWnd, System.Text.StringBuilder lpString, int nMaxCount);

    public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
'@
}

# Constants for mouse_event / keybd_event / SetWindowPos flags
Set-Variable -Scope Script -Name MOUSEEVENTF_LEFTDOWN  -Value 0x0002
Set-Variable -Scope Script -Name MOUSEEVENTF_LEFTUP    -Value 0x0004
Set-Variable -Scope Script -Name KEYEVENTF_KEYUP       -Value 0x0002
Set-Variable -Scope Script -Name SWP_NOZORDER          -Value 0x0004
Set-Variable -Scope Script -Name SWP_NOMOVE            -Value 0x0002
Set-Variable -Scope Script -Name SWP_NOSIZE            -Value 0x0001
Set-Variable -Scope Script -Name SW_RESTORE            -Value 9
Set-Variable -Scope Script -Name HWND_TOPMOST          -Value ([IntPtr](-1))
Set-Variable -Scope Script -Name HWND_NOTOPMOST        -Value ([IntPtr](-2))

# Minimal virtual-key map for the keys QA is likely to need; extend as needed.
$Script:VkMap = @{
    'ENTER'  = 0x0D
    'ESCAPE' = 0x1B
    'ESC'    = 0x1B
    'SPACE'  = 0x20
    'TAB'    = 0x09
    'UP'     = 0x26
    'DOWN'   = 0x28
    'LEFT'   = 0x25
    'RIGHT'  = 0x27
}

# ----------------------------------------------------------------------------
# Build
# ----------------------------------------------------------------------------

function Build-PaddleShock {
    <# Builds the app once via Gradle's `installDist`, producing an
       independently-launchable app under build/install/PaddleShock.
       This is what lets us start two JVMs directly with `java`/the
       generated .bat launcher, sidestepping Gradle daemon contention
       from running `./gradlew run` twice concurrently. #>
    [CmdletBinding()]
    param()

    Push-Location $RepoRoot
    try {
        Write-Host "Building PaddleShock (gradlew installDist)..."
        & "$RepoRoot\gradlew.bat" installDist --console=plain
        if ($LASTEXITCODE -ne 0) {
            throw "gradlew installDist failed with exit code $LASTEXITCODE"
        }
        if (-not (Test-Path $LauncherBat)) {
            throw "Expected launcher not found at $LauncherBat after build"
        }
        Write-Host "Build OK: $LauncherBat"
    } finally {
        Pop-Location
    }
}

# ----------------------------------------------------------------------------
# Save-file handling (force windowed mode so screenshots work)
# ----------------------------------------------------------------------------

function Backup-PaddleShockSettings {
    <# Backs up settings.dat (if present) and removes it so the next launch
       uses in-code defaults, which are windowed 1280x720 - required because
       exclusive fullscreen breaks GDI screen capture. Call
       Restore-PaddleShockSettings afterwards to put the player's real
       settings file back. #>
    New-Item -ItemType Directory -Path $SaveDir -Force | Out-Null
    if (Test-Path $SettingsFile) {
        Copy-Item $SettingsFile $SettingsBackup -Force
        Remove-Item $SettingsFile -Force
        Write-Host "Backed up existing settings.dat, removed it to force windowed-mode defaults."
    } else {
        Write-Host "No existing settings.dat found; nothing to back up (defaults are windowed already)."
    }
}

function Restore-PaddleShockSettings {
    <# Restores the player's original settings.dat saved by
       Backup-PaddleShockSettings. Safe to call even if there was nothing
       to restore. #>
    if (Test-Path $SettingsBackup) {
        Copy-Item $SettingsBackup $SettingsFile -Force
        Remove-Item $SettingsBackup -Force
        Write-Host "Restored original settings.dat."
    } else {
        # There was no pre-existing file; remove whatever the harness run wrote
        # so we don't leave a stray forced-windowed settings.dat behind.
        if (Test-Path $SettingsFile) {
            Remove-Item $SettingsFile -Force
        }
    }
}

# ----------------------------------------------------------------------------
# Launch / window resolution
# ----------------------------------------------------------------------------

function Start-PaddleShockInstances {
    <# Launches TWO independent PaddleShock JVM processes and waits for each
       one's own window handle to appear. Returns an array of two objects:
       @{ Instance=1; Process=<Process>; Pid=<int>; Hwnd=<IntPtr> }, same for 2.

       Windows are positioned side-by-side (not overlapping) to avoid the
       GPU/focus contention documented in tools/README.md, and launches are
       staggered a few seconds apart for the same reason. #>
    [CmdletBinding()]
    param(
        [int]$TimeoutSeconds = 60
    )

    if (-not (Test-Path $LibDir)) {
        throw "PaddleShock is not built yet. Run Build-PaddleShock first (expected $LibDir)."
    }

    Backup-PaddleShockSettings

    # Launch java.exe directly rather than the generated PaddleShock.bat.
    # The .bat runs under a cmd.exe console host, and that console window
    # was found (during live verification) to open on top of / overlapping
    # the actual GLFW game window at the same screen position - a real
    # window-stacking contention issue, not just a hypothetical one. Going
    # straight to java.exe with a hidden console avoids it entirely; the
    # GLFW game window is a separate native window and is unaffected by the
    # console's visibility.
    $javaExe = (Get-Command java.exe).Source
    $classpath = (Get-ChildItem -Path $LibDir -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'

    $instances = @()

    for ($i = 1; $i -le 2; $i++) {
        Write-Host "Launching instance $i..."

        # IMPORTANT: Start-Process's own -PassThru Process.Id was found (during
        # live verification) to be UNRELIABLE for this launch shape on Windows
        # PowerShell 5.1 - the PID it returns does not always correspond to any
        # real, running process (ShellExecute-path launches can hand back a PID
        # for a transient handle rather than the actual java.exe). Trusting it
        # led to Stop-ProcessTree silently killing nothing and leaving the real
        # game process running - i.e. exactly the orphaned-java.exe failure
        # mode this harness exists to avoid.
        #
        # So instead: tag each launch with a unique marker system property and
        # find the real java.exe afterwards by matching that marker in its
        # command line via WMI/CIM - this is authoritative regardless of what
        # Start-Process's return value says.
        $marker = "paddleshock-harness-instance-$i-$([guid]::NewGuid().ToString('N'))"
        Start-Process -FilePath $javaExe `
            -ArgumentList @("-Dharness.marker=$marker", '-classpath', "`"$classpath`"", $MainClass) `
            -WorkingDirectory $InstallDir `
            -WindowStyle Hidden `
            | Out-Null

        $realPid = Wait-ForMarkedProcess -Marker $marker -TimeoutSeconds $TimeoutSeconds
        $hwnd = Wait-ForInstanceWindow -TargetPid $realPid -TimeoutSeconds $TimeoutSeconds

        $instances += [pscustomobject]@{
            Instance = $i
            Pid      = $realPid
            Hwnd     = $hwnd
        }

        # Position side by side so neither window fully occludes the other,
        # and so it's visually obvious (and screenshot-verifiable) which is which.
        $x = if ($i -eq 1) { 0 } else { 1280 }
        [PaddleShockHarness.Win32]::SetWindowPos($hwnd, [IntPtr]::Zero, $x, 0, 1280, 720, $SWP_NOZORDER) | Out-Null
        [PaddleShockHarness.Win32]::ShowWindow($hwnd, $SW_RESTORE) | Out-Null

        if ($i -eq 1) {
            # Stagger launches: starting both LWJGL3/GL windows at literally
            # the same instant is what tends to trigger GPU/driver init
            # contention (see tools/README.md). A few seconds between
            # launches avoided it in testing.
            Start-Sleep -Seconds 4
        }
    }

    Write-Host "Both instances launched: PID $($instances[0].Pid) (instance 1), PID $($instances[1].Pid) (instance 2)."
    return $instances
}

function Wait-ForMarkedProcess {
    <# Polls WMI/CIM for a java.exe process whose command line contains the
       given unique marker (set via -Dharness.marker=...), and returns its
       real PID. See the comment in Start-PaddleShockInstances for why this
       is used instead of trusting Start-Process's own returned PID. #>
    param(
        [Parameter(Mandatory)][string]$Marker,
        [int]$TimeoutSeconds = 30
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $match = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -and $_.CommandLine.Contains($Marker) } |
            Select-Object -First 1
        if ($match) {
            return [int]$match.ProcessId
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Timed out waiting for a java.exe process tagged with marker '$Marker' after $TimeoutSeconds s."
}

function Wait-ForInstanceWindow {
    <# Polls a specific PID (not window title!) until it exposes a
       MainWindowHandle, so instance 1 and instance 2 - which share the
       exact same window title "PaddleShock" - are never confused with
       each other. #>
    param(
        [Parameter(Mandatory)][int]$TargetPid,
        [int]$TimeoutSeconds = 60
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        # The launcher .bat spawns a java.exe child; MainWindowHandle can end
        # up on either the .bat's process object or the child java.exe once
        # Windows re-parents/refreshes, so check the java.exe descendant too.
        $candidates = @(Get-Process -Id $TargetPid -ErrorAction SilentlyContinue)
        $candidates += Get-CimInstance Win32_Process -Filter "ParentProcessId=$TargetPid" -ErrorAction SilentlyContinue |
            ForEach-Object { Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue }

        foreach ($c in $candidates) {
            if ($c -and $c.MainWindowHandle -ne [IntPtr]::Zero) {
                $title = New-Object System.Text.StringBuilder 256
                [PaddleShockHarness.Win32]::GetWindowText($c.MainWindowHandle, $title, 256) | Out-Null
                if ($title.ToString() -like "*$WindowTitle*") {
                    return $c.MainWindowHandle
                }
            }
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Timed out waiting for PaddleShock window for PID $TargetPid (or its child) after $TimeoutSeconds s."
}

function Get-PaddleShockInstance {
    <# Looks up one instance's record (by Instance number 1/2) from the
       array returned by Start-PaddleShockInstances. #>
    param(
        [Parameter(Mandatory)][array]$Instances,
        [Parameter(Mandatory)][int]$Instance
    )
    $found = $Instances | Where-Object { $_.Instance -eq $Instance }
    if (-not $found) { throw "No instance numbered $Instance in the given instance set." }
    return $found
}

# ----------------------------------------------------------------------------
# Per-instance input/automation helpers (parameterized by instance, no globals)
# ----------------------------------------------------------------------------

function Focus-PaddleShockInstance {
    <# Brings the given instance's window to the foreground and to the top
       of the z-order.

       IMPORTANT (see tools/README.md "GPU/focus contention" section): on a
       real interactive desktop, Windows' foreground-lock rules mean a
       background-spawned automation process's plain SetForegroundWindow
       call can silently fail to actually change focus/z-order if the user
       (or some other app) currently owns the foreground - this was observed
       live during harness verification (a screenshot captured an unrelated
       foreground browser window instead of the game). Two standard
       workarounds are combined here: (1) a momentary HWND_TOPMOST pulse,
       which is a z-order change and is NOT subject to the foreground-lock
       restriction, so it reliably makes the target window paintable/
       clickable even when focus can't be stolen; (2) the "phantom ALT key"
       trick (tap Alt immediately around the SetForegroundWindow call),
       which is what actually lets SetForegroundWindow succeed for a
       background process in most cases. #>
    param(
        [Parameter(Mandatory)][array]$Instances,
        [Parameter(Mandatory)][int]$Instance
    )
    $inst = Get-PaddleShockInstance -Instances $Instances -Instance $Instance
    [PaddleShockHarness.Win32]::ShowWindow($inst.Hwnd, $SW_RESTORE) | Out-Null

    [PaddleShockHarness.Win32]::SetWindowPos($inst.Hwnd, $HWND_TOPMOST, 0, 0, 0, 0, ($SWP_NOMOVE -bor $SWP_NOSIZE)) | Out-Null

    [PaddleShockHarness.Win32]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero)          # VK_MENU (Alt) down
    [PaddleShockHarness.Win32]::SetForegroundWindow($inst.Hwnd) | Out-Null
    [PaddleShockHarness.Win32]::keybd_event(0x12, 0, $KEYEVENTF_KEYUP, [UIntPtr]::Zero)  # Alt up

    [PaddleShockHarness.Win32]::SetWindowPos($inst.Hwnd, $HWND_NOTOPMOST, 0, 0, 0, 0, ($SWP_NOMOVE -bor $SWP_NOSIZE)) | Out-Null

    Start-Sleep -Milliseconds 200
}

function Get-PaddleShockWindowRect {
    param([Parameter(Mandatory)][IntPtr]$Hwnd)
    $rect = New-Object PaddleShockHarness.Win32+RECT
    [PaddleShockHarness.Win32]::GetWindowRect($Hwnd, [ref]$rect) | Out-Null
    return $rect
}

function Click-PaddleShockInstance {
    <# Left-clicks inside the given instance's window at a position
       expressed as a fraction of the window's client rect (0.0-1.0 on each
       axis), so callers don't need to know the actual screen pixel
       coordinates. #>
    param(
        [Parameter(Mandatory)][array]$Instances,
        [Parameter(Mandatory)][int]$Instance,
        [Parameter(Mandatory)][double]$XRel,
        [Parameter(Mandatory)][double]$YRel
    )
    $inst = Get-PaddleShockInstance -Instances $Instances -Instance $Instance
    Focus-PaddleShockInstance -Instances $Instances -Instance $Instance

    $rect = Get-PaddleShockWindowRect -Hwnd $inst.Hwnd
    $x = [int]($rect.Left + ($rect.Right - $rect.Left) * $XRel)
    $y = [int]($rect.Top  + ($rect.Bottom - $rect.Top) * $YRel)

    [PaddleShockHarness.Win32]::SetCursorPos($x, $y) | Out-Null
    Start-Sleep -Milliseconds 50
    [PaddleShockHarness.Win32]::mouse_event($MOUSEEVENTF_LEFTDOWN, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 50
    [PaddleShockHarness.Win32]::mouse_event($MOUSEEVENTF_LEFTUP, 0, 0, 0, [UIntPtr]::Zero)
}

function Send-PaddleShockKey {
    <# Sends a single key press+release to the given instance. $Key is
       looked up in $Script:VkMap (ENTER, ESCAPE, SPACE, TAB, UP/DOWN/LEFT/
       RIGHT); a single character (e.g. "A") is also accepted. #>
    param(
        [Parameter(Mandatory)][array]$Instances,
        [Parameter(Mandatory)][int]$Instance,
        [Parameter(Mandatory)][string]$Key
    )
    Focus-PaddleShockInstance -Instances $Instances -Instance $Instance

    $vk = $Script:VkMap[$Key.ToUpperInvariant()]
    if (-not $vk) {
        if ($Key.Length -eq 1) {
            $vk = [byte][char]$Key.ToUpperInvariant()[0]
        } else {
            throw "Unknown key '$Key'; add it to `$Script:VkMap or pass a single character."
        }
    }

    [PaddleShockHarness.Win32]::keybd_event([byte]$vk, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 50
    [PaddleShockHarness.Win32]::keybd_event([byte]$vk, 0, $KEYEVENTF_KEYUP, [UIntPtr]::Zero)
}

function Save-PaddleShockScreenshot {
    <# Captures the given instance's window region (via GDI
       CopyFromScreen, since it's a plain window, not exclusive fullscreen)
       and writes it to $Path as a PNG.

       Pulses the window to HWND_TOPMOST for the duration of the capture.
       CopyFromScreen grabs whatever is actually composited on screen at
       those coordinates, so if some other window (the user's browser, an
       editor, etc.) happens to sit on top of the game window at capture
       time, you silently get a screenshot of THAT instead - this was
       observed live during harness verification on a real interactive
       desktop. Topmost is a z-order property, not a focus/input property,
       so it is not subject to the foreground-lock restriction that can
       make plain SetForegroundWindow calls fail for a background process
       (see Focus-PaddleShockInstance). #>
    param(
        [Parameter(Mandatory)][array]$Instances,
        [Parameter(Mandatory)][int]$Instance,
        [Parameter(Mandatory)][string]$Path
    )
    Add-Type -AssemblyName System.Drawing

    $inst = Get-PaddleShockInstance -Instances $Instances -Instance $Instance
    [PaddleShockHarness.Win32]::ShowWindow($inst.Hwnd, $SW_RESTORE) | Out-Null
    [PaddleShockHarness.Win32]::SetWindowPos($inst.Hwnd, $HWND_TOPMOST, 0, 0, 0, 0, ($SWP_NOMOVE -bor $SWP_NOSIZE)) | Out-Null
    Start-Sleep -Milliseconds 200

    $rect = Get-PaddleShockWindowRect -Hwnd $inst.Hwnd
    $width  = $rect.Right - $rect.Left
    $height = $rect.Bottom - $rect.Top
    if ($width -le 0 -or $height -le 0) {
        [PaddleShockHarness.Win32]::SetWindowPos($inst.Hwnd, $HWND_NOTOPMOST, 0, 0, 0, 0, ($SWP_NOMOVE -bor $SWP_NOSIZE)) | Out-Null
        throw "Instance $Instance window rect looks invalid ($width x $height); is the window minimized?"
    }

    $bitmap = New-Object System.Drawing.Bitmap $width, $height
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.CopyFromScreen($rect.Left, $rect.Top, 0, 0, (New-Object System.Drawing.Size $width, $height))

    [PaddleShockHarness.Win32]::SetWindowPos($inst.Hwnd, $HWND_NOTOPMOST, 0, 0, 0, 0, ($SWP_NOMOVE -bor $SWP_NOSIZE)) | Out-Null

    $dir = Split-Path -Parent $Path
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)

    $graphics.Dispose()
    $bitmap.Dispose()

    Write-Host "Saved instance $Instance screenshot to $Path"
}

# ----------------------------------------------------------------------------
# Cleanup
# ----------------------------------------------------------------------------

function Stop-PaddleShockInstances {
    <# Reliably kills both instances, including any child processes the
       launcher .bat or JVM may have spawned, and restores the original
       settings.dat. Safe to call multiple times / with partial instance
       sets. #>
    param(
        [array]$Instances
    )

    foreach ($inst in $Instances) {
        Stop-ProcessTree -RootPid $inst.Pid
    }

    # Give JVM shutdown a moment to actually complete (Stop-Process signals
    # termination but exit isn't always instantaneous) before the sweep
    # below checks what's still alive.
    Start-Sleep -Milliseconds 750

    Restore-PaddleShockSettings

    # Belt-and-suspenders: in case any java.exe from this install dir
    # survived under a PID we didn't track (e.g. re-parented to a
    # different launcher, or a launch whose PID resolution raced with this
    # cleanup), sweep by command line match too.
    $stray = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine -like "*PaddleShock*" }
    foreach ($s in $stray) {
        Write-Host "Killing stray java.exe (PID $($s.ProcessId)) matched by command line."
        Stop-Process -Id $s.ProcessId -Force -ErrorAction SilentlyContinue
    }
}

function Stop-ProcessTree {
    <# Kills a process and all of its descendants. The harness launches
       java.exe directly (not via the .bat, which would add a cmd.exe
       layer), so there normally are no children to speak of - but this
       stays recursive as defense in depth against any future launch
       shape that does spawn child processes. #>
    param([Parameter(Mandatory)][int]$RootPid)

    $children = Get-CimInstance Win32_Process -Filter "ParentProcessId=$RootPid" -ErrorAction SilentlyContinue
    foreach ($child in $children) {
        Stop-ProcessTree -RootPid $child.ProcessId
    }
    Stop-Process -Id $RootPid -Force -ErrorAction SilentlyContinue
}

# ----------------------------------------------------------------------------
# Smoke test: proves the harness itself works end to end (no MP feature yet)
# ----------------------------------------------------------------------------

function Invoke-PaddleShockHarnessSmokeTest {
    <# Launches both instances, screenshots each at the main menu, and
       verifies both screenshots are valid non-blank PNGs. This is the
       harness's own self-test, since there's no multiplayer feature yet
       to exercise. #>
    [CmdletBinding()]
    param(
        [string]$OutDir = (Join-Path $env:TEMP 'paddleshock-mp-harness-smoketest')
    )

    New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
    $instances = $null
    try {
        Build-PaddleShock
        $instances = Start-PaddleShockInstances

        # Give both instances time to finish the splash screen and reach the
        # main menu before screenshotting.
        Start-Sleep -Seconds 6

        $shot1 = Join-Path $OutDir 'instance1.png'
        $shot2 = Join-Path $OutDir 'instance2.png'
        Save-PaddleShockScreenshot -Instances $instances -Instance 1 -Path $shot1
        Save-PaddleShockScreenshot -Instances $instances -Instance 2 -Path $shot2

        $ok1 = Test-ScreenshotNonBlank -Path $shot1
        $ok2 = Test-ScreenshotNonBlank -Path $shot2

        Write-Host "Instance 1 screenshot valid & non-blank: $ok1 ($shot1)"
        Write-Host "Instance 2 screenshot valid & non-blank: $ok2 ($shot2)"

        if (-not ($ok1 -and $ok2)) {
            throw "Smoke test FAILED: one or both screenshots were blank/invalid."
        }
        Write-Host "Smoke test PASSED."
    } finally {
        if ($instances) {
            Stop-PaddleShockInstances -Instances $instances
        }
    }
}

function Test-ScreenshotNonBlank {
    <# Loads a PNG and checks it decodes and isn't a single solid color
       (a cheap but effective blank/black-window detector). #>
    param([Parameter(Mandatory)][string]$Path)

    Add-Type -AssemblyName System.Drawing
    if (-not (Test-Path $Path)) { return $false }

    try {
        $bmp = New-Object System.Drawing.Bitmap $Path
    } catch {
        return $false
    }

    $first = $bmp.GetPixel(0, 0)
    $distinctFound = $false
    # Sample a grid rather than every pixel for speed.
    for ($x = 0; $x -lt $bmp.Width -and -not $distinctFound; $x += [Math]::Max(1, [int]($bmp.Width / 20))) {
        for ($y = 0; $y -lt $bmp.Height -and -not $distinctFound; $y += [Math]::Max(1, [int]($bmp.Height / 20))) {
            $p = $bmp.GetPixel($x, $y)
            if ($p.R -ne $first.R -or $p.G -ne $first.G -or $p.B -ne $first.B) {
                $distinctFound = $true
            }
        }
    }
    $bmp.Dispose()
    return $distinctFound
}

# ----------------------------------------------------------------------------
# Entry point when invoked directly (not dot-sourced) with -RunSmokeTest
# ----------------------------------------------------------------------------

if ($RunSmokeTest) {
    Invoke-PaddleShockHarnessSmokeTest
}

# Launcher Artwork Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement the tightly coupled RED/GREEN unit. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Package the user-provided bell/toll-gate artwork as a safe adaptive launcher icon and generate an APK for manual installation.

**Architecture:** Retain the supplied source unchanged; a development PowerShell generator removes exterior white padding and emits transparent adaptive foregrounds, legacy fallbacks and previews. The manifest references a single adaptive-capable mipmap for both regular and round icons. Tests inspect the packaged icon via PackageManager.

**Tech Stack:** Existing Android/Kotlin/AndroidX tests, XML adaptive icons, PNG resources, Windows PowerShell/System.Drawing; no new dependency.

---

**Status:** specification and plan independently reviewed and approved after
clarifying the physical-device boundary and exact input file. User authorized
implementation directly. Local baseline accepted with `--no-daemon`.
Delegated implementation and independent spec review completed. Controller full
verification: 112 JVM / 150 instrumented, no failures/errors/skips, lint 0 errors.
Independent final quality review approved with no findings. Artifact signature,
input/resources/packaging and hash verified; owned AVD shut down.
Task complete, APK handed off for manual installation; no physical-device action.

## Context and ownership

- Spec: `docs/superpowers/specs/2026-11-17-launcher-artwork-design.md`.
- User explicitly approved proceeding to implementation without another question.
  Continue delegated implementation, controller review and later manual install.
- Baseline `f304a6c`, clean `feat/monitor-history-and-settings`. Reuse the approved
  single-workspace Windows/Gradle workflow; do not create competing builds,
  switch main, merge or push.
- One implementer owns app/resources/test/tool/source/previews, not documentation
  except `artwork/launcher/README.md`; controller owns review and final evidence.
- Use @test-driven-development and @verification-before-completion. Have the
  specification and plan reviewed before dispatching implementation.

## File map

- Copy the exact user-provided source
  `C:\Users\Victor\Downloads\ChatGPT Image Nov 17, 2026, 05_33_13 PM.png`
  (WSL `/mnt/c/Users/Victor/Downloads/ChatGPT Image Nov 17, 2026, 05_33_13 PM.png`)
  unchanged to `artwork/launcher/source.png`;
  add `artwork/launcher/README.md` with source hash and regeneration command.
- Add `tools/generate-launcher-icons.ps1`, deterministic development-only
  conversion using System.Drawing (optionally embedded C# for flood fill).
- Modify only application attributes in `app/src/main/AndroidManifest.xml`:
  `android:icon="@mipmap/ic_launcher"` and `android:roundIcon="@mipmap/ic_launcher"`.
- Add `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`,
  `app/src/main/res/values/launcher_colors.xml` and density PNG resources under
  `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/`.
- Add `app/src/androidTest/java/br/com/c6tag/diagnostico/ui/LauncherIconTest.kt`.
- Add generated previews to `docs/testing/screenshots/launcher-circle.png` and
  `launcher-rounded-square.png`. Do not change existing screenshots.
- Controller adds `docs/testing/launcher-artwork-results.md` and current-status
  links only; previous installed-artifact hashes remain historical.

## Task 1 — tightly coupled delegated RED/GREEN

- [x] Start owned read-only `emulator-5572` only after confirming that exact port
  is not already live. Use established SDK AVD `Medium_Phone_API_36.1` with
  `-read-only -no-snapshot-save -no-snapshot-load -no-window -no-audio`.
  Use one `wait-for-device`, not polling/sleeps or physical-device queries.
- [x] Add runtime instrumentation using `InstrumentationRegistry` target context:

```kotlin
val context = InstrumentationRegistry.getInstrumentation().targetContext
val info = context.packageManager.getApplicationInfo(context.packageName, 0)
assertTrue("A launcher icon must be explicitly configured", info.icon != 0)
val icon = context.packageManager.getApplicationIcon(context.packageName)
assertTrue(icon is AdaptiveIconDrawable)
```

  Separate tests check foreground/background non-null and bitmap foreground
  transparent outer padding/nonempty art with retained opaque white details.
  Use runtime IDs rather than referencing resources not yet created, so RED
  compiles. Do not assert unrelated default-app-icon rendering.
- [x] Run focused `LauncherIconTest` with the command below before production
  edits; record the expected missing configured icon/AdaptiveIconDrawable failure.
- [x] Copy source without altering original; verify the approved SHA-256.
- [x] Implement generator with default source `artwork/launcher/source.png`.
  Validate PNG dimensions before writing, dispose native graphics objects and
  fail clearly on invalid input. No package installation or network calls.
  Flood-fill only near-white pixels connected to the image exterior to alpha0;
  preserve disconnected interior whites. Keep all original remaining pixels
  unchanged apart from deterministic high-quality scaling.
- [x] Center artwork on a transparent 108 dp adaptive layer over solid navy
  (`#052B57` starting background). Start near a 64–66 dp artwork box and use
  inspected masks to choose margins that preserve bell/alert/gate. Avoid
  blindly scaling the full source to 108 dp and clipping the subject.
- [x] Generate five densities: legacy icon sizes 48/72/96/144/192 pixels;
  adaptive foreground canvases 108/162/216/324/432 pixels. Compose the legacy
  icon from the central 72 dp visible region on navy. Generate circle and
  rounded-square previews from the same visible region, not a separate layout.
  Keep PNG alpha and proper resampling; do not write outside the named files.
- [x] Add adaptive XML and color:

```xml
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
```

```xml
<resources>
    <color name="launcher_background">#052B57</color>
</resources>
```

- [x] Set manifest regular/round icon attributes. Leave notification drawable,
  dependencies, app version and all Kotlin production logic untouched.
- [x] Run generator, then focused tests GREEN without weakening icon/layer/alpha
  assertions. Inspect both previews; improve margins if clipping is present.
  Self-review and hand off exclusive Gradle/ADB ownership, keeping owned AVD
  running for controller checks. Do not commit yet.

## Task 2 — controller review, verification and delivery

- [x] Independently review actual diff; request spec compliance then quality
  reviews, routing any fixes back to implementer. No controller production edits.
- [x] Run complete suite with the owned emulator explicitly selected; inspect
  XML counts/failures/skips and lint (previous baseline 112 JVM / 146 Android,
  lint 0 errors / 8 warnings; MissingApplicationIcon should disappear).
- [x] Audit packaged icon references, foreground/color resources, unchanged
  notification icon, source hash and absence of unrelated application changes.
  Check signed debug APK with `apksigner`, size/SHA-256 and original package.
- [x] Record evidence/limitations and regeneration command. Commit local changes
  with Conventional Commits, checking diff whitespace before each commit.
- [x] Shut down only owned AVD and report APK path. Do not install on S23 or ask
  for another decision; user already chose later manual installation.

## Commands

Focused RED/GREEN:

```powershell
powershell.exe -NoProfile -Command '$env:ANDROID_SERIAL="emulator-5572"; & ".\tools\gradle.ps1" ":app:connectedDebugAndroidTest" "-Pandroid.testInstrumentationRunnerArguments.class=br.com.c6tag.diagnostico.ui.LauncherIconTest" "--no-daemon" "--console=plain"; exit $LASTEXITCODE'
```

Generate resources:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/generate-launcher-icons.ps1
```

Complete verification:

```powershell
powershell.exe -NoProfile -Command '$env:ANDROID_SERIAL="emulator-5572"; & ".\tools\gradle.ps1" ":app:testDebugUnitTest" ":app:assembleDebug" ":app:assembleDebugAndroidTest" ":app:lintDebug" ":app:connectedDebugAndroidTest" "--no-daemon" "--console=plain"; exit $LASTEXITCODE'
```

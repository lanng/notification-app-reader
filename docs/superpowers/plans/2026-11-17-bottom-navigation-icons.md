# Bottom Navigation Icons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the three tiny Unicode navigation glyphs with readable 28 dp vector icons without changing navigation or financial behavior.

**Architecture:** Retain `MonitorRoot` and its Material navigation items. Add three local vector drawables and render them with decorative `Icon` content, relying on the existing label and Material tint. Instrumented Compose tests measure rendered geometry and semantics directly.

**Tech Stack:** Existing Kotlin/Compose Material 3, XML vector drawables, AndroidX Compose UI tests; no dependency changes.

---

**Status:** specification approved by user; implementation plan independently
reviewed and approved after clarifying minimum 48 dp touch-target assertions.
Baseline JVM rerun and local build/lint accepted before production edits.

## Authorization and evidence path

- User approved the written spec at
  `docs/superpowers/specs/2026-11-17-bottom-navigation-icons-design.md`.
- User subsequently requested delegated implementation, controller review, and
  completion through APK generation without further questions. Installation
  will be manual after their return; do not connect to or install on the S23.
- Continue in the existing clean feature branch
  `feat/monitor-history-and-settings`, preserving the approved single-workspace
  Windows/Gradle workflow; do not switch `main`, merge, push or create a competing
  implementation workspace for this bounded change.
- Use @test-driven-development, @verification-planning and
  @verification-before-completion. Before implementation, have this plan reviewed.
- Tasks 1 and 2 form a single tightly coupled RED/GREEN implementation unit:
  delegate them to one implementer, then run independent spec and quality reviews.
  The controller handles documentation, evidence checks and final verification,
  not production edits.
- Root cause: `MonitorRoot.kt:87` uses `Text` for font-dependent Unicode glyphs.
- Decisive evidence: real Compose rendered icon bounds, tab touch bounds,
  label-only accessibility semantics and successful tab selection.
- Synthetic screenshots establish visual appearance only; they cannot validate
  a new S23 installation, physical rotation or real TalkBack speech.
- All instrumented commands must set `ANDROID_SERIAL=emulator-5572` for an
  explicitly created owned AVD. No physical-device command or permission change.

## File map

- Modify `app/src/main/java/br/com/c6tag/diagnostico/ui/monitor/MonitorRoot.kt`:
  replace only icon contents, add `painterResource` import.
- Create `app/src/main/res/drawable/ic_monitor_overview.xml`,
  `ic_monitor_history.xml`, `ic_monitor_settings.xml`: dashboard/history/gear.
- Create `app/src/androidTest/java/br/com/c6tag/diagnostico/ui/monitor/MonitorBottomNavigationTest.kt`:
  synthetic configuration, geometry, semantics, routing and screenshot checks.
- Update spec approval status and `docs/testing/monitor-local-results.md`.
- Add only new synthetic images
  `docs/testing/screenshots/synthetic-navigation-{light,dark}.png`.
  Do not replace previous release screenshots or save the user's screenshot.

## Task 1 — RED: rendered icon regression

- [ ] Create the owned read-only AVD with the established command below, using
  the Windows SDK; fail rather than reusing a pre-existing port.
- [ ] Add a Compose test that renders `MonitorRoot` with synthetic state and
  no-op actions. Use `mutableStateOf` for theme, font scale and forced size,
  and `DeviceConfigurationOverride` with `LocalDensity` captured via `SideEffect`.
- [ ] For both light/dark, font scales 1/2, and sizes 390×844/740×360 dp,
  check all three `tab-icon-{overview,history,settings}` nodes in the unmerged
  tree with `assertWidthIsEqualTo(28.dp)` and `assertHeightIsEqualTo(28.dp)`.
  Check each corresponding tab touch dimension is **at least** 48 dp:
  `touch.width + 0.001f >= 48f * localDensity` and
  `touch.height + 0.001f >= 48f * localDensity`. This is not equality;
  larger targets are valid. The tolerance is only physical-pixel float noise.
- [ ] Check icons have no `ContentDescription` or click semantics; each merged
  tab has its visible label, a click action and exactly one matching actionable
  label node. Assert old Unicode texts absent. Click each tab and assert its
  selected state and destination; return to overview for each configuration.
- [ ] Run only `MonitorBottomNavigationTest` with the focused command below.
  Expected RED: missing icon node, not a compiler/environment error. Record it
  before adding production resources or modifying `MonitorRoot`.

## Task 2 — GREEN: vector replacement

- [ ] Add three XML `<vector>` resources with intrinsic `28dp` width/height,
  `24`×`24` viewport and simple black filled paths (tinted by `Icon`).
  Overview uses four tiles spanning coordinates 3–21; history uses a circular
  return arrow and clock hands; settings uses a gear with a circular cutout.
  Keep path extent substantial (about 18–21 viewport units), not a padded
  miniature drawing. No notification-icon or dependency changes.
- [ ] Replace `icon = { Text(listOf("◉", "≡", "⚙")[index]) }` with:

```kotlin
icon = {
    Icon(
        painter = painterResource(listOf(
            R.drawable.ic_monitor_overview,
            R.drawable.ic_monitor_history,
            R.drawable.ic_monitor_settings,
        )[index]),
        contentDescription = null,
        modifier = Modifier.size(28.dp).testTag(
            listOf("tab-icon-overview", "tab-icon-history", "tab-icon-settings")[index],
        ),
    )
},
```

- [ ] Repeat the focused command; expected GREEN for all configuration assertions.
  Diagnose any failure without weakening the accepted 28 dp/48 dp criteria.
- [ ] Add a synthetic screenshot test for both themes at normal portrait size;
  capture to app test files using `captureToImage().asAndroidBitmap()` and PNG
  output, following `MonitorFinalLayoutTest.save`.
- [ ] Run the focused class again, extract only those two synthetic PNGs from
  the owned AVD using `exec-out run-as ... cat files/<name>` and inspect them.

## Task 3 — Regression, review and checkpoint

- [ ] Run JVM/debug/APKtest/lint and the complete instrumented suite with the
  owned AVD explicitly selected. Parse XML totals/failures/skips and lint issues.
- [ ] Request an independent read-only spec/quality review of this narrow diff.
  Fix concrete findings and rerun affected checks.
- [ ] Calculate APK size and SHA-256. Record RED/GREEN, full results, review,
  screenshot scope and the fact that the S23 still has the prior artifact.
  No physical update has been authorized.
- [ ] Commit source/tests/images and documentation with English Conventional
  Commits, checking `git diff --check` first. Keep the feature branch.
- [ ] Shut down only the owned emulator. Report outcome, APK path and ask for
  separate consent if the user wants installation.

## Runnable commands

Owned AVD (shell background job; no polling/sleep):

```text
"/mnt/c/Users/Victor/AppData/Local/Android/Sdk/emulator/emulator.exe" -avd Medium_Phone_API_36.1 -port 5572 -read-only -no-snapshot-save -no-snapshot-load -no-window -no-audio
```

Before starting, check only `adb -s emulator-5572 get-state`; if it exists, do
not take ownership. After starting use one `adb -s emulator-5572 wait-for-device`
command; Android test-runner startup provides further readiness checks.

Focused RED/GREEN:

```powershell
powershell.exe -NoProfile -Command '$env:ANDROID_SERIAL="emulator-5572"; & ".\tools\gradle.ps1" ":app:connectedDebugAndroidTest" "-Pandroid.testInstrumentationRunnerArguments.class=br.com.c6tag.diagnostico.ui.monitor.MonitorBottomNavigationTest" "--console=plain"; exit $LASTEXITCODE'
```

Complete verification:

```powershell
powershell.exe -NoProfile -Command '$env:ANDROID_SERIAL="emulator-5572"; & ".\tools\gradle.ps1" ":app:testDebugUnitTest" ":app:assembleDebug" ":app:assembleDebugAndroidTest" ":app:lintDebug" ":app:connectedDebugAndroidTest" "--console=plain"; exit $LASTEXITCODE'
```

The connected runner may uninstall test/application APKs on the owned AVD.
If needed, install both built APKs only there and run the focused class directly
with `am instrument -w -e class ...` to regenerate screenshots before extraction.
Do not interpret that additional focused run as the complete suite.

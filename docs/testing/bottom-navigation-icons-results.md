# Bottom navigation icons — implementation evidence

## Status

Implementation complete. Independent spec/quality reviews and controller
verification approved. APK generated for the user's later manual installation.
The user approved the written design and requested completion through APK
generation for later manual installation. No physical-device operation is
authorized or needed for this follow-up.

## Scope

- Three local vector icons rendered in 28 × 28 dp boxes.
- Existing labels, Material colors, selection/navigation and minimum 48 dp tab
  touch targets preserved.
- No financial, capture, alert, database, permission, dependency or version changes.
- Source baseline: `d17ae84`; feature branch
  `feat/monitor-history-and-settings`.

Design: `../superpowers/specs/2026-11-17-bottom-navigation-icons-design.md`.
Plan: `../superpowers/plans/2026-11-17-bottom-navigation-icons.md`.

## Baseline evidence

Controller ran local JVM/build/APKtest/lint successfully before implementation.
The explicit JVM `--rerun-tasks` command also completed successfully.
Previously recorded baseline: 112 JVM / 144 instrumented tests, lint
0 errors / 8 prior warnings. No instrumented baseline was rerun on the S23.

## Evidence path

- Implementer owns focused RED/GREEN and new synthetic screenshot production.
- Independent read-only spec review precedes independent quality review.
- Controller owns the complete suite, report counts, artifact hash and diff audit.
- Android test target must be the newly owned `emulator-5572`, never a physical
  installation. No concurrent Gradle/ADB commands between implementer/controller.
- Rendered icon box measurements and semantics checks are distinct from
  screenshot inspection; none proves new physical S23 behavior.
- Keep previous release screenshots and the user's private screenshot untouched.

## Installed artifact boundary

The S23's previously installed and validated artifact has SHA-256
`1eaacb831de78a2fb60d628e5bcffeb0dd72fd808a8aedad1bd3f6fe5ccad1bf`.
A new local build must receive a separate size/hash record before handoff.
Generating it does not install it, change device settings or validate the new
icons on the S23. The user will install manually.

## Implementation and focused RED/GREEN

The implementer replaced only the icon slot in `MonitorRoot` and added the three
local drawable resources plus `MonitorBottomNavigationTest`.

Implementer-reported focused run before production edits: two tests executed,
expected failure at missing `tab-icon-overview` (RED). A test-only density
capture compile issue was corrected before the meaningful RED run.
After the vector replacement, the focused Gradle class passed 2/2 (GREEN).
A fixture-dependent destination text check was replaced with existing stable
destination tags; selection and destination assertions remain present.

The implementer additionally installed both test APKs only on the owned AVD
and reran direct instrumentation, 2/2, to recover the generated synthetic
screenshots after Gradle removed the app. This does not count as a full suite.
The controller did not personally observe the initial RED command; that part
is attributed to the implementer, not an independent execution.

## Controller verification

Controller executed the complete planned command with
`ANDROID_SERIAL=emulator-5572`, including JVM tests, both debug APK builds, lint
and the full connected suite. Result: exit 0, `BUILD SUCCESSFUL in 1m 43s`,
85 tasks (12 executed / 73 up-to-date). XML reports checked:

| Suite | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| JVM | 112 | 0 | 0 | 0 |
| Instrumented | 146 | 0 | 0 | 0 |

Lint: 0 errors/fatal, 8 existing warnings (tool/dependency versions and missing
launcher application icon), none in the navigation changes.

The controller read the actual source, vector paths and test assertions, and
visually inspected both new synthetic screenshots. Icons are visibly larger;
light/dark selection tint and labels remain legible. The controlled test covers
28 dp rendered boxes, minimum 48 dp touch dimensions, decorative semantics,
single actionable labels and tab selection/routes across eight combinations
of theme/font scale/portrait-landscape constraints.

Diff audit: no application files outside the approved icon slot/resources/test.
Historical screenshots untouched. Generated BuildConfig retains
`C6_PACKAGE_NAME="com.c6bank.app"`. APK identity/version remain
`br.com.c6tag.diagnostico`, 1.1/code 2. No INTERNET, exact alarm, DND or full-screen
permission; merged manifest retains backup-disabled/exclusions and the listener.

An initial artifact-audit helper invocation passed a WSL absolute path to the
Windows `aapt2.exe`, which cannot interpret that path. The file existed; using a
project-relative path fixed the verification helper without changing the app.

## Independent reviews

Spec compliance review: **approved**, no blocking findings. Reviewer inspected
actual source/resources/tests and screenshots, without running Gradle/ADB.
Runtime evidence above belongs to the controller.

Independent final quality review: **approved**, no Critical/Important/Minor
findings. This reviewer also inspected the actual narrow diff and screenshots,
without running tests or builds. Both reviews cover the entire small change.

## New local APK

- Path: `app/build/outputs/apk/debug/app-debug.apk`.
- Windows path:
  `C:\Users\Victor\dev_mob\notification_app\app\build\outputs\apk\debug\app-debug.apk`.
- Size: **12,974,137 bytes**.
- SHA-256:
  `1f8552908d28b0a17bd59e5b08aed42715f68ff3223fa8cd0b4701bb7731f95f`.
- Version: 1.1/code 2, unchanged by this visual-only patch.
- `apksigner verify --verbose`: **Verifies**, APK Signature Scheme v2 valid,
  one signer.

To install manually, choose updating the existing app, not uninstalling it.
Do not clear data or configure a new balance merely because the icons changed.
If Android refuses the update, stop rather than uninstalling the current app.
No S23 command or installation was performed for this follow-up.

## Limits

The new vectors were not viewed on the physical S23 during this task.
The earlier physical TalkBack check covered the prior artifact; the new
accessibility assertions are semantic tests, not a fresh speech/focus session.
Background-delivery and timing limitations from the main monitor validation
remain unchanged.

Owned `emulator-5572` shut down by the controller with `emu kill`, response OK.
No physical device was accessed or configured. Feature branch preserved without
merge/push. The APK itself remains a local ignored build artifact, not committed.

## Local checkpoints

- `d17ae84` — approved specification and reviewed implementation plan.
- `ed164ba` — vector icon implementation.
- `310fa19` — geometry/semantics/navigation tests and synthetic screenshots.
- This result document is committed separately with the final documentation.

No code changes followed the full controller verification or independent
reviews; the final updates were documentation and commit bookkeeping only.

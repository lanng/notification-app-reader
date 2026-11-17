# Launcher artwork — implementation evidence

## Status

Implementation complete. Controller full-suite/artifact verification and
independent specification/quality reviews approved. APK ready for manual update.
User approved adaptation and explicitly requested implementation.
No physical-device access or installation is part of this task.

Spec and plan were independently reviewed and approved:

- `../superpowers/specs/2026-11-17-launcher-artwork-design.md`
- `../superpowers/plans/2026-11-17-launcher-artwork.md`

Baseline implementation checkpoint: `7af56fb`.

## Input and scope

User-provided source: square 1254 px RGB PNG, 1,650,458 bytes, SHA-256
`d279e810703fb4ad194ad4ad3faa782eb56b7e223f246d1801e370958ade8a2e`.
The supplied Downloads file is read-only for this task; the implementation
retains an unchanged repository copy for reproducible generation.

Only launcher artwork/packaging changes are authorized: adaptive icon,
density fallbacks, manifest icon references, development generator and tests.
Exterior-connected near-white border may be made transparent; original art,
colors and disconnected interior white details must remain intact.
Notification/navigation icons and financial/background behavior remain unchanged.

## Baseline and verification route

Controller verified System.Drawing can decode the exact input without changing
it. No external image library was installed.

The initial local JVM/build/APKtest/lint command printed `BUILD SUCCESSFUL in
11s` but the shell wrapper timed out at 180 seconds; that invocation is not
accepted as a successful command. Repeating with `--no-daemon` completed with
exit 0, `BUILD SUCCESSFUL in 10s`, 84 tasks (1 executed / 83 up-to-date).
No application change was made to address shell/process lifetime.
Existing baseline reports: 112 JVM / 146 instrumented, lint 0 errors / 8 warnings.
No new instrumented baseline was run on the physical S23.

Implementer owns focused test-first RED/GREEN and controlled mask previews.
Controller owns full JVM/instrumented/build/lint verification, source/input/diff
audit, packaged icon inspection, signature and APK hash. Gradle and ADB ownership
must be handed off explicitly; instrumented target is owned `emulator-5572` only.

## Artifact boundary

The prior navigation-icon APK had SHA-256
`1f8552908d28b0a17bd59e5b08aed42715f68ff3223fa8cd0b4701bb7731f95f`.
That fact does not indicate whether the user installed it manually.
Record the new launcher-artwork build separately; do not overwrite earlier
installed-artifact evidence or assume a physical update.

## Limits

Circle/rounded-square previews are controlled masks, not screenshots of the
user's launcher. Appearance on the physical S23 remains unobserved.
No claim of new banking/capture/alert or TalkBack validation follows from
packaging a launcher icon.

## Recovery checkpoint

After the server restart, the controller recovered the existing reviewed
specification/plan and resumed the same implementation agent. Completed reviews
and accepted baseline were not repeated. No application modifications were
present at the recovery checkpoint; parent evidence documents were preserved.

## Implementation and test-first evidence

The implementer added the adaptive/legacy launcher resources, two manifest icon
attributes, source-preserving PowerShell generator, artwork README and
`LauncherIconTest`.

Implementer reported a meaningful RED run against the original manifest:
configured icon ID was 0. After implementation, focused instrumentation passed
4/4 tests. A follow-up edge test caught a bright fringe; tightening only the
exterior-connected near-white cleanup produced the final 4/4 GREEN.
This RED/GREEN evidence is attributed to the implementer, not a controller
reconstruction of the earlier command.

The chosen artwork box is 65 dp within the transparent 108 dp foreground.
Five foreground densities are 108/162/216/324/432 px; legacy fallbacks are
48/72/96/144/192 px. Background is navy `#052B57`.
The implementer reported byte-identical regeneration of all 12 generated PNGs.

## Controller verification

Controller ran the full planned JVM/build/APKtest/lint/connected command with
`--no-daemon` and `ANDROID_SERIAL=emulator-5572`. Result: exit 0,
`BUILD SUCCESSFUL in 1m 57s`, 85 tasks (12 executed / 73 up-to-date).
XML reports independently counted:

| Suite | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| JVM | 112 | 0 | 0 | 0 |
| Instrumented | 150 | 0 | 0 | 0 |

Lint: 0 errors/fatal, 14 warnings. The previous MissingApplicationIcon warning
is gone. Seven prior tool/dependency warnings remain. Current icon-specific
warnings: ObsoleteSdkInt for the v26 folder with minSdk 26; five
IconLauncherShape checks on legacy PNGs used under the shared round-icon name;
one MonochromeLauncherIcon warning (a themed monochrome variant is explicitly
outside this task). These are not runtime test failures.

Controller inspected source/script/tests and both controlled mask previews.
The bell, red alert and gate remain visible; inner white artwork is retained.
Preview alpha checked independently: both corner pixels are transparent;
at (30,30), the circle is transparent and rounded-square opaque, confirming
the masks differ despite some image viewers flattening alpha.

Original Downloads source and repository copy both retain the approved byte
count/hash. Generated PNG dimensions were independently verified. Manifest
comparison confirms only `icon`/`roundIcon` attributes changed; the notification
icon is byte-identical to baseline. Packaged adaptive XML, background/foreground,
regular/round icon references and the original application identity were checked.
No prohibited network/exact-alarm/DND/full-screen permission appeared.
Generated BuildConfig retains `C6_PACKAGE_NAME="com.c6bank.app"`.

## Current local APK

- Path: `app/build/outputs/apk/debug/app-debug.apk`.
- Size: **12,976,475 bytes**.
- SHA-256:
  `40b9e38a015247e47d98796f2263217d48ae3429d70a0e21346f15b706940f01`.
- Version: **1.1/code 2**, unchanged.
- `apksigner verify --verbose`: **Verifies**, v2 signature valid, one signer.

This is generated locally, not installed by the assistant on a physical device.
Manual installation should update the existing app without uninstalling,
clearing data or creating another balance reference.

## Review status and regeneration

Independent spec compliance review: **approved**. Reviewer inspected the actual
implementation and classified the icon packaging lint warnings as advisory,
not a physical-device failure or a reason to change the approved resource layout.
The themed monochrome variant remains explicitly outside scope.
Reviewer did not run Gradle/ADB; runtime results above are controller evidence.

Independent final code-quality review: **approved, no findings**. Reviewer
inspected the actual generator, resource packaging, tests and scope; no
Critical/Important/Minor defects identified. No runtime or physical-device
verification was performed by that reviewer.

Intentional regeneration of the tracked, generated previews and resources:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/generate-launcher-icons.ps1 -AllowPreviewOverwrite
```

Without that switch, the tool refuses to overwrite existing launcher previews;
it does not delete other screenshots or image files.

Controller independently exercised that refusal: command exited nonzero with
the expected message and all 12 generated PNG hashes remained unchanged.
The ten density PNGs inside the signed APK were also independently compared
byte-for-byte with the generated resources; all matched.

## Closure

Owned read-only `emulator-5572` was shut down by the controller with `emu kill`,
response OK. No physical device was accessed, configured or updated.
Keep `feat/monitor-history-and-settings` without automatic merge/push.
Application source did not change after the full verification and code reviews;
remaining work was documentation and local commit bookkeeping.

Local checkpoints:

- `7af56fb` — reviewed specification and implementation plan.
- `beb1f18` — adaptive launcher resources, unchanged input and generator.
- `2658b77` — packaged-icon tests and controlled mask previews.
- This final evidence is committed separately as documentation.

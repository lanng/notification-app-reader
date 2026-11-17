# Launcher artwork — design

## Approval and goal

The user supplied a PNG and approved adapting it as the application launcher
icon, preserving the artwork/colors while treating the outer white border and
adding adaptive-icon margins. They explicitly said to proceed and implement;
do not introduce another user-review gate for this bounded visual change.
Continue the previously authorized delegated implementation and manual-APK handoff.
Independent specification and implementation-plan reviews: approved.

Source: 1254 × 1254 RGB PNG, 1,650,458 bytes, SHA-256
`d279e810703fb4ad194ad4ad3faa782eb56b7e223f246d1801e370958ade8a2e`.
Preserve an unchanged copy at `artwork/launcher/source.png`.

## Behavior

- Add the launcher's full-color app icon, currently absent from the manifest.
- Use an Android adaptive icon with a navy background and centered artwork.
  Remove only near-white background connected to the source image's exterior;
  preserve white details inside the toll gate and the rest of the original art.
- Put the composition within adaptive safe margins. Inspect circular and
  rounded-square previews so the bell, alert symbol and gate are not clipped.
  The navy outer framing can be masked by the launcher.
- Supply density-appropriate legacy bitmap fallbacks and adaptive foreground
  PNGs. Point both `android:icon` and `android:roundIcon` to the adaptive-capable
  `@mipmap/ic_launcher`; let Android/the launcher choose its mask.
- Keep the existing monochrome notification icon unchanged. Do not reuse the
  color artwork as a notification status icon, navigation icon or app content.
- Do not add themed/monochrome variants, animations or a splash redesign.

## Boundaries and reproducibility

Add a small development-only PowerShell generator using Windows System.Drawing
and the unchanged source copy, plus an artwork README. No app dependencies,
network access or generative image editing. Do not modify the Downloads original.
Generated resources and mask previews are local deterministic image conversions.

No financial/capture/alert/database changes, permissions, package identity,
version changes or physical-device operations. Verification on an explicitly
owned read-only AVD is allowed. Preserve the current feature branch.
Generate a signed debug APK for the user's manual update; never uninstall,
clear data, reset balance or install it on a physical device.

## Verification

Test-first instrumentation must fail against the missing manifest icon before
resource/manifest implementation. After implementation verify PackageManager
returns an `AdaptiveIconDrawable` with real foreground/background layers and
that the foreground has transparent outer padding while retaining opaque white
artwork details. Inspect generated circular and rounded-square previews.

Run the full JVM/instrumented/build/lint suite using only a newly owned read-only
AVD. Audit packaged icon references/resources, application identity, source hash,
signature and APK hash. A lower MissingApplicationIcon lint warning count is
expected, not a reason to change unrelated warnings.

The mask previews are controlled renders, not evidence of appearance on the
physical S23 or a guarantee about every launcher. Existing functional/TalkBack
evidence remains historical and is not re-established by this visual patch.

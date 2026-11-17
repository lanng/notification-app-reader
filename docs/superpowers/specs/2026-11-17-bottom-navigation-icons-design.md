# Bottom navigation icons — design

## Goal and approval

The user reported that the bottom navigation icons are too small on the S23.
They approved replacing the current text glyphs with 28 dp vector icons while
preserving labels, colors and touch targets. This document makes that small
visual change explicit. The user subsequently approved this written document
with “Sim”; implementation is authorized, but physical installation is not.

Independent read-only specification review: approved, with no blocking issues.
The review highlighted measuring the rendered icon box and retaining each
visible label as the tab's sole accessible name; both are acceptance criteria below.

## Cause and alternatives

`MonitorRoot.kt` renders `◉`, `≡` and `⚙` using `Text`. Their apparent size and
shape depend on font metrics rather than an explicit icon viewport.

- **Recommended:** local vector drawables rendered using Material `Icon` in a
  28 × 28 dp box. Predictable sizing without adding an icon dependency.
- Increasing glyph font size would be smaller in code but retain inconsistent
  shapes and font-dependent sizing.
- Increasing the whole navigation bar would change the approved layout and
  consume content space without addressing the glyph cause.

## Visual and accessibility behavior

Replace only the three icon contents of the existing `NavigationBarItem`s:

| Tab | Vector |
|---|---|
| Visão geral | Four-tile dashboard |
| Histórico | History clock with a return arrow |
| Ajustes | Gear |

Use simple monochrome paths with comparable visual weight and most of the
viewport occupied; avoid detailed or heavily padded drawings.
Render each in an explicit 28 × 28 dp box, using the existing Material selected
and unselected tint. Preserve the current bar height, labels, selection indicator,
theme palettes, routing, selection state and existing tab test tags.

Keep each tab's touch target at least 48 × 48 dp where it is displayed.
The icon is decorative (`contentDescription = null`): the existing visible tab
label remains the accessible name, without an extra focus stop or duplicate name.
Do not change system font/display settings to compensate for the original issue.

## Implementation boundaries

Add three local vector resources and reference them from `MonitorRoot`.
No new dependencies, permissions, database migrations, financial logic,
capture/background behavior, application identity or version changes.
Keep the notification icon unchanged. Do not copy the user's screenshot into
the repository or use their financial data as test fixtures.

This approval does not authorize installation on the S23. A new APK requires
its own hash record and separate installation consent; the prior installed
artifact and its validation evidence remain historical facts.

## Evidence required

1. An instrumented regression test must fail against the glyph implementation,
   then pass after the replacement. Check all three icon boxes are 28 dp square,
   tab touch targets are at least 48 dp square, labels/selection remain usable,
   and icons do not introduce duplicate accessible names.
2. Exercise light/dark themes, normal/200% font scales and controlled portrait/
   landscape content constraints. These tests do not prove physical rotation
   or fresh TalkBack behavior on the S23.
3. Inspect new screenshots using synthetic data in both themes; preserve the
   earlier release screenshots rather than silently replacing historical evidence.
4. Run JVM tests, debug/app-test builds, lint and instrumented UI regression tests
   on an explicitly selected owned AVD, never on the user's installation.
5. Record results, limitations and the new APK hash; keep commits local unless
   the user separately chooses integration/publication.

# Launcher artwork source

`source.png` is an unchanged copy of the user-provided 1254 × 1254 RGB PNG.
Its SHA-256 is
`d279e810703fb4ad194ad4ad3faa782eb56b7e223f246d1801e370958ade8a2e`.
The generator verifies both this hash and the expected dimensions before writing
any resources.

From the repository root, run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\generate-launcher-icons.ps1
```

The development-only generator uses Windows `System.Drawing`, removes only
near-white pixels connected to the source image's exterior, and preserves
interior white toll-gate details. It scales the unchanged artwork into a
centered 65 dp square on a transparent 108 dp adaptive foreground. The navy
adaptive background is `#052B57`. Legacy bitmaps and the mask previews are
derived from the same central 72 dp composition.

The generator writes only the five density-specific launcher resource folders
and `docs/testing/screenshots/launcher-circle.png` /
`launcher-rounded-square.png`. If those two exact preview files already exist,
the default behavior refuses to overwrite them. To intentionally regenerate
those generated previews, pass `-AllowPreviewOverwrite`.

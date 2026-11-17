param(
    [string] $SourcePath = (Join-Path (Split-Path -Parent $PSScriptRoot) 'artwork\launcher\source.png'),
    [switch] $AllowPreviewOverwrite
)

$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$expectedSha256 = 'd279e810703fb4ad194ad4ad3faa782eb56b7e223f246d1801e370958ade8a2e'

if (-not (Test-Path -LiteralPath $SourcePath -PathType Leaf)) {
    throw "Launcher artwork source not found: $SourcePath"
}

$resolvedSource = (Resolve-Path -LiteralPath $SourcePath).Path
$actualSha256 = (Get-FileHash -LiteralPath $resolvedSource -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualSha256 -ne $expectedSha256) {
    throw "Launcher artwork source hash mismatch. Expected $expectedSha256, got $actualSha256."
}

$previewPaths = @(
    (Join-Path $repositoryRoot 'docs\testing\screenshots\launcher-circle.png'),
    (Join-Path $repositoryRoot 'docs\testing\screenshots\launcher-rounded-square.png')
)
if (-not $AllowPreviewOverwrite) {
    $existingPreviews = @($previewPaths | Where-Object { Test-Path -LiteralPath $_ })
    if ($existingPreviews.Count -gt 0) {
        throw "Refusing to overwrite existing screenshots. Use -AllowPreviewOverwrite only to regenerate these launcher previews: $($existingPreviews -join ', ')"
    }
}

Add-Type -AssemblyName System.Drawing

$generatorSource = @'
using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;

namespace LauncherArtwork
{
    public static class IconGenerator
    {
        private const int SourceSize = 1254;
        private const int ArtworkDp = 65;
        private const int AdaptiveCanvasDp = 108;
        private const int VisibleCanvasDp = 72;
        private const int PreviewPixelsPerDp = 6;

        public static void Generate(string sourcePath, string repositoryRoot)
        {
            using (Bitmap source = new Bitmap(sourcePath))
            {
                if (source.Width != SourceSize || source.Height != SourceSize)
                {
                    throw new InvalidDataException(
                        "Expected a 1254 x 1254 launcher source image, got " +
                        source.Width + " x " + source.Height + ".");
                }

                using (Bitmap processed = RemoveExteriorNearWhite(source))
                {
                    string[] densityNames = { "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi" };
                    int[] foregroundPixels = { 108, 162, 216, 324, 432 };
                    int[] legacyPixels = { 48, 72, 96, 144, 192 };

                    for (int index = 0; index < densityNames.Length; index++)
                    {
                        string mipmap = Path.Combine(
                            repositoryRoot, "app", "src", "main", "res",
                            "mipmap-" + densityNames[index]);

                        using (Bitmap foreground = RenderForeground(
                            processed, foregroundPixels[index],
                            foregroundPixels[index] * ArtworkDp / (float)AdaptiveCanvasDp))
                        {
                            SavePng(foreground, Path.Combine(mipmap, "ic_launcher_foreground.png"));
                        }

                        using (Bitmap legacy = RenderLegacy(processed, legacyPixels[index]))
                        {
                            SavePng(legacy, Path.Combine(mipmap, "ic_launcher.png"));
                        }
                    }

                    string screenshotDirectory = Path.Combine(
                        repositoryRoot, "docs", "testing", "screenshots");
                    SavePreview(processed, Path.Combine(screenshotDirectory, "launcher-circle.png"), true);
                    SavePreview(processed, Path.Combine(screenshotDirectory, "launcher-rounded-square.png"), false);
                }
            }
        }

        private static Bitmap RemoveExteriorNearWhite(Bitmap source)
        {
            int width = source.Width;
            int height = source.Height;
            int pixelCount = checked(width * height);
            using (Bitmap normalized = new Bitmap(width, height, PixelFormat.Format32bppArgb))
            {
                using (Graphics graphics = Graphics.FromImage(normalized))
                {
                    graphics.DrawImage(source, new Rectangle(0, 0, width, height));
                }

                Rectangle bounds = new Rectangle(0, 0, width, height);
                BitmapData data = normalized.LockBits(
                    bounds, ImageLockMode.ReadWrite, PixelFormat.Format32bppArgb);
                try
                {
                    byte[] pixels = CopyPixels(data, width, height);
                    bool[] exterior = new bool[pixelCount];
                    int[] queue = new int[pixelCount];
                    int head = 0;
                    int tail = 0;

                    for (int x = 0; x < width; x++)
                    {
                        EnqueueIfExteriorNearWhite(x, width, height, pixels, exterior, queue, ref tail);
                        EnqueueIfExteriorNearWhite((height - 1) * width + x, width, height, pixels, exterior, queue, ref tail);
                    }
                    for (int y = 1; y < height - 1; y++)
                    {
                        EnqueueIfExteriorNearWhite(y * width, width, height, pixels, exterior, queue, ref tail);
                        EnqueueIfExteriorNearWhite(y * width + width - 1, width, height, pixels, exterior, queue, ref tail);
                    }

                    while (head < tail)
                    {
                        int current = queue[head++];
                        int x = current % width;
                        int y = current / width;
                        for (int offsetY = -1; offsetY <= 1; offsetY++)
                        {
                            int neighborY = y + offsetY;
                            if (neighborY < 0 || neighborY >= height)
                            {
                                continue;
                            }
                            for (int offsetX = -1; offsetX <= 1; offsetX++)
                            {
                                if (offsetX == 0 && offsetY == 0)
                                {
                                    continue;
                                }
                                int neighborX = x + offsetX;
                                if (neighborX >= 0 && neighborX < width)
                                {
                                    EnqueueIfExteriorNearWhite(
                                        neighborY * width + neighborX, width, height,
                                        pixels, exterior, queue, ref tail);
                                }
                            }
                        }
                    }

                    for (int index = 0; index < pixelCount; index++)
                    {
                        if (exterior[index])
                        {
                            int offset = index * 4;
                            pixels[offset] = 0;
                            pixels[offset + 1] = 0;
                            pixels[offset + 2] = 0;
                            pixels[offset + 3] = 0;
                        }
                    }
                    WritePixels(data, width, height, pixels);
                }
                finally
                {
                    normalized.UnlockBits(data);
                }
                return (Bitmap)normalized.Clone();
            }
        }

        private static void EnqueueIfExteriorNearWhite(
            int index, int width, int height, byte[] pixels, bool[] exterior,
            int[] queue, ref int tail)
        {
            if (exterior[index])
            {
                return;
            }

            int offset = index * 4;
            int blue = pixels[offset];
            int green = pixels[offset + 1];
            int red = pixels[offset + 2];
            int minimum = Math.Min(red, Math.Min(green, blue));
            int maximum = Math.Max(red, Math.Max(green, blue));
            if (minimum >= 220 && maximum - minimum <= 32)
            {
                exterior[index] = true;
                queue[tail++] = index;
            }
        }

        private static byte[] CopyPixels(BitmapData data, int width, int height)
        {
            int rowBytes = checked(width * 4);
            byte[] pixels = new byte[checked(rowBytes * height)];
            for (int y = 0; y < height; y++)
            {
                IntPtr row = IntPtr.Add(data.Scan0, y * data.Stride);
                Marshal.Copy(row, pixels, y * rowBytes, rowBytes);
            }
            return pixels;
        }

        private static void WritePixels(BitmapData data, int width, int height, byte[] pixels)
        {
            int rowBytes = checked(width * 4);
            for (int y = 0; y < height; y++)
            {
                IntPtr row = IntPtr.Add(data.Scan0, y * data.Stride);
                Marshal.Copy(pixels, y * rowBytes, row, rowBytes);
            }
        }

        private static Bitmap RenderForeground(Bitmap source, int canvasPixels, float artworkPixels)
        {
            Bitmap result = CreateBitmap(canvasPixels, canvasPixels);
            using (Graphics graphics = Graphics.FromImage(result))
            {
                ConfigureQuality(graphics);
                graphics.Clear(Color.Transparent);
                float offset = (canvasPixels - artworkPixels) / 2f;
                graphics.DrawImage(source, new RectangleF(offset, offset, artworkPixels, artworkPixels));
            }
            return result;
        }

        private static Bitmap RenderLegacy(Bitmap source, int outputPixels)
        {
            Bitmap result = CreateBitmap(outputPixels, outputPixels);
            using (Graphics graphics = Graphics.FromImage(result))
            {
                ConfigureQuality(graphics);
                graphics.Clear(Color.FromArgb(255, 5, 43, 87));
                float artworkPixels = outputPixels * ArtworkDp / (float)VisibleCanvasDp;
                float offset = (outputPixels - artworkPixels) / 2f;
                graphics.DrawImage(source, new RectangleF(offset, offset, artworkPixels, artworkPixels));
            }
            return result;
        }

        private static void SavePreview(Bitmap source, string path, bool circle)
        {
            int side = VisibleCanvasDp * PreviewPixelsPerDp;
            float artworkPixels = ArtworkDp * PreviewPixelsPerDp;
            using (Bitmap composition = CreateBitmap(side, side))
            using (Bitmap mask = CreateBitmap(side, side))
            using (Bitmap result = CreateBitmap(side, side))
            {
                using (Graphics graphics = Graphics.FromImage(composition))
                {
                    ConfigureQuality(graphics);
                    graphics.Clear(Color.FromArgb(255, 5, 43, 87));
                    float offset = (side - artworkPixels) / 2f;
                    graphics.DrawImage(source, new RectangleF(offset, offset, artworkPixels, artworkPixels));
                }

                using (Graphics graphics = Graphics.FromImage(mask))
                using (GraphicsPath maskPath = new GraphicsPath())
                {
                    ConfigureQuality(graphics);
                    graphics.Clear(Color.Transparent);
                    if (circle)
                    {
                        maskPath.AddEllipse(0, 0, side - 1, side - 1);
                    }
                    else
                    {
                        AddRoundedRectangle(
                            maskPath, new RectangleF(0.5f, 0.5f, side - 1f, side - 1f), side * 0.22f);
                    }
                    using (SolidBrush brush = new SolidBrush(Color.White))
                    {
                        graphics.FillPath(brush, maskPath);
                    }
                }

                for (int y = 0; y < side; y++)
                {
                    for (int x = 0; x < side; x++)
                    {
                        int alpha = mask.GetPixel(x, y).A;
                        Color color = composition.GetPixel(x, y);
                        result.SetPixel(x, y, Color.FromArgb(alpha, color.R, color.G, color.B));
                    }
                }
                SavePng(result, path);
            }
        }

        private static void AddRoundedRectangle(GraphicsPath path, RectangleF rectangle, float radius)
        {
            float diameter = radius * 2f;
            path.AddArc(rectangle.Left, rectangle.Top, diameter, diameter, 180, 90);
            path.AddArc(rectangle.Right - diameter, rectangle.Top, diameter, diameter, 270, 90);
            path.AddArc(rectangle.Right - diameter, rectangle.Bottom - diameter, diameter, diameter, 0, 90);
            path.AddArc(rectangle.Left, rectangle.Bottom - diameter, diameter, diameter, 90, 90);
            path.CloseFigure();
        }

        private static Bitmap CreateBitmap(int width, int height)
        {
            Bitmap bitmap = new Bitmap(width, height, PixelFormat.Format32bppArgb);
            bitmap.SetResolution(96, 96);
            return bitmap;
        }

        private static void ConfigureQuality(Graphics graphics)
        {
            graphics.CompositingMode = CompositingMode.SourceOver;
            graphics.CompositingQuality = CompositingQuality.HighQuality;
            graphics.InterpolationMode = InterpolationMode.HighQualityBicubic;
            graphics.PixelOffsetMode = PixelOffsetMode.HighQuality;
            graphics.SmoothingMode = SmoothingMode.HighQuality;
        }

        private static void SavePng(Bitmap bitmap, string path)
        {
            string directory = Path.GetDirectoryName(path);
            Directory.CreateDirectory(directory);
            using (FileStream stream = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.None))
            {
                bitmap.Save(stream, ImageFormat.Png);
            }
        }
    }
}
'@

Add-Type -TypeDefinition $generatorSource -ReferencedAssemblies System.Drawing
[LauncherArtwork.IconGenerator]::Generate($resolvedSource, $repositoryRoot)
Write-Host 'Generated density launcher resources and circular/rounded-square previews.'

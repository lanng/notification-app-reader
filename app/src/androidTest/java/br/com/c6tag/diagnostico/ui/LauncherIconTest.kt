package br.com.c6tag.diagnostico.ui

import android.content.pm.PackageManager
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIconTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val packageManager = targetContext.packageManager

    @Test
    fun applicationDeclaresALauncherIcon() {
        val applicationInfo = packageManager.getApplicationInfo(targetContext.packageName, 0)

        assertNotEquals(0, applicationInfo.icon)
    }

    @Test
    fun packageManagerResolvesAnAdaptiveApplicationIcon() {
        val icon = packageManager.getApplicationIcon(targetContext.packageName)

        assertTrue("Expected AdaptiveIconDrawable, got ${icon.javaClass.simpleName}", icon is AdaptiveIconDrawable)
    }

    @Test
    fun adaptiveIconContainsBackgroundAndForegroundLayers() {
        val icon = packageManager.getApplicationIcon(targetContext.packageName) as? AdaptiveIconDrawable

        assertNotNull("Application icon must be adaptive", icon)
        assertNotNull("Adaptive icon must contain a background", icon!!.background)
        assertNotNull("Adaptive icon must contain a foreground", icon.foreground)
    }

    @Test
    fun foregroundHasTransparentPaddingAndRetainsOpaqueWhiteArtwork() {
        val icon = packageManager.getApplicationIcon(targetContext.packageName) as? AdaptiveIconDrawable
        assertNotNull("Application icon must be adaptive", icon)

        val foreground = icon!!.foreground as? BitmapDrawable
        assertNotNull("Adaptive foreground must be a bitmap", foreground)

        val bitmap = foreground!!.bitmap
        assertTrue("Foreground bitmap must retain alpha", bitmap.hasAlpha())
        val outerCorners = listOf(
            bitmap.getPixel(0, 0),
            bitmap.getPixel(bitmap.width - 1, 0),
            bitmap.getPixel(0, bitmap.height - 1),
            bitmap.getPixel(bitmap.width - 1, bitmap.height - 1),
        )
        assertTrue(
            "Foreground must have transparent outer padding",
            outerCorners.all { it ushr 24 == 0 },
        )
        val artworkSize = bitmap.width * 65f / 108f
        val artworkOffset = (bitmap.width - artworkSize) / 2f
        val exteriorEdgeY = (artworkOffset + 43f * artworkSize / 1254f).toInt()
        assertTrue(
            "Near-white exterior edge must be cleared before it forms a bright opaque fringe",
            bitmap.getPixel(bitmap.width / 2, exteriorEdgeY) ushr 24 < 140,
        )

        var hasOpaqueArtwork = false
        var hasOpaqueWhiteGateDetail = false
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = pixel ushr 24
                if (alpha > 0) {
                    hasOpaqueArtwork = true
                    val red = pixel shr 16 and 0xff
                    val green = pixel shr 8 and 0xff
                    val blue = pixel and 0xff
                    val gateArmRegion =
                        x in (bitmap.width * 0.45f).toInt()..(bitmap.width * 0.66f).toInt() &&
                            y in (bitmap.height * 0.60f).toInt()..(bitmap.height * 0.68f).toInt()
                    if (gateArmRegion && alpha == 0xff && red >= 245 && green >= 245 && blue >= 245) {
                        hasOpaqueWhiteGateDetail = true
                    }
                }
            }
        }

        assertTrue("Foreground must contain non-transparent artwork", hasOpaqueArtwork)
        assertTrue("Foreground must retain opaque white toll-gate detail", hasOpaqueWhiteGateDetail)
    }
}

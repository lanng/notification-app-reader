package br.com.c6tag.diagnostico.capture

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManifestPrivacyTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun packagedAppOnlyRequestsJustifiedPermissionsAndKeepsListenerBindingPrivate() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS,
        )
        val requested = packageInfo.requestedPermissions.orEmpty().toSet()
        assertFalse("INTERNET must not be requested", "android.permission.INTERNET" in requested)
        assertTrue(
            "the debug-only probe may request full-screen-intent access",
            "android.permission.USE_FULL_SCREEN_INTENT" in requested,
        )
        assertTrue("local alerts must declare POST_NOTIFICATIONS", "android.permission.POST_NOTIFICATIONS" in requested)
        assertFalse("READ_EXTERNAL_STORAGE must not be requested", "android.permission.READ_EXTERNAL_STORAGE" in requested)
        assertFalse("WRITE_EXTERNAL_STORAGE must not be requested", "android.permission.WRITE_EXTERNAL_STORAGE" in requested)
        assertEquals(
            "only the documented WorkManager and local-alert permissions may be requested",
            DOCUMENTED_PLATFORM_PERMISSIONS,
            requested.filter { it.startsWith("android.permission.") }.toSet(),
        )
        assertTrue(requested.none { "PRIVILEGED" in it || "MANAGE_EXTERNAL_STORAGE" in it })

        val applicationInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals(0, applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)

        val listener = packageInfo.services.orEmpty().single {
            it.name.endsWith(".capture.C6NotificationListener")
        }
        assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", listener.permission)
        assertEquals(false, listener.exported)

        val jobService = packageInfo.services.orEmpty().single {
            it.name == "androidx.work.impl.background.systemjob.SystemJobService"
        }
        assertEquals("android.permission.BIND_JOB_SERVICE", jobService.permission)
        assertTrue(jobService.exported)

        val foregroundService = packageInfo.services.orEmpty().single {
            it.name == "androidx.work.impl.foreground.SystemForegroundService"
        }
        assertFalse(foregroundService.exported)

        val actionReceiver = packageInfo.receivers.orEmpty().single {
            it.name.endsWith(".monitor.alerts.AlertActionReceiver")
        }
        assertFalse(actionReceiver.exported)

        val launcher = packageInfo.activities.orEmpty().single {
            it.name.endsWith(".MainActivity")
        }
        assertTrue(launcher.exported)

        val probe = packageInfo.activities.orEmpty().single {
            it.name.endsWith(".remote.probe.AlertProbeActivity")
        }
        assertFalse("the debug probe activity must not be exported", probe.exported)

        val probeStop = packageInfo.receivers.orEmpty().single {
            it.name.endsWith(".remote.probe.ProbeStopReceiver")
        }
        assertFalse("the debug probe stop receiver must not be exported", probeStop.exported)
    }

    @Test
    fun packagedBackupXmlExcludesEveryPrivateDomainFromLegacyCloudAndTransferRules() {
        val applicationInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
        val legacyResource = context.resources.getIdentifier(
            "backup_rules",
            "xml",
            applicationInfo.packageName,
        )
        assertTrue("legacy full-backup rules are not packaged", legacyResource != 0)
        val legacy = readRules(legacyResource)
        assertTrue("legacy rules must not contain include entries", legacy.includes.isEmpty())
        DOMAINS.forEach { domain ->
            assertTrue(
                "legacy rules must exclude $domain",
                domain to "." in legacy.excludes,
            )
        }

        val extractionRulesId = context.resources.getIdentifier(
            "data_extraction_rules",
            "xml",
            applicationInfo.packageName,
        )
        assertTrue("Android 12+ data-extraction-rules are not packaged", extractionRulesId != 0)
        val extraction = readRules(extractionRulesId)
        assertTrue("extraction rules must not contain include entries", extraction.includes.isEmpty())
        listOf("cloud-backup", "device-transfer").forEach { transfer ->
            val excludes = extraction.excludesBySection[transfer].orEmpty()
            DOMAINS.forEach { domain ->
                assertTrue(
                    "$transfer must exclude $domain",
                    domain to "." in excludes,
                )
            }
        }
    }

    private fun readRules(resourceId: Int): ParsedRules {
        val parser = context.resources.getXml(resourceId)
        parser.use {
            val includes = mutableListOf<Pair<String, String>>()
            val excludes = mutableListOf<Pair<String, String>>()
            val excludesBySection = mutableMapOf<String, MutableList<Pair<String, String>>>()
            var section: String? = null
            while (parser.eventType != XmlResourceParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlResourceParser.START_TAG -> {
                        when (parser.name) {
                            "cloud-backup", "device-transfer" -> section = parser.name
                            "include" -> includes += parser.rule()
                            "exclude" -> {
                                val rule = parser.rule()
                                excludes += rule
                                section?.let { excludesBySection.getOrPut(it, ::mutableListOf) += rule }
                            }
                        }
                    }

                    XmlResourceParser.END_TAG -> {
                        if (parser.name == section) section = null
                    }
                }
                parser.next()
            }
            return ParsedRules(includes, excludes, excludesBySection)
        }
    }

    private fun XmlResourceParser.rule(): Pair<String, String> =
        getAttributeValue(null, "domain") to getAttributeValue(null, "path")

    private data class ParsedRules(
        val includes: List<Pair<String, String>>,
        val excludes: List<Pair<String, String>>,
        val excludesBySection: Map<String, List<Pair<String, String>>>,
    )

    private companion object {
        val DOCUMENTED_PLATFORM_PERMISSIONS = setOf(
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.USE_FULL_SCREEN_INTENT",
            "android.permission.WAKE_LOCK",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.FOREGROUND_SERVICE",
        )

        val DOMAINS = listOf(
            "root",
            "file",
            "database",
            "sharedpref",
            "external",
            "device_root",
            "device_file",
            "device_database",
            "device_sharedpref",
        )
    }
}

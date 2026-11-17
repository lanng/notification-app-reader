package br.com.c6tag.diagnostico.monitor.alerts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonitorNotifierTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Before fun prepareChannel() {
        manager.cancel(MonitorNotifier.NOTIFICATION_ID)
        manager.deleteNotificationChannel(MonitorNotifier.CHANNEL_ID)
        val declared = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        if (Build.VERSION.SDK_INT >= 33 && Manifest.permission.POST_NOTIFICATIONS in declared) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @After fun clearSyntheticNotification() {
        manager.cancel(MonitorNotifier.NOTIFICATION_ID)
    }

    @Test
    fun currentCycleBuildsPrivateNotificationWithExplicitImmutableAcknowledgment() {
        val notifier = MonitorNotifier(context)
        val overview = alertOverview()
        val notification = notifier.build(overview, overview.alertCycle!!.emissionIntent!!)
        assertNotNull(notification)
        notification!!
        assertEquals(MonitorNotifier.CHANNEL_ID, notification.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("estimado"))
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("200,00"))
        assertEquals(0, notification.flags and Notification.FLAG_AUTO_CANCEL)
        assertEquals(1, notification.actions.size)
        assertEquals("Entendi", notification.actions.single().title.toString())
        assertNotNull(notification.contentIntent)
        assertNotNull(notification.actions.single().actionIntent)
        if (Build.VERSION.SDK_INT >= 31) {
            assertTrue(notification.actions.single().actionIntent.isImmutable)
            assertTrue(notification.contentIntent.isImmutable)
        }
        val another = alertOverview("another-cycle")
        val otherNotification = notifier.build(another, another.alertCycle!!.emissionIntent!!)
        assertNotNull(otherNotification)
        assertNotEquals(notification.actions.single().actionIntent, otherNotification!!.actions.single().actionIntent)
    }

    @Test
    fun channelIsCreatedWithAttentionImportanceWithoutBypassingDnd() {
        val notifier = MonitorNotifier(context)
        notifier.ensureChannel()
        val channel = manager.getNotificationChannel(MonitorNotifier.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertFalse(channel.canBypassDnd())
    }

    @Test
    fun stalePausedOrClosedCycleCannotBuildAnObsoleteNotification() {
        val notifier = MonitorNotifier(context)
        val overview = alertOverview()
        val intent = overview.alertCycle!!.emissionIntent!!
        assertNull(notifier.build(overview, intent.copy(cycleId = "old-cycle")))
        assertNull(notifier.build(overview, intent.copy(version = 99L)))
        assertNull(notifier.build(overview.copy(alertCycle = overview.alertCycle.copy(status = AlertStatus.PAUSED)), intent))
        assertNull(notifier.build(overview.copy(alertCycle = overview.alertCycle.copy(status = AlertStatus.CLOSED)), intent))
    }

    @Test
    fun deniedPermissionIsNotReportedAsAcceptedSubmission() {
        val overview = alertOverview()
        val notifier = MonitorNotifier(context, object : TestPlatform() {
            override fun permissionGranted() = false
            override fun publish(notification: Notification) = fail("Denied notification must not reach Android")
        })
        assertEquals(SubmissionOutcome.PERMISSION_DENIED, notifier.submit(overview, overview.alertCycle!!.emissionIntent!!))
        assertFalse(notifier.capabilities().canSubmit)
        assertFalse(notifier.capabilities().permissionGranted)
    }

    @Test
    fun blockedOrMissingChannelIsNotPublishedAndExistingPreferencesAreUntouched() {
        val overview = alertOverview()
        val blocked = NotificationChannel(MonitorNotifier.CHANNEL_ID, "synthetic blocked channel", NotificationManager.IMPORTANCE_NONE)
        val notifier = MonitorNotifier(context, object : TestPlatform() {
            override fun channel() = blocked
            override fun createChannel(channel: NotificationChannel) = fail("Existing channel must not be overwritten")
            override fun publish(notification: Notification) = fail("Blocked notification must not be published")
        })
        assertEquals(SubmissionOutcome.CHANNEL_BLOCKED, notifier.submit(overview, overview.alertCycle!!.emissionIntent!!))
        assertTrue(notifier.capabilities().channelBlocked)
        val absent = MonitorNotifier(context, object : TestPlatform() {
            override fun channel(): NotificationChannel? = null
            override fun createChannel(channel: NotificationChannel) = Unit
            override fun publish(notification: Notification) = fail("Missing channel must not be reported as accepted")
        })
        assertEquals(SubmissionOutcome.CHANNEL_BLOCKED, absent.submit(overview, overview.alertCycle.emissionIntent!!))
        assertFalse(absent.capabilities().channelExists)
    }

    @Test
    fun thrownPlatformPublicationIsSafeFailureNotSuccessfulSend() {
        val overview = alertOverview()
        val notifier = MonitorNotifier(context, object : TestPlatform() {
            override fun publish(notification: Notification) = error("synthetic publication failure")
        })
        assertEquals(SubmissionOutcome.FAILED, notifier.submit(overview, overview.alertCycle!!.emissionIntent!!))
    }

    @Test
    fun silentChannelRemainsSilentButCanStillAcceptVisibleNotification() {
        val overview = alertOverview()
        val silent = NotificationChannel(MonitorNotifier.CHANNEL_ID, "silent synthetic channel", NotificationManager.IMPORTANCE_HIGH)
            .apply { setSound(null, null) }
        var published = false
        val notifier = MonitorNotifier(context, object : TestPlatform() {
            override fun channel() = silent
            override fun createChannel(channel: NotificationChannel) = fail("Do not restore channel sound")
            override fun publish(notification: Notification) { published = true }
        })
        assertEquals(SubmissionOutcome.ACCEPTED, notifier.submit(overview, overview.alertCycle!!.emissionIntent!!))
        assertTrue(published)
        assertNull(silent.sound)
    }

    @Test
    fun appWideBlockAndPermissionRaceAreNotSuccessfulSubmissions() {
        val overview = alertOverview()
        val blocked = MonitorNotifier(context, object : TestPlatform() {
            override fun notificationsEnabled() = false
            override fun publish(notification: Notification) = fail("App notifications disabled")
        })
        assertEquals(SubmissionOutcome.PERMISSION_DENIED, blocked.submit(overview, overview.alertCycle!!.emissionIntent!!))
        val raced = MonitorNotifier(context, object : TestPlatform() {
            override fun publish(notification: Notification) = throw SecurityException("synthetic permission race")
        })
        assertEquals(SubmissionOutcome.PERMISSION_DENIED, raced.submit(overview, overview.alertCycle.emissionIntent!!))
    }

    @Test
    fun acceptedRealPublicationIsVisibleInOwnNotificationManagerAndCanBeCancelled() {
        if (Build.VERSION.SDK_INT >= 33) {
            assertTrue("POST_NOTIFICATIONS must be declared before runtime grant",
                Manifest.permission.POST_NOTIFICATIONS in context.packageManager.getPackageInfo(
                    context.packageName, PackageManager.GET_PERMISSIONS,
                ).requestedPermissions.orEmpty())
        }
        val notifier = MonitorNotifier(context)
        val overview = alertOverview()
        var outcome: SubmissionOutcome? = null
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeAndWaitForEvent(
            { outcome = notifier.submit(overview, overview.alertCycle!!.emissionIntent!!) },
            { it.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && it.packageName == context.packageName },
            5_000L,
        )
        assertEquals(SubmissionOutcome.ACCEPTED, outcome)
        assertTrue(notifier.capabilities().canSubmit)
        assertEquals(1, manager.activeNotifications.count { it.id == MonitorNotifier.NOTIFICATION_ID })
        notifier.cancel()
        // NotificationManager cancellation is asynchronous; acceptance is not instant disappearance.
    }

    @Test
    fun cancellationIsForwardedToNotificationPlatform() {
        var cancelled = false
        val notifier = MonitorNotifier(context, object : TestPlatform() {
            override fun cancel() { cancelled = true }
        })
        notifier.cancel()
        assertTrue(cancelled)
    }

    private open class TestPlatform : MonitorNotificationPlatform {
        override fun permissionGranted() = true
        override fun notificationsEnabled() = true
        override fun channel(): NotificationChannel? =
            NotificationChannel(MonitorNotifier.CHANNEL_ID, "synthetic channel", NotificationManager.IMPORTANCE_HIGH)
        override fun createChannel(channel: NotificationChannel) = Unit
        override fun publish(notification: Notification) = Unit
        override fun cancel() = Unit
    }
}

package br.com.c6tag.diagnostico.remote.probe

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProbeFullScreenTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var readFailingPreferences: ReadFailingPreferences
    private lateinit var store: ProbeSessionStore
    private lateinit var platform: FakePlatform
    private lateinit var access: FakeFullScreenAccess
    private lateinit var controller: ProbeNotificationController
    private var now = 10_000L

    @Before
    fun setUp() {
        preferences = context.getSharedPreferences(
            "probe-full-screen-test-${UUID.randomUUID()}",
            Context.MODE_PRIVATE,
        )
        readFailingPreferences = ReadFailingPreferences(preferences)
        store = ProbeSessionStore(context, readFailingPreferences)
        platform = FakePlatform()
        access = FakeFullScreenAccess()
        controller = newController()
    }

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
    }

    @Test
    fun optionOffOrUnavailableAccessKeepsOrdinaryNotificationAndStopAction() {
        access.status = ProbeFullScreenAccessStatus.GRANTED
        assertEquals(
            ProbeSubmissionOutcome.ACCEPTED,
            controller.submitCritical(useFullScreenIntent = false),
        )
        assertFallbackNotification(platform.published.single().second)

        platform.clearNotifications()
        controller = newController()
        access.status = ProbeFullScreenAccessStatus.DENIED
        assertEquals(
            ProbeSubmissionOutcome.ACCEPTED,
            controller.submitCritical(useFullScreenIntent = true),
        )
        assertFallbackNotification(platform.published.single().second)

        platform.clearNotifications()
        controller = newController()
        access.statusFailure = IllegalStateException("synthetic FSI capability read failure")
        assertEquals(
            ProbeSubmissionOutcome.ACCEPTED,
            controller.submitCritical(useFullScreenIntent = true),
        )
        assertFallbackNotification(platform.published.single().second)
        assertEquals(1, platform.attempted.size)
        assertEquals(1, platform.published.size)
    }

    @Test
    fun grantedAccessAndExplicitOptionCreateOnlyAnImmutableExplicitActivityPendingIntent() {
        access.status = ProbeFullScreenAccessStatus.GRANTED

        assertEquals(
            ProbeSubmissionOutcome.ACCEPTED,
            controller.submitCritical(useFullScreenIntent = true),
        )

        val notification = platform.published.single().second
        val occurrenceId = requireNotNull(store.currentOccurrenceId())
        val fullScreenIntent = requireNotNull(notification.fullScreenIntent)
        val expectedIntent = Intent(context, AlertProbeActivity::class.java).apply {
            action = ProbeNotificationController.SHOW_ALERT_ACTION
            data = Uri.parse("monitor-probe://screen/$occurrenceId")
            putExtra(ProbeNotificationController.EXTRA_OCCURRENCE_ID, occurrenceId)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val expectedPendingIntent = PendingIntent.getActivity(
            context,
            ProbeNotificationController.FULL_SCREEN_REQUEST_CODE,
            expectedIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        assertEquals(expectedPendingIntent, fullScreenIntent)
        if (android.os.Build.VERSION.SDK_INT >= 31) assertTrue(fullScreenIntent.isImmutable)
        assertEquals(1, notification.actions.size)
        assertNotNull(notification.actions.single().actionIntent)
        assertEquals(ProbeNotificationController.NOTIFICATION_ID, platform.published.single().first)
    }

    @Test
    fun capabilityRevocationAtPublicationRetriesOnceWithoutFullScreenOnSameOccurrence() {
        access.status = ProbeFullScreenAccessStatus.GRANTED
        platform.beforePublish = {
            if (platform.attempted.size == 1) {
                access.status = ProbeFullScreenAccessStatus.DENIED
                platform.nextPublishFailure = SecurityException("synthetic FSI restriction")
            }
        }

        assertEquals(
            ProbeSubmissionOutcome.ACCEPTED,
            controller.submitCritical(useFullScreenIntent = true),
        )

        assertEquals(2, platform.attempted.size)
        assertNotNull(platform.attempted.first().second.fullScreenIntent)
        assertNull(platform.attempted.last().second.fullScreenIntent)
        assertEquals(1, platform.published.size)
        assertNull(platform.published.single().second.fullScreenIntent)
        assertEquals(
            platform.attempted.first().second.actions.single().actionIntent,
            platform.attempted.last().second.actions.single().actionIntent,
        )
        assertEquals(
            platform.attempted.first().first,
            platform.attempted.last().first,
        )
        assertEquals(1, platform.createdChannels.size)
        assertTrue(platform.cancelledIds.isEmpty())
        assertNotNull(store.currentOccurrenceId())
    }

    @Test
    fun capabilityRevokedAfterQueryButBeforePublishSendsOnlyTheOrdinaryNotification() {
        access.statusesToReturn = mutableListOf(
            ProbeFullScreenAccessStatus.GRANTED,
            ProbeFullScreenAccessStatus.DENIED,
        )

        assertEquals(
            ProbeSubmissionOutcome.ACCEPTED,
            controller.submitCritical(useFullScreenIntent = true),
        )

        assertEquals(1, platform.attempted.size)
        assertNull(platform.attempted.single().second.fullScreenIntent)
        assertNull(platform.published.single().second.fullScreenIntent)
        assertNotNull(store.currentOccurrenceId())
    }

    @Test
    fun presentationRequiresDurableCurrentUnexpiredSessionFromThisProcess() {
        access.status = ProbeFullScreenAccessStatus.GRANTED
        assertEquals(
            ProbeSubmissionOutcome.ACCEPTED,
            controller.submitCritical(useFullScreenIntent = true),
        )
        val occurrenceId = requireNotNull(store.currentOccurrenceId())

        assertTrue(controller.isCurrentPresentationSession(occurrenceId))
        assertEquals(
            ProbeNotificationController.TIMEOUT_AFTER_MILLIS,
            controller.presentationRemainingMillis(occurrenceId),
        )
        assertFalse(controller.isCurrentPresentationSession("00000000-0000-0000-0000-000000000000"))

        val otherProcessController = newController(processIdentity = "another-process")
        assertFalse(otherProcessController.isCurrentPresentationSession(occurrenceId))

        now += ProbeNotificationController.TIMEOUT_AFTER_MILLIS + 1
        assertFalse(controller.isCurrentPresentationSession(occurrenceId))
        assertNull(controller.presentationRemainingMillis(occurrenceId))

        now = 10_000L
        assertTrue(controller.stop(occurrenceId))
        assertFalse(controller.isCurrentPresentationSession(occurrenceId))
    }

    @Test
    fun showIntentValidationRequiresMatchingActionDataIdCurrentProcessAndReadableSession() {
        assertEquals(ProbeSubmissionOutcome.ACCEPTED, controller.submitCritical())
        val firstId = requireNotNull(store.currentOccurrenceId())
        val firstIntent = showAlertIntent(firstId)

        assertTrue(controller.isCurrentPresentationIntent(firstIntent))
        assertFalse(
            controller.isCurrentPresentationIntent(
                showAlertIntent(firstId).setAction("not-the-probe-action"),
            ),
        )
        assertFalse(
            controller.isCurrentPresentationIntent(
                showAlertIntent(firstId).setData(Uri.parse("monitor-probe://screen/other")),
            ),
        )
        assertFalse(
            controller.isCurrentPresentationIntent(
                showAlertIntent(firstId).putExtra(
                    ProbeNotificationController.EXTRA_OCCURRENCE_ID,
                    "00000000-0000-0000-0000-000000000000",
                ),
            ),
        )
        assertFalse(
            controller.isCurrentPresentationIntent(showAlertIntent("00000000-0000-0000-0000-000000000000")),
        )

        assertEquals(ProbeSubmissionOutcome.ACCEPTED, controller.submitCritical())
        val replacementId = requireNotNull(store.currentOccurrenceId())
        assertFalse(controller.isCurrentPresentationIntent(firstIntent))
        assertTrue(controller.isCurrentPresentationIntent(showAlertIntent(replacementId)))

        val otherProcessController = newController(processIdentity = "another-process")
        assertFalse(otherProcessController.isCurrentPresentationIntent(showAlertIntent(replacementId)))

        val cancelCount = platform.cancelledIds.size
        readFailingPreferences.failStringReads = true
        assertFalse(
            "A previously known identity hint must not authorize presentation after a failed read",
            controller.isCurrentPresentationIntent(showAlertIntent(replacementId)),
        )
        assertEquals("Intent validation must never cancel", cancelCount, platform.cancelledIds.size)
    }

    @Test
    fun delayedTriggerCancellationAndReplacementIgnoreEvenManuallyRunStaleCallbacks() {
        val scheduler = FakeDelayScheduler()
        var submissions = 0
        val delayed = ProbeDelayedTrigger(scheduler) { submissions++ }

        delayed.schedule()
        val first = scheduler.entries.single()
        delayed.schedule()
        val second = scheduler.entries.last()
        assertTrue(first.cancelled)
        assertTrue(delayed.isPending)

        first.action()
        assertEquals(0, submissions)
        second.action()
        assertEquals(1, submissions)
        assertFalse(delayed.isPending)

        delayed.schedule()
        val cancelled = scheduler.entries.last()
        delayed.cancel()
        cancelled.action()
        assertEquals(1, submissions)
        assertFalse(delayed.isPending)
    }

    @Test
    fun delayedTriggerCanScheduleSessionExpiryAndRunItWithoutCancellingTheNotification() {
        assertEquals(ProbeSubmissionOutcome.ACCEPTED, controller.submitCritical())
        val occurrenceId = requireNotNull(store.currentOccurrenceId())
        val scheduler = FakeDelayScheduler()
        var expired = false
        val expiry = ProbeDelayedTrigger(scheduler) {
            expired = controller.presentationRemainingMillis(occurrenceId) == null
        }

        expiry.scheduleAfter(
            requireNotNull(controller.presentationRemainingMillis(occurrenceId)),
        )
        val expiryTask = scheduler.entries.single()
        assertEquals(ProbeNotificationController.TIMEOUT_AFTER_MILLIS, expiryTask.delayMillis)
        assertTrue(expiry.isPending)

        now += ProbeNotificationController.TIMEOUT_AFTER_MILLIS
        expiryTask.action()

        assertTrue(expired)
        assertFalse(expiry.isPending)
        assertEquals(occurrenceId, store.currentOccurrenceId())
        assertTrue(platform.cancelledIds.isEmpty())
    }

    private fun newController(processIdentity: String = "test-process") =
        ProbeNotificationController(
            context = context,
            store = store,
            platformOverride = platform,
            fullScreenAccessOverride = access,
            monotonicClock = { now },
            processIdentity = processIdentity,
        )

    private fun showAlertIntent(occurrenceId: String) =
        Intent(context, AlertProbeActivity::class.java).apply {
            action = ProbeNotificationController.SHOW_ALERT_ACTION
            data = Uri.parse("monitor-probe://screen/$occurrenceId")
            putExtra(ProbeNotificationController.EXTRA_OCCURRENCE_ID, occurrenceId)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    private fun assertFallbackNotification(notification: Notification) {
        assertNull(notification.fullScreenIntent)
        assertEquals(1, notification.actions.size)
        assertNotNull(notification.actions.single().actionIntent)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            assertTrue(notification.actions.single().actionIntent.isImmutable)
        }
    }

    private class FakeFullScreenAccess : ProbeFullScreenAccess {
        var status = ProbeFullScreenAccessStatus.DENIED
        var statusFailure: Exception? = null
        var statusesToReturn: MutableList<ProbeFullScreenAccessStatus>? = null
        var settingsOpens = 0

        override fun capabilityStatus(): ProbeFullScreenAccessStatus {
            statusFailure?.let { throw it }
            statusesToReturn?.takeIf { it.isNotEmpty() }?.let { return it.removeAt(0) }
            return status
        }

        override fun openSettings(activity: Activity): Boolean {
            settingsOpens++
            return true
        }
    }

    private class FakePlatform : ProbeNotificationPlatform {
        var beforePublish: (() -> Unit)? = null
        var nextPublishFailure: Exception? = null
        val createdChannels = mutableListOf<NotificationChannel>()
        val attempted = mutableListOf<Pair<Int, Notification>>()
        val published = mutableListOf<Pair<Int, Notification>>()
        val cancelledIds = mutableListOf<Int>()
        private var currentChannel: NotificationChannel? = null

        override fun permissionGranted() = true
        override fun notificationsEnabled() = true
        override fun channel(): NotificationChannel? = currentChannel
        override fun createChannel(channel: NotificationChannel) {
            createdChannels += channel
            currentChannel = channel
        }
        override fun publish(notificationId: Int, notification: Notification) {
            attempted += notificationId to notification
            beforePublish?.invoke()
            nextPublishFailure?.let {
                nextPublishFailure = null
                throw it
            }
            published += notificationId to notification
        }
        override fun cancel(notificationId: Int) {
            cancelledIds += notificationId
        }

        fun clearNotifications() {
            attempted.clear()
            published.clear()
        }
    }

    private class FakeDelayScheduler : ProbeDelayScheduler {
        val entries = mutableListOf<Entry>()

        override fun schedule(delayMillis: Long, action: () -> Unit): ProbeScheduledTask {
            return Entry(delayMillis, action).also { entries += it }
        }

        class Entry(
            val delayMillis: Long,
            val action: () -> Unit,
        ) : ProbeScheduledTask {
            var cancelled = false
            override fun cancel() {
                cancelled = true
            }
        }
    }

    private class ReadFailingPreferences(
        private val delegate: android.content.SharedPreferences,
    ) : android.content.SharedPreferences by delegate {
        var failStringReads = false

        override fun getString(key: String, defValue: String?): String? {
            check(!failStringReads) { "synthetic session read failure" }
            return delegate.getString(key, defValue)
        }
    }
}

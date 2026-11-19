package br.com.c6tag.diagnostico.remote.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.net.Uri
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.c6tag.diagnostico.monitor.alerts.MonitorNotifier
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProbeNotificationTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var readFailingPreferences: ReadFailingPreferences
    private lateinit var store: ProbeSessionStore
    private lateinit var platform: FakePlatform
    private lateinit var controller: ProbeNotificationController

    @Before
    fun setUp() {
        preferences = context.getSharedPreferences(
            "probe-test-${UUID.randomUUID()}",
            Context.MODE_PRIVATE,
        )
        readFailingPreferences = ReadFailingPreferences(preferences)
        store = ProbeSessionStore(context, readFailingPreferences)
        platform = FakePlatform()
        controller = ProbeNotificationController(context, store, platform)
    }

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
    }

    @Test
    fun criticalNotificationUsesPrivateSyntheticContentHighChannelAndTimeout() {
        assertEquals(ProbeSubmissionOutcome.ACCEPTED, controller.submitCritical())

        val notification = platform.published.single().second
        val channel = platform.createdChannels.single()
        assertEquals("monitor_critical_probe_v1", notification.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(Notification.CATEGORY_REMINDER, notification.category)
        assertEquals(30_000L, notification.timeoutAfter)
        assertEquals(0, notification.flags and Notification.FLAG_AUTO_CANCEL)
        assertNotEquals(0, notification.flags and Notification.FLAG_INSISTENT)
        assertEquals("Teste de alerta crítico", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals(
            "Dados sintéticos — não é seu saldo",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT),
        )
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, channel.sound)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION, channel.audioAttributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, channel.audioAttributes.contentType)
        assertEquals(Notification.VISIBILITY_PRIVATE, channel.lockscreenVisibility)
        assertFalse(channel.canBypassDnd())
        assertEquals(6_099, platform.published.single().first)
        assertNotEquals(MonitorNotifier.CHANNEL_ID, notification.channelId)
        assertNotEquals(MonitorNotifier.NOTIFICATION_ID, platform.published.single().first)
    }

    @Test
    fun stopActionIsImmutableExplicitAndUniquePerOccurrence() {
        controller.submitCritical()
        val firstId = store.currentOccurrenceId()!!
        val firstAction = platform.published.last().second.actions.single()
        assertEquals("Parar som", firstAction.title)
        assertEquals(expectedStopPendingIntent(firstId), firstAction.actionIntent)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            assertTrue(firstAction.actionIntent.isImmutable)
        }

        controller.submitCritical()
        val secondId = store.currentOccurrenceId()!!
        val secondAction = platform.published.last().second.actions.single()
        assertNotEquals(firstId, secondId)
        assertNotEquals(firstAction.actionIntent, secondAction.actionIntent)
        assertEquals(expectedStopPendingIntent(secondId), secondAction.actionIntent)
    }

    @Test
    fun deniedPermissionOrDisabledAppNotificationsNeverPublish() {
        platform.permission = false
        assertEquals(ProbeSubmissionOutcome.PERMISSION_DENIED, controller.submitCritical())
        assertTrue(platform.published.isEmpty())
        assertNull(store.currentOccurrenceId())

        platform.permission = true
        platform.appNotificationsEnabled = false
        assertEquals(ProbeSubmissionOutcome.PERMISSION_DENIED, controller.submitCritical())
        assertTrue(platform.published.isEmpty())
        assertNull(store.currentOccurrenceId())
    }

    @Test
    fun statusQueryIsReadOnlyAndPreservesStoredIdentityWhenPlatformReadsFail() {
        val initial = controller.readStatus()

        assertEquals(ProbeCapabilityStatus.GRANTED, initial.permission)
        assertEquals(ProbeCapabilityStatus.GRANTED, initial.appNotifications)
        assertEquals(ProbeChannelStatus.NOT_CREATED, initial.channel)
        assertEquals(ProbeSessionReadStatus.CONFIRMED_EMPTY, initial.sessionReadStatus)
        assertNull(initial.storedOccurrenceId)
        assertTrue(platform.createdChannels.isEmpty())
        assertTrue(platform.published.isEmpty())
        assertTrue(platform.cancelAttempts.isEmpty())

        platform.permission = false
        platform.appNotificationsEnabled = false
        platform.channelValue = NotificationChannel(
            ProbeNotificationController.CHANNEL_ID,
            "synthetic blocked channel",
            NotificationManager.IMPORTANCE_NONE,
        )
        val denied = controller.readStatus()
        assertEquals(ProbeCapabilityStatus.DENIED, denied.permission)
        assertEquals(ProbeCapabilityStatus.DENIED, denied.appNotifications)
        assertEquals(ProbeChannelStatus.BLOCKED, denied.channel)
        assertNull(denied.storedOccurrenceId)
        assertTrue(platform.createdChannels.isEmpty())
        assertTrue(platform.published.isEmpty())
        assertTrue(platform.cancelAttempts.isEmpty())

        platform.permission = true
        platform.appNotificationsEnabled = true
        platform.channelValue = NotificationChannel(
            ProbeNotificationController.CHANNEL_ID,
            "synthetic available channel",
            NotificationManager.IMPORTANCE_HIGH,
        )
        assertEquals(ProbeSubmissionOutcome.ACCEPTED, controller.submitCritical())
        val occurrenceId = store.currentOccurrenceId()
        platform.permissionFailure = IllegalStateException("permission state unavailable")
        platform.appNotificationsFailure = SecurityException("app notification state unavailable")
        platform.channelFailure = IllegalStateException("channel state unavailable")

        val unknown = controller.readStatus()
        assertEquals(ProbeCapabilityStatus.UNKNOWN, unknown.permission)
        assertEquals(ProbeCapabilityStatus.UNKNOWN, unknown.appNotifications)
        assertEquals(ProbeChannelStatus.UNKNOWN, unknown.channel)
        assertEquals(ProbeSessionReadStatus.OCCURRENCE_PRESENT, unknown.sessionReadStatus)
        assertEquals("platform read failures must not hide the stored stop identity", occurrenceId, unknown.storedOccurrenceId)
        assertEquals(1, platform.published.size)
        assertTrue(platform.createdChannels.isEmpty())
        assertTrue(platform.cancelAttempts.isEmpty())
    }

    @Test
    fun statusReadFailureRetainsPreviouslyKnownOccurrenceForPresentation() {
        assertEquals(ProbeSubmissionOutcome.ACCEPTED, controller.submitCritical())
        val occurrenceId = requireNotNull(store.currentOccurrenceId())
        val knownStatus = controller.readStatus()
        assertEquals(ProbeSessionReadStatus.OCCURRENCE_PRESENT, knownStatus.sessionReadStatus)
        assertEquals(occurrenceId, knownStatus.storedOccurrenceId)

        readFailingPreferences.failStringReads = true
        val status = controller.readStatus()

        assertEquals(
            "A failed storage read must not erase a previously known stop hint",
            occurrenceId,
            status.storedOccurrenceId,
        )
        assertEquals(ProbeSessionReadStatus.UNKNOWN, status.sessionReadStatus)
        assertEquals(1, platform.published.size)
        assertTrue(platform.cancelAttempts.isEmpty())
    }

    @Test
    fun stopReadFailureReturnsFalseWithoutCancellingAndMatchingRetryWorksAfterRecovery() {
        assertEquals(ProbeSubmissionOutcome.ACCEPTED, controller.submitCritical())
        val occurrenceId = requireNotNull(store.currentOccurrenceId())

        readFailingPreferences.failStringReads = true

        assertFalse(controller.stop(occurrenceId))
        assertTrue("An unreadable owner must never be cancelled", platform.cancelAttempts.isEmpty())

        readFailingPreferences.failStringReads = false
        assertTrue(controller.stop(occurrenceId))

        assertNull(store.currentOccurrenceId())
        assertEquals(listOf(ProbeNotificationController.NOTIFICATION_ID), platform.cancelledIds)
    }

    @Test
    fun staleStopCannotCancelReplacementAfterStorageReadRecovers() {
        assertEquals(ProbeSubmissionOutcome.ACCEPTED, controller.submitCritical())
        val oldOccurrenceId = requireNotNull(store.currentOccurrenceId())
        val replacementId = "synthetic-replacement-occurrence"
        val independentlyReadableStore = ProbeSessionStore(context, preferences)

        readFailingPreferences.failStringReads = true
        assertTrue(independentlyReadableStore.persistOccurrenceId(replacementId))

        assertFalse(controller.stop(oldOccurrenceId))
        assertTrue("A failed read must not cancel an unknown replacement", platform.cancelAttempts.isEmpty())

        readFailingPreferences.failStringReads = false
        assertFalse(controller.stop(oldOccurrenceId))
        assertEquals(replacementId, store.currentOccurrenceId())
        assertTrue("A stale stop hint must not cancel the replacement", platform.cancelAttempts.isEmpty())
        assertTrue(controller.stop(replacementId))
        assertEquals(listOf(ProbeNotificationController.NOTIFICATION_ID), platform.cancelledIds)
    }

    @Test
    fun initialSessionReadFailureHasNoOccurrenceHint() {
        readFailingPreferences.failStringReads = true

        val status = controller.readStatus()

        assertEquals(ProbeSessionReadStatus.UNKNOWN, status.sessionReadStatus)
        assertNull(status.storedOccurrenceId)
        assertTrue(platform.published.isEmpty())
        assertTrue(platform.cancelAttempts.isEmpty())
    }

    @Test
    fun missingOrBlockedChannelNeverReportsAccepted() {
        platform.createChannelOnRequest = false
        assertEquals(ProbeSubmissionOutcome.CHANNEL_BLOCKED, controller.submitCritical())
        assertTrue(platform.published.isEmpty())
        assertNull(store.currentOccurrenceId())

        platform.channelValue = NotificationChannel(
            "monitor_critical_probe_v1",
            "synthetic blocked channel",
            NotificationManager.IMPORTANCE_NONE,
        )
        assertEquals(ProbeSubmissionOutcome.CHANNEL_BLOCKED, controller.submitCritical())
        assertEquals(1, platform.createdChannels.size)
        assertTrue(platform.published.isEmpty())
        assertNull(store.currentOccurrenceId())
    }

    @Test
    fun existingCustomizedSilentChannelIsNotRecreatedOrOverwritten() {
        val customized = NotificationChannel(
            "monitor_critical_probe_v1",
            "user customized synthetic channel",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { setSound(null, null) }
        platform.channelValue = customized

        assertEquals(ProbeSubmissionOutcome.ACCEPTED, controller.submitCritical())

        assertTrue(platform.createdChannels.isEmpty())
        assertNull(customized.sound)
        assertSame(customized, platform.channelValue)
    }

    @Test
    fun sessionIsPersistedBeforePublishAndFailedPublicationCleansItsSession() {
        var persistedAtPublish: String? = null
        platform.beforePublish = { persistedAtPublish = store.currentOccurrenceId() }
        assertEquals(ProbeSubmissionOutcome.ACCEPTED, controller.submitCritical())
        assertEquals(store.currentOccurrenceId(), persistedAtPublish)

        val unrelatedKey = "unrelated-probe-test-value"
        preferences.edit().putString(unrelatedKey, "preserve").commit()
        store.clearCurrentIfMatches(store.currentOccurrenceId()!!)
        val newerOccurrenceId = UUID.randomUUID().toString()
        platform.beforePublish = {
            store.persistOccurrenceId(newerOccurrenceId)
            platform.postedOccurrenceId = newerOccurrenceId
        }
        platform.publishFailure = IllegalStateException("synthetic publish failure")
        assertEquals(ProbeSubmissionOutcome.FAILED, controller.submitCritical())
        assertEquals(newerOccurrenceId, store.currentOccurrenceId())
        assertEquals("preserve", preferences.getString(unrelatedKey, null))
        assertTrue("Do not cancel when ownership changed during failed publish", platform.cancelAttempts.isEmpty())
        assertEquals(newerOccurrenceId, platform.postedOccurrenceId)
        store.clearCurrentIfMatches(newerOccurrenceId)

        platform.beforePublish = null
        platform.publishFailure = SecurityException("synthetic permission race")
        assertEquals(ProbeSubmissionOutcome.PERMISSION_DENIED, controller.submitCritical())
        assertNull(store.currentOccurrenceId())
    }

    @Test
    fun notificationConstructionFailureClearsDurableSessionWithoutPublishing() {
        platform.channelValue = NotificationChannel(
            ProbeNotificationController.CHANNEL_ID,
            "existing synthetic channel",
            NotificationManager.IMPORTANCE_HIGH,
        )
        val throwingController = ProbeNotificationController(
            context,
            store,
            platform,
            notificationFactoryOverride = {
                throw IllegalStateException("synthetic notification construction failure")
            },
        )

        assertEquals(ProbeSubmissionOutcome.FAILED, throwingController.submitCritical())

        assertNull("Unpublished session must be cleared", store.currentOccurrenceId())
        assertTrue(platform.published.isEmpty())
        assertTrue(platform.cancelAttempts.isEmpty())
    }

    @Test
    fun failedPublicationWithSuccessfulCancellationClearsMatchingSession() {
        platform.publishFailure = IllegalStateException("synthetic pre-post failure")
        var occurrenceAtPublish: String? = null
        platform.beforePublish = { occurrenceAtPublish = store.currentOccurrenceId() }

        assertEquals(ProbeSubmissionOutcome.FAILED, controller.submitCritical())

        assertNotNull(occurrenceAtPublish)
        assertNull(store.currentOccurrenceId())
        assertEquals(listOf(6_099), platform.cancelledIds)
    }

    @Test
    fun failedPublicationAndFailedCancellationRetainIdentityForStopRetry() {
        platform.publishFailure = IllegalStateException("synthetic post-then-error")
        platform.postBeforeFailure = true
        platform.cancelFailure = IllegalStateException("synthetic cancellation failure")
        var occurrenceAtPublish: String? = null
        platform.beforePublish = {
            occurrenceAtPublish = store.currentOccurrenceId()
            platform.postedOccurrenceId = occurrenceAtPublish
        }

        assertEquals(ProbeSubmissionOutcome.FAILED, controller.submitCritical())

        val occurrenceId = requireNotNull(occurrenceAtPublish)
        assertEquals(occurrenceId, store.currentOccurrenceId())
        assertTrue(platform.postedIds.contains(6_099))
        assertEquals(occurrenceId, platform.postedOccurrenceId)

        assertFalse(controller.stop(occurrenceId))
        assertEquals(occurrenceId, store.currentOccurrenceId())
        assertTrue(platform.postedIds.contains(6_099))
        assertEquals(occurrenceId, platform.postedOccurrenceId)

        platform.cancelFailure = null
        assertTrue(controller.stop(occurrenceId))
        assertNull(store.currentOccurrenceId())
        assertFalse(platform.postedIds.contains(6_099))
        assertNull(platform.postedOccurrenceId)
    }

    @Test
    fun failedPreferenceCommitNeverPublishesNotification() {
        val commitFailingStore = ProbeSessionStore(
            context,
            CommitFailingPreferences(preferences),
        )
        val commitFailingController = ProbeNotificationController(
            context,
            commitFailingStore,
            platform,
        )

        assertEquals(ProbeSubmissionOutcome.FAILED, commitFailingController.submitCritical())
        assertTrue(platform.published.isEmpty())
    }

    @Test
    fun staleStopCannotCancelReplacementAndMatchingStopIsIdempotent() {
        controller.submitCritical()
        val oldId = store.currentOccurrenceId()!!
        controller.submitCritical()
        val currentId = store.currentOccurrenceId()!!
        assertNotEquals(oldId, currentId)
        val cancellationsAfterReplacement = platform.cancelledIds.size

        assertFalse(controller.stop(oldId))
        assertEquals(cancellationsAfterReplacement, platform.cancelledIds.size)
        assertTrue(controller.stop(currentId))
        assertFalse(controller.stop(currentId))
        assertNull(store.currentOccurrenceId())
        assertEquals(cancellationsAfterReplacement + 1, platform.cancelledIds.size)
        assertTrue(platform.cancelledIds.all { it == 6_099 })
    }

    @Test
    fun newStoreAndControllerInstanceCanStopPersistedSession() {
        controller.submitCritical()
        val occurrenceId = store.currentOccurrenceId()!!
        val newStore = ProbeSessionStore(context, preferences)
        val newController = ProbeNotificationController(context, newStore, platform)

        assertTrue(newController.stop(occurrenceId))
        assertNull(store.currentOccurrenceId())
        assertEquals(listOf(6_099), platform.cancelledIds)
    }

    @Test
    fun sharedStoreLockMakesConcurrentMatchingStopsAtomic() {
        controller.submitCritical()
        val occurrenceId = store.currentOccurrenceId()!!
        val secondStore = ProbeSessionStore(context, preferences)
        val secondController = ProbeNotificationController(context, secondStore, platform)
        val firstCancelEntered = java.util.concurrent.CountDownLatch(1)
        val releaseFirstCancel = java.util.concurrent.CountDownLatch(1)
        val secondAttempting = java.util.concurrent.CountDownLatch(1)
        val secondCancelEntered = java.util.concurrent.CountDownLatch(1)
        platform.onCancel = {
            if (Thread.currentThread().name == "probe-first-stop") {
                firstCancelEntered.countDown()
                check(releaseFirstCancel.await(5, java.util.concurrent.TimeUnit.SECONDS))
            } else if (Thread.currentThread().name == "probe-second-stop") {
                secondCancelEntered.countDown()
            }
        }
        var firstResult = false
        var secondResult = true
        val first = Thread({ firstResult = controller.stop(occurrenceId) }, "probe-first-stop")
        val second = Thread({
            secondAttempting.countDown()
            secondResult = secondController.stop(occurrenceId)
        }, "probe-second-stop")

        first.start()
        assertTrue(firstCancelEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        second.start()
        try {
            assertTrue(secondAttempting.await(5, java.util.concurrent.TimeUnit.SECONDS))
            awaitThreadState(second, Thread.State.BLOCKED)
            assertEquals(1, secondCancelEntered.count)
        } finally {
            releaseFirstCancel.countDown()
        }
        first.join(5_000)
        second.join(5_000)

        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertTrue(firstResult)
        assertFalse(secondResult)
        assertEquals(listOf(6_099), platform.cancelledIds)
    }

    private fun awaitThreadState(thread: Thread, state: Thread.State) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (thread.state != state && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertEquals("Second stop must be blocked on the shared process lock", state, thread.state)
    }

    private fun expectedStopPendingIntent(occurrenceId: String): PendingIntent {
        val intent = Intent(context, ProbeStopReceiver::class.java).apply {
            action = ProbeNotificationController.STOP_ACTION
            data = Uri.Builder()
                .scheme("monitor-probe")
                .authority("sound")
                .appendPath(occurrenceId)
                .build()
            putExtra(ProbeNotificationController.EXTRA_OCCURRENCE_ID, occurrenceId)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    internal class FakePlatform : ProbeNotificationPlatform {
        @Volatile
        var permission = true
        @Volatile
        var appNotificationsEnabled = true
        @Volatile
        var channelValue: NotificationChannel? = null
        @Volatile
        var permissionFailure: Exception? = null
        @Volatile
        var appNotificationsFailure: Exception? = null
        @Volatile
        var channelFailure: Exception? = null
        @Volatile
        var createChannelOnRequest = true
        @Volatile
        var publishFailure: Exception? = null
        @Volatile
        var postBeforeFailure = false
        @Volatile
        var cancelFailure: Exception? = null
        @Volatile
        var postedOccurrenceId: String? = null
        @Volatile
        var beforePublish: (() -> Unit)? = null
        @Volatile
        var onCancel: (() -> Unit)? = null

        val createdChannels = java.util.concurrent.CopyOnWriteArrayList<NotificationChannel>()
        val published = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, Notification>>()
        val postedIds = java.util.concurrent.CopyOnWriteArraySet<Int>()
        val cancelAttempts = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val cancelledIds = java.util.concurrent.CopyOnWriteArrayList<Int>()

        override fun permissionGranted(): Boolean {
            permissionFailure?.let { throw it }
            return permission
        }

        override fun notificationsEnabled(): Boolean {
            appNotificationsFailure?.let { throw it }
            return appNotificationsEnabled
        }

        override fun channel(): NotificationChannel? {
            channelFailure?.let { throw it }
            return channelValue
        }

        override fun createChannel(channel: NotificationChannel) {
            createdChannels += channel
            if (createChannelOnRequest) channelValue = channel
        }

        override fun publish(notificationId: Int, notification: Notification) {
            beforePublish?.invoke()
            if (postBeforeFailure) postedIds += notificationId
            publishFailure?.let { throw it }
            published += notificationId to notification
            postedIds += notificationId
        }

        override fun cancel(notificationId: Int) {
            cancelAttempts += notificationId
            onCancel?.invoke()
            cancelFailure?.let { throw it }
            cancelledIds += notificationId
            postedIds -= notificationId
            postedOccurrenceId = null
        }
    }

    private class CommitFailingPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String, value: String?): SharedPreferences.Editor {
                    editor.putString(key, value)
                    return this
                }

                override fun remove(key: String): SharedPreferences.Editor {
                    editor.remove(key)
                    return this
                }

                override fun commit(): Boolean = false
            }
        }
    }

    private class ReadFailingPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        @Volatile
        var failStringReads = false

        override fun getString(key: String, defValue: String?): String? {
            check(!failStringReads) { "synthetic session read failure" }
            return delegate.getString(key, defValue)
        }
    }
}

package br.com.c6tag.diagnostico.monitor.alerts

import android.Manifest
import android.app.NotificationManager
import android.app.Notification
import android.app.NotificationChannel
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import androidx.work.WorkInfo
import br.com.c6tag.diagnostico.CollectorApplication
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEvent
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEventType
import br.com.c6tag.diagnostico.monitor.MonitorAlertEffects
import br.com.c6tag.diagnostico.monitor.MonitorOverview
import br.com.c6tag.diagnostico.monitor.ConfirmReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AndroidAlertsIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun committedLowReferencePublishesRealFirstNotificationWithoutAnyWorkerOrTestDriver() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= 33) {
            assertTrue(Manifest.permission.POST_NOTIFICATIONS in context.packageManager.getPackageInfo(
                context.packageName, PackageManager.GET_PERMISSIONS,
            ).requestedPermissions.orEmpty())
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(MonitorNotifier.NOTIFICATION_ID)
        val workerCalls = AtomicInteger()
        WorkTestEnvironment(context) { workerCalls.incrementAndGet() }.use { work ->
            val notifier = MonitorNotifier(context)
            AlertHarness(context) { _, _ -> AndroidMonitorAlertEffects(notifier, work.scheduler) }.use { harness ->
                try {
                    instrumentation.uiAutomation.executeAndWaitForEvent(
                        { runBlocking {
                            harness.configureLow()
                            assertEquals(SubmissionOutcome.ACCEPTED, harness.coordinator.overview().alertCycle!!.lastSubmissionOutcome)
                        } },
                        { it.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && it.packageName == context.packageName },
                        5_000L,
                    )
                    val published = manager.activeNotifications.single { it.id == MonitorNotifier.NOTIFICATION_ID }
                    assertEquals(MonitorNotifier.CHANNEL_ID, published.notification.channelId)
                    assertTrue(published.notification.extras.getCharSequence("android.text").toString().contains("200,00"))
                    assertEquals(0, workerCalls.get())
                    assertEquals(1_100L, runBlocking { harness.coordinator.overview().alertCycle!!.lastSubmittedAtMs })
                    val jobs = work.manager.getWorkInfosForUniqueWork(LowBalanceScheduler.UNIQUE_WORK_NAME).get()
                        .filter { !it.state.isFinished }
                    assertEquals(1, jobs.size)
                    assertEquals(900_000L, jobs.single().initialDelayMillis)
                } finally {
                    notifier.cancel()
                }
            }
        }
    }

    @Test
    fun periodicWorkRespectsPersistedPauseAndLosesRaceToTrustedCredit() = runBlocking {
        val entered = Channel<Unit>(Channel.UNLIMITED)
        val release = Channel<Unit>()
        val exited = Channel<Unit>(Channel.UNLIMITED)
        val submissions = AtomicInteger()
        lateinit var harness: AlertHarness
        WorkTestEnvironment(context) {
            try {
                entered.send(Unit)
                release.receive()
                harness.coordinator.refresh()
            } finally {
                exited.trySend(Unit)
            }
        }.use { work ->
            harness = AlertHarness(context) { _, _ ->
                object : MonitorAlertEffects {
                    override fun synchronize(overview: MonitorOverview) = work.scheduler.synchronize(overview)
                    override fun submit(overview: MonitorOverview, intent: EmissionIntent): SubmissionOutcome {
                        submissions.incrementAndGet()
                        return SubmissionOutcome.ACCEPTED
                    }
                }
            }
            harness.use {
                harness.configureLow()
                val cycle = harness.coordinator.overview().alertCycle!!
                val id = work.manager.getWorkInfosForUniqueWork(LowBalanceScheduler.UNIQUE_WORK_NAME).get()
                    .single { !it.state.isFinished }.id
                harness.nowMs = 2_000L
                harness.coordinator.acknowledge(cycle.id)
                harness.nowMs = 3_000L
                work.driver.setInitialDelayMet(id)
                withTimeout(5_000L) { entered.receive() }
                val pausedTick = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(5_000L) { work.manager.getWorkInfoByIdFlow(id).first { it?.state == WorkInfo.State.ENQUEUED } }
                }
                release.send(Unit)
                withTimeout(5_000L) { exited.receive() }
                pausedTick.await()
                assertEquals(1, submissions.get())
                assertEquals(AlertStatus.PAUSED, harness.coordinator.overview().alertCycle!!.status)
                harness.nowMs = 1_802_000L
                work.driver.setPeriodDelayMet(id)
                withTimeout(5_000L) { entered.receive() }
                val resumedTick = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(5_000L) { work.manager.getWorkInfoByIdFlow(id).first { it?.state == WorkInfo.State.ENQUEUED } }
                }
                release.send(Unit)
                withTimeout(5_000L) { exited.receive() }
                resumedTick.await()
                assertEquals(2, submissions.get())
                harness.nowMs = 2_702_000L
                work.driver.setPeriodDelayMet(id)
                withTimeout(5_000L) { entered.receive() }
                harness.recorder.recordNotification("synthetic.bank") {
                    DiagnosticEvent(
                        DiagnosticEventType.POSTED_OR_UPDATED, harness.nowMs, postedAtMs = harness.nowMs - 1L,
                        packageName = "synthetic.bank", key = "synthetic-credit",
                        title = "Você recebeu um Pix", text = "Pix recebido no valor de R$ 200,00.",
                    )
                }
                withTimeout(5_000L) {
                    work.manager.getWorkInfoByIdFlow(id).first { it?.state == WorkInfo.State.CANCELLED }
                    exited.receive()
                }
                release.trySend(Unit)
                assertEquals(40_000L, harness.coordinator.overview().balanceCents)
                assertEquals(AlertStatus.CLOSED, harness.coordinator.overview().alertCycle!!.status)
                assertEquals(2, submissions.get())
            }
        }
    }

    @Test
    fun frameworkPendingIntentFinishesReceiverWithoutAcknowledgingAnOldCycle() = runBlocking {
        val application = context.applicationContext as CollectorApplication
        val before = application.monitorCoordinator.overview()
        val overview = alertOverview("stale-framework-cycle")
        val notification = MonitorNotifier(context).build(overview, overview.alertCycle!!.emissionIntent!!)!!
        val finished = CompletableDeferred<Unit>()
        notification.actions.single().actionIntent.send(
            context, 0, null, { _, _, _, _, _ -> finished.complete(Unit) }, Handler(Looper.getMainLooper()),
        )
        withTimeout(5_000L) { finished.await() }
        assertEquals(before, application.monitorCoordinator.overview())
    }

    @Test
    fun androidAdapterRequestsCancellationOnlyAfterPauseOrRecoveryIsPersisted() = runBlocking {
        val cancelledStates = mutableListOf<AlertStatus?>()
        WorkTestEnvironment(context) {}.use { work ->
            AlertHarness(context) { store, database ->
                val platform = object : MonitorNotificationPlatform {
                    override fun permissionGranted() = true
                    override fun notificationsEnabled() = true
                    override fun channel() = NotificationChannel(
                        MonitorNotifier.CHANNEL_ID, "synthetic", NotificationManager.IMPORTANCE_HIGH,
                    )
                    override fun createChannel(channel: NotificationChannel) = Unit
                    override fun publish(notification: Notification) = Unit
                    override fun cancel() {
                        assertFalse(database.writableDatabase.inTransaction())
                        cancelledStates += store.overview().alertCycle?.status
                    }
                }
                AndroidMonitorAlertEffects(MonitorNotifier(context, platform), work.scheduler)
            }.use { harness ->
                harness.configureLow()
                val id = work.manager.getWorkInfosForUniqueWork(LowBalanceScheduler.UNIQUE_WORK_NAME).get()
                    .single { !it.state.isFinished }.id
                harness.nowMs = 2_000L
                harness.coordinator.acknowledge(harness.coordinator.overview().alertCycle!!.id)
                assertEquals(AlertStatus.PAUSED, cancelledStates.last())
                assertEquals(id, work.manager.getWorkInfosForUniqueWork(LowBalanceScheduler.UNIQUE_WORK_NAME).get()
                    .single { !it.state.isFinished }.id)
                val session = harness.coordinator.beginReference().session!!
                harness.nowMs = 2_100L
                harness.coordinator.confirmReference(ConfirmReference(session.id, 1L, 30_000L, 30_000L, 0L))
                assertEquals(AlertStatus.CLOSED, cancelledStates.last())
                assertTrue(work.manager.getWorkInfosForUniqueWork(LowBalanceScheduler.UNIQUE_WORK_NAME).get()
                    .none { !it.state.isFinished })
            }
        }
    }
}

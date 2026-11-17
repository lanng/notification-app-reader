package br.com.c6tag.diagnostico.capture

import android.app.Notification
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEvent
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEventType
import br.com.c6tag.diagnostico.diagnostic.DiagnosticRecorder
import br.com.c6tag.diagnostico.diagnostic.DiagnosticStore
import br.com.c6tag.diagnostico.diagnostic.StoredDiagnosticEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationCaptureCoordinatorTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun filtersPackageBeforeMappingAndKeepsLifecycleAndNotificationQueueOrder() = runBlocking {
        val store = RecordingStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recorder = DiagnosticRecorder(store, TARGET_PACKAGE, scope = scope)
        val status = CaptureStatus(TARGET_PACKAGE)
        var mappingCalls = 0
        val coordinator = NotificationCaptureCoordinator(
            targetPackage = TARGET_PACKAGE,
            recorder = recorder,
            captureStatus = status,
            scope = scope,
            mapEvent = { sbn, type, observedAtMs, removalReason ->
                mappingCalls++
                DiagnosticEvent(
                    type = type,
                    observedAtMs = observedAtMs,
                    packageName = sbn.packageName,
                    removalReason = removalReason,
                )
            },
        )

        try {
            assertFalse(
                coordinator.enqueueNotification(
                    statusBarNotification(OTHER_PACKAGE),
                    DiagnosticEventType.POSTED_OR_UPDATED,
                    observedAtMs = 10L,
                ),
            )
            coordinator.recordLifecycle(DiagnosticEventType.LISTENER_CONNECTED, observedAtMs = 9L)
            assertTrue(
                coordinator.enqueueNotification(
                    statusBarNotification(TARGET_PACKAGE),
                    DiagnosticEventType.POSTED_OR_UPDATED,
                    observedAtMs = 10L,
                ),
            )
            assertTrue(
                coordinator.enqueueNotification(
                    statusBarNotification(TARGET_PACKAGE),
                    DiagnosticEventType.REMOVED,
                    observedAtMs = 11L,
                    removalReason = 7,
                ),
            )

            withTimeout(5_000L) {
                recorder.state.first { it.version == 3L }
            }

            assertEquals(2, mappingCalls)
            assertEquals(
                listOf(
                    DiagnosticEventType.LISTENER_CONNECTED,
                    DiagnosticEventType.POSTED_OR_UPDATED,
                    DiagnosticEventType.REMOVED,
                ),
                store.snapshot().map(DiagnosticEvent::type),
            )
            assertEquals(listOf(9L, 10L, 11L), store.snapshot().map(DiagnosticEvent::observedAtMs))
            assertEquals(7, store.snapshot().last().removalReason)
        } finally {
            coordinator.close()
            recorder.close()
            scope.cancel()
        }
    }

    @Test
    fun emptyTargetDisablesCaptureAndSnapshotFailuresUseSafeStatusCodes() = runBlocking {
        val store = RecordingStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recorder = DiagnosticRecorder(store, allowedPackage = "", scope = scope)
        val status = CaptureStatus(targetPackage = "")
        var mappingCalls = 0
        val coordinator = NotificationCaptureCoordinator(
            targetPackage = "",
            recorder = recorder,
            captureStatus = status,
            scope = scope,
            mapEvent = { _, _, _, _ ->
                mappingCalls++
                error("synthetic sensitive mapping failure")
            },
        )

        try {
            assertFalse(
                coordinator.enqueueNotification(
                    statusBarNotification(TARGET_PACKAGE),
                    DiagnosticEventType.POSTED_OR_UPDATED,
                    observedAtMs = 10L,
                ),
            )
            assertEquals(0, mappingCalls)

            coordinator.reportSnapshotQueryFailure(observedAtMs = 20L)
            assertEquals(CaptureFailureCode.ACTIVE_SNAPSHOT_QUERY_FAILED, status.state.value.lastError)
            assertTrue(store.snapshot().isEmpty())
        } finally {
            coordinator.close()
            recorder.close()
            scope.cancel()
        }
    }

    @Test
    fun mappingFailuresAreObservableWithoutPersistingExceptionText() = runBlocking {
        val store = RecordingStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recorder = DiagnosticRecorder(store, TARGET_PACKAGE, scope = scope)
        val status = CaptureStatus(TARGET_PACKAGE)
        val coordinator = NotificationCaptureCoordinator(
            targetPackage = TARGET_PACKAGE,
            recorder = recorder,
            captureStatus = status,
            scope = scope,
            mapEvent = { _, _, _, _ -> error("synthetic private payload must not escape") },
        )

        try {
            assertTrue(
                coordinator.enqueueNotification(
                    statusBarNotification(TARGET_PACKAGE),
                    DiagnosticEventType.POSTED_OR_UPDATED,
                    observedAtMs = 30L,
                ),
            )

            withTimeout(5_000L) {
                status.state.first { it.errorCount == 1L }
            }

            assertEquals(CaptureFailureCode.NOTIFICATION_MAPPING_FAILED, status.state.value.lastError)
            assertTrue(store.snapshot().isEmpty())
        } finally {
            coordinator.close()
            recorder.close()
            scope.cancel()
        }
    }

    @Suppress("DEPRECATION")
    private fun statusBarNotification(packageName: String) = StatusBarNotification(
        packageName,
        packageName,
        1,
        "synthetic-tag",
        10_000,
        0,
        0,
        Notification.Builder(context, "synthetic-test-channel").setContentText("synthetic").build(),
        Process.myUserHandle(),
        1_700_000_000_000L,
    )

    private class RecordingStore : DiagnosticStore {
        val events = mutableListOf<DiagnosticEvent>()

        override fun append(event: DiagnosticEvent): Long {
            synchronized(events) { events += event }
            return synchronized(events) { events.size.toLong() }
        }

        fun snapshot(): List<DiagnosticEvent> = synchronized(events) { events.toList() }

        override fun page(beforeId: Long?, limit: Int): List<StoredDiagnosticEvent> = emptyList()

        override fun clear() {
            synchronized(events) { events.clear() }
        }

        override fun <T> exportSnapshot(
            block: (br.com.c6tag.diagnostico.diagnostic.DiagnosticSnapshotMetadata, Sequence<StoredDiagnosticEvent>) -> T,
        ): T = block(
            br.com.c6tag.diagnostico.diagnostic.DiagnosticSnapshotMetadata(
                total = synchronized(events) { events.size.toLong() },
                minId = null,
                maxId = null,
            ),
            emptySequence(),
        )
    }

    private companion object {
        const val TARGET_PACKAGE = "synthetic.target.package"
        const val OTHER_PACKAGE = "synthetic.other.package"
    }
}

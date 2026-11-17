package br.com.c6tag.diagnostico.monitor.alerts

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.c6tag.diagnostico.monitor.MonitorAlertEffects
import br.com.c6tag.diagnostico.monitor.MonitorOverview
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlertActionReceiverTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun explicitAcknowledgmentPersistsPauseBeforeNotificationCancellation() = runBlocking {
        val cancelled = mutableListOf<AlertCycle?>()
        AlertHarness(context) { store, database ->
            object : MonitorAlertEffects {
                override fun synchronize(overview: MonitorOverview) {
                    if (overview.alertCycle?.status == AlertStatus.PAUSED) {
                        assertFalse(database.writableDatabase.inTransaction())
                        assertEquals(AlertStatus.PAUSED, store.overview().alertCycle!!.status)
                        cancelled += store.overview().alertCycle
                    }
                }
                override fun submit(overview: MonitorOverview, intent: EmissionIntent) = SubmissionOutcome.ACCEPTED
            }
        }.use { harness ->
            harness.configureLow()
            val cycle = harness.coordinator.overview().alertCycle!!
            harness.nowMs = 2_000L
            val result = AlertActionReceiver.dispatch(action(cycle.id), harness.coordinator)
            assertNotNull(result)
            assertNull(result!!.rejection)
            assertEquals(AlertStatus.PAUSED, result.overview.alertCycle!!.status)
            assertEquals(1, cancelled.size)
            assertEquals(1_802_000L, result.overview.alertCycle.pauseUntilMs)
            AlertActionReceiver.dispatch(action(cycle.id), harness.coordinator)
            assertEquals(1, cancelled.size)
            assertEquals(1_802_000L, harness.coordinator.overview().alertCycle!!.pauseUntilMs)
        }
    }

    @Test
    fun wrongActionMismatchedIdentityAndOldCycleDoNotPauseCurrentCycle() = runBlocking {
        AlertHarness(context) { _, _ ->
            object : MonitorAlertEffects {
                override fun synchronize(overview: MonitorOverview) = Unit
                override fun submit(overview: MonitorOverview, intent: EmissionIntent) = SubmissionOutcome.ACCEPTED
            }
        }.use { harness ->
            harness.configureLow()
            val cycle = harness.coordinator.overview().alertCycle!!
            assertNull(AlertActionReceiver.dispatch(action(cycle.id).setAction("other"), harness.coordinator))
            assertNull(AlertActionReceiver.dispatch(action(cycle.id).putExtra(AlertActionReceiver.EXTRA_CYCLE_ID, "other"),
                harness.coordinator))
            assertNull(AlertActionReceiver.dispatch(action(cycle.id).setData(null), harness.coordinator))
            assertNotNull(AlertActionReceiver.dispatch(action("old-cycle"), harness.coordinator))
            assertEquals(AlertStatus.ACTIVE, harness.coordinator.overview().alertCycle!!.status)
            assertNull(harness.coordinator.overview().alertCycle!!.pauseUntilMs)
        }
    }

    private fun action(cycleId: String) = Intent(context, AlertActionReceiver::class.java).apply {
        action = AlertActionReceiver.ACTION_ACKNOWLEDGE
        data = Uri.Builder().scheme("c6tag-monitor").authority("alert").appendPath(cycleId).appendPath("acknowledge").build()
        putExtra(AlertActionReceiver.EXTRA_CYCLE_ID, cycleId)
    }
}

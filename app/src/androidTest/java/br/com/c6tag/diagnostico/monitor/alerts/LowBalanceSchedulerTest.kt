package br.com.c6tag.diagnostico.monitor.alerts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.WorkInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LowBalanceSchedulerTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun activeCycleHasOneDelayedNetworkFreeJobWithoutResetOnDebitOrPause() {
        WorkTestEnvironment(context) {}.use { environment ->
            val overview = alertOverview()
            environment.scheduler.synchronize(overview)
            val active = environment.activeJobs()
            assertEquals(1, active.size)
            val first = active.single()
            assertEquals(900_000L, first.initialDelayMillis)
            assertEquals(900_000L, first.periodicityInfo!!.repeatIntervalMillis)
            assertEquals(NetworkType.NOT_REQUIRED, first.constraints.requiredNetworkType)
            environment.scheduler.synchronize(overview.copy(balanceCents = 19_000L))
            environment.scheduler.synchronize(overview.copy(alertCycle = overview.alertCycle!!.copy(
                status = AlertStatus.PAUSED, pauseUntilMs = 1_802_000L, emissionIntent = null,
            )))
            val unchanged = environment.activeJobs().single()
            assertEquals(first.id, unchanged.id)
            assertEquals(first.generation, unchanged.generation)
            assertEquals(first.nextScheduleTimeMillis, unchanged.nextScheduleTimeMillis)
        }
    }

    @Test
    fun intervalUpdatePreservesIdentityAndRecoveryCancelsUniqueWork() {
        WorkTestEnvironment(context) {}.use { environment ->
            val overview = alertOverview()
            environment.scheduler.synchronize(overview)
            assertEquals(1, environment.activeJobs().size)
            val first = environment.activeJobs().single()
            val updated = overview.copy(settings = overview.settings.copy(alerts = AlertSettings(30, 30)))
            environment.scheduler.synchronize(updated)
            val next = environment.activeJobs().single()
            assertEquals(first.id, next.id)
            assertEquals(1_800_000L, next.periodicityInfo!!.repeatIntervalMillis)
            environment.scheduler.synchronize(updated.copy(alertCycle = overview.alertCycle!!.copy(status = AlertStatus.CLOSED)))
            assertTrue(environment.activeJobs().isEmpty())
            assertEquals(WorkInfo.State.CANCELLED, environment.manager.getWorkInfoById(first.id).get()!!.state)
        }
    }

    @Test
    fun testDriverRunsDelayedAndRepeatedGatewayWithoutWaitingFifteenMinutes() = runBlocking {
        val entered = Channel<Unit>(Channel.UNLIMITED)
        var release = CompletableDeferred<Unit>()
        WorkTestEnvironment(context) {
            entered.send(Unit)
            release.await()
        }.use { environment ->
            environment.scheduler.synchronize(alertOverview())
            assertEquals(1, environment.activeJobs().size)
            val id = environment.activeJobs().single().id
            assertTrue(entered.tryReceive().isFailure)
            environment.driver.setInitialDelayMet(id)
            withTimeout(5_000L) { entered.receive() }
            val firstFinished = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000L) { environment.manager.getWorkInfoByIdFlow(id).first { it?.state == WorkInfo.State.ENQUEUED } }
            }
            release.complete(Unit)
            firstFinished.await()
            release = CompletableDeferred()
            environment.driver.setPeriodDelayMet(id)
            withTimeout(5_000L) { entered.receive() }
            val secondFinished = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000L) { environment.manager.getWorkInfoByIdFlow(id).first { it?.state == WorkInfo.State.ENQUEUED } }
            }
            release.complete(Unit)
            secondFinished.await()
            assertEquals(id, environment.activeJobs().single().id)
        }
    }

    @Test
    fun newCycleReplacesUnfinishedOldCycleJobInsteadOfKeepingItsDeadline() {
        WorkTestEnvironment(context) {}.use { environment ->
            environment.scheduler.synchronize(alertOverview("cycle-a"))
            val first = environment.activeJobs().single()
            environment.scheduler.synchronize(alertOverview("cycle-b"))
            val next = environment.activeJobs().single()
            assertNotEquals(first.id, next.id)
            val previous = environment.manager.getWorkInfoById(first.id).get()
            assertTrue("Replaced work must be cancelled or pruned", previous == null || previous.state == WorkInfo.State.CANCELLED)
            assertEquals(900_000L, next.initialDelayMillis)
        }
    }

    private fun WorkTestEnvironment.activeJobs(): List<WorkInfo> =
        manager.getWorkInfosForUniqueWork(LowBalanceScheduler.UNIQUE_WORK_NAME).get(10, TimeUnit.SECONDS)
            .filter { !it.state.isFinished }
}

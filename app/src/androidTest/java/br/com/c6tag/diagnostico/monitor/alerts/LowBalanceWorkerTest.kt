package br.com.c6tag.diagnostico.monitor.alerts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LowBalanceWorkerTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun workerReevaluatesGatewayInsteadOfTrustingObsoleteInputData() = runBlocking {
        var evaluations = 0
        val worker = TestListenableWorkerBuilder<LowBalanceWorker>(context)
            .setWorkerFactory(AlertWorkerFactory { evaluations++ })
            .setInputData(workDataOf("cycle_id" to "obsolete", "balance_cents" to 0L))
            .build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(1, evaluations)
    }

    @Test
    fun storageFailureRequestsRetryButCancellationIsNotConvertedToSuccess() = runBlocking {
        val failed = TestListenableWorkerBuilder<LowBalanceWorker>(context)
            .setWorkerFactory(AlertWorkerFactory { error("synthetic storage failure") }).build()
        assertEquals(ListenableWorker.Result.retry(), failed.doWork())
        val cancelled = TestListenableWorkerBuilder<LowBalanceWorker>(context)
            .setWorkerFactory(AlertWorkerFactory { throw CancellationException("synthetic cancellation") }).build()
        assertTrue(runCatching { cancelled.doWork() }.exceptionOrNull() is CancellationException)
    }
}

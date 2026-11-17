package br.com.c6tag.diagnostico.monitor.alerts

import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import br.com.c6tag.diagnostico.CollectorApplication
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

internal class AlertWorkerFactory(private val gateway: LowBalanceGateway) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        if (workerClassName == LowBalanceWorker::class.java.name) LowBalanceWorker(appContext, workerParameters, gateway) else null
}

internal class WorkTestEnvironment(context: Context, gateway: LowBalanceGateway) : AutoCloseable {
    init {
        runBlocking { (context.applicationContext as CollectorApplication).monitorCoordinator.overview() }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor())
            .setWorkerFactory(AlertWorkerFactory(gateway)).build())
    }
    val manager = WorkManager.getInstance(context)
    val driver = requireNotNull(WorkManagerTestInitHelper.getTestDriver(context))
    val scheduler = LowBalanceScheduler(manager)
    override fun close() {
        manager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
        WorkManagerTestInitHelper.closeWorkDatabase()
    }
}

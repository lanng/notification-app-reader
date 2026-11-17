package br.com.c6tag.diagnostico.monitor.alerts

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import br.com.c6tag.diagnostico.CollectorApplication
import kotlinx.coroutines.CancellationException

internal fun interface LowBalanceGateway {
    suspend fun reevaluate()
}

class LowBalanceWorker internal constructor(
    context: Context,
    parameters: WorkerParameters,
    private val gateway: LowBalanceGateway,
) : CoroutineWorker(context, parameters) {
    constructor(context: Context, parameters: WorkerParameters) : this(context, parameters, LowBalanceGateway {
        (context.applicationContext as CollectorApplication).monitorCoordinator.refresh()
    })

    override suspend fun doWork(): Result = try {
        // Read the current persisted state through the serial coordinator; inputData is not money.
        gateway.reevaluate()
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Result.retry()
    }
}

package br.com.c6tag.diagnostico.remote.probe

import android.os.Handler
import android.os.Looper

internal interface ProbeScheduledTask {
    fun cancel()
}

internal fun interface ProbeDelayScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): ProbeScheduledTask
}

internal class ProbeDelayedTrigger(
    private val scheduler: ProbeDelayScheduler,
    private val submit: () -> Unit,
) {
    private var generation = 0L
    private var scheduledTask: ProbeScheduledTask? = null

    val isPending: Boolean
        get() = scheduledTask != null

    fun schedule() = scheduleAfter(DELAY_MILLIS)

    fun scheduleAfter(delayMillis: Long) {
        require(delayMillis >= 0L)
        generation += 1
        val scheduledGeneration = generation
        scheduledTask?.cancel()
        scheduledTask = scheduler.schedule(delayMillis) {
            if (generation == scheduledGeneration) {
                scheduledTask = null
                submit()
            }
        }
    }

    fun cancel() {
        generation += 1
        scheduledTask?.cancel()
        scheduledTask = null
    }

    companion object {
        const val DELAY_MILLIS = 5_000L
    }
}

internal class HandlerProbeDelayScheduler : ProbeDelayScheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun schedule(delayMillis: Long, action: () -> Unit): ProbeScheduledTask {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMillis)
        return object : ProbeScheduledTask {
            override fun cancel() {
                handler.removeCallbacks(runnable)
            }
        }
    }
}

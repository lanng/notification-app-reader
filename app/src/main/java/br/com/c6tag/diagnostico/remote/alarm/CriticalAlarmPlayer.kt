package br.com.c6tag.diagnostico.remote.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import br.com.c6tag.diagnostico.remote.AlertSoundSession
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ActiveCriticalAlarm(
    val occurrenceId: String,
    val isGuest: Boolean,
    val balanceCents: Long?,
    val limitCents: Long,
    val cycleId: String?,
    val session: AlertSoundSession,
)

/**
 * Process-wide controller for the 30-second continuous alarm sound and vibration
 * used exclusively for CRITICAL low-balance alerts (both Host and Guest).
 */
internal object CriticalAlarmPlayer {
    const val DURATION_MS = 30_000L

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutableActive = MutableStateFlow<ActiveCriticalAlarm?>(null)
    val active: StateFlow<ActiveCriticalAlarm?> = mutableActive.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var autoStopRunnable: Runnable? = null

    fun start(
        context: Context,
        isGuest: Boolean,
        balanceCents: Long?,
        limitCents: Long,
        cycleId: String?,
    ): ActiveCriticalAlarm {
        val appContext = context.applicationContext
        val occurrenceId = UUID.randomUUID().toString()
        val startedAt = SystemClock.elapsedRealtime()
        val alarm = ActiveCriticalAlarm(
            occurrenceId = occurrenceId,
            isGuest = isGuest,
            balanceCents = balanceCents,
            limitCents = limitCents,
            cycleId = cycleId,
            session = AlertSoundSession(occurrenceId, startedAt),
        )

        synchronized(lock) {
            stopLocked()
            mutableActive.value = alarm
            startAudioLocked(appContext)
            startVibrationLocked(appContext)
            val stopTask = Runnable {
                stop(occurrenceId)
            }
            autoStopRunnable = stopTask
            mainHandler.postDelayed(stopTask, DURATION_MS)
        }
        return alarm
    }

    fun stop(expectedOccurrenceId: String? = null) {
        synchronized(lock) {
            val current = mutableActive.value
            if (expectedOccurrenceId != null && current != null && current.occurrenceId != expectedOccurrenceId) {
                return
            }
            stopLocked()
            mutableActive.value = null
        }
    }

    fun stopForRole(isGuest: Boolean) {
        synchronized(lock) {
            val current = mutableActive.value
            if (current != null && current.isGuest == isGuest) {
                stopLocked()
                mutableActive.value = null
            }
        }
    }

    private fun stopLocked() {
        autoStopRunnable?.let(mainHandler::removeCallbacks)
        autoStopRunnable = null
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
        } catch (_: RuntimeException) {
            // Safe audio release.
        } finally {
            mediaPlayer = null
        }
        try {
            vibrator?.cancel()
        } catch (_: RuntimeException) {
            // Safe vibrator release.
        } finally {
            vibrator = null
        }
    }

    private fun startAudioLocked(context: Context) {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return

        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
            mediaPlayer = player
        } catch (_: Exception) {
            mediaPlayer = null
        }
    }

    private fun startVibrationLocked(context: Context) {
        try {
            val resolvedVibrator = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            } ?: return

            if (!resolvedVibrator.hasVibrator()) return
            val effect = VibrationEffect.createWaveform(
                longArrayOf(0L, 600L, 350L, 600L, 700L),
                0,
            )
            resolvedVibrator.vibrate(effect)
            vibrator = resolvedVibrator
        } catch (_: Exception) {
            vibrator = null
        }
    }
}

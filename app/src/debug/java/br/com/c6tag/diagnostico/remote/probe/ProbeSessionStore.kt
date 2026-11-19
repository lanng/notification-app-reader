package br.com.c6tag.diagnostico.remote.probe

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

internal data class ProbeStoredOccurrence(
    val occurrenceId: String,
    val monotonicStartMillis: Long?,
    val processIdentity: String?,
)

internal class ProbeSessionStore(
    context: Context,
    preferences: SharedPreferences? = null,
) {
    private val preferences = preferences ?: context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun currentOccurrenceId(): String? = synchronized(processLock) {
        preferences.getString(KEY_OCCURRENCE_ID, null)
    }

    fun currentOccurrence(): ProbeStoredOccurrence? = synchronized(processLock) {
        val occurrenceId = preferences.getString(KEY_OCCURRENCE_ID, null) ?: return@synchronized null
        ProbeStoredOccurrence(
            occurrenceId = occurrenceId,
            monotonicStartMillis = if (preferences.contains(KEY_MONOTONIC_START)) {
                preferences.getLong(KEY_MONOTONIC_START, 0L)
            } else {
                null
            },
            processIdentity = preferences.getString(KEY_PROCESS_IDENTITY, null),
        )
    }

    internal fun persistOccurrenceId(occurrenceId: String): Boolean =
        synchronized(processLock) {
            preferences.edit()
                .putString(KEY_OCCURRENCE_ID, occurrenceId)
                .remove(KEY_MONOTONIC_START)
                .remove(KEY_PROCESS_IDENTITY)
                .commit()
        }

    internal fun persistOccurrence(
        occurrenceId: String,
        monotonicStartMillis: Long,
        processIdentity: String,
    ): Boolean = synchronized(processLock) {
        preferences.edit()
            .putString(KEY_OCCURRENCE_ID, occurrenceId)
            .putLong(KEY_MONOTONIC_START, monotonicStartMillis)
            .putString(KEY_PROCESS_IDENTITY, processIdentity)
            .commit()
    }

    internal fun clearCurrentIfMatches(expectedOccurrenceId: String): Boolean =
        synchronized(processLock) {
            if (preferences.getString(KEY_OCCURRENCE_ID, null) != expectedOccurrenceId) {
                return@synchronized false
            }
            preferences.edit()
                .remove(KEY_OCCURRENCE_ID)
                .remove(KEY_MONOTONIC_START)
                .remove(KEY_PROCESS_IDENTITY)
                .commit()
        }

    companion object {
        const val PREFERENCES_NAME = "critical_alert_probe"
        private const val KEY_OCCURRENCE_ID = "current_occurrence_id"
        private const val KEY_MONOTONIC_START = "current_occurrence_monotonic_start"
        private const val KEY_PROCESS_IDENTITY = "current_occurrence_process_identity"
        internal val processLock = Any()
        internal val processIdentity: String = UUID.randomUUID().toString()
    }
}

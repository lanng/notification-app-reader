package br.com.c6tag.diagnostico.remote.probe

import android.content.Context
import android.content.SharedPreferences

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

    internal fun persistOccurrenceId(occurrenceId: String): Boolean =
        synchronized(processLock) {
            preferences.edit().putString(KEY_OCCURRENCE_ID, occurrenceId).commit()
        }

    internal fun clearCurrentIfMatches(expectedOccurrenceId: String): Boolean =
        synchronized(processLock) {
            if (preferences.getString(KEY_OCCURRENCE_ID, null) != expectedOccurrenceId) {
                return@synchronized false
            }
            preferences.edit().remove(KEY_OCCURRENCE_ID).commit()
        }

    companion object {
        const val PREFERENCES_NAME = "critical_alert_probe"
        private const val KEY_OCCURRENCE_ID = "current_occurrence_id"
        internal val processLock = Any()
    }
}

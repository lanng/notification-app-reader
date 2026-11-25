package br.com.c6tag.diagnostico.remote.sync

import android.content.Context
import br.com.c6tag.diagnostico.monitor.FinancialEntryKind
import br.com.c6tag.diagnostico.monitor.ThemePreference
import br.com.c6tag.diagnostico.remote.SharedAlertSeverity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

internal interface GuestLocalStateStore {
    fun readAlertState(): GuestLocalAlertState
    fun writeAlertState(state: GuestLocalAlertState)
    fun readCachedSnapshot(): SharedMonitorSnapshot?
    fun writeCachedSnapshot(snapshot: SharedMonitorSnapshot?)
    fun readCachedHistory(): List<SharedHistoryEntry>
    fun writeCachedHistory(entries: List<SharedHistoryEntry>)
    fun readTheme(): ThemePreference
    fun writeTheme(theme: ThemePreference)
    fun readUploadedFcmToken(): String?
    fun writeUploadedFcmToken(token: String?)
    fun clearMonitorCache()
}

internal class SharedPreferencesGuestLocalStateStore(
    context: Context,
) : GuestLocalStateStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    override fun readAlertState(): GuestLocalAlertState {
        val pauseMinutes = prefs.getInt(KEY_PAUSE_MINUTES, 30)
            .takeIf { it in GuestAlertPolicy.SUPPORTED_PAUSE_MINUTES } ?: 30
        return GuestLocalAlertState(
            cycleKey = prefs.getString(KEY_CYCLE_KEY, null),
            pausedSeverity = prefs.getString(KEY_PAUSED_SEVERITY, null)?.let(::parseSeverityOrNull),
            pausedUntilMs = prefs.getNullableLong(KEY_PAUSED_UNTIL_MS),
            lastNotifiedAtMs = prefs.getNullableLong(KEY_LAST_NOTIFIED_AT_MS),
            lastNotifiedSeverity = prefs.getString(KEY_LAST_NOTIFIED_SEVERITY, null)?.let(::parseSeverityOrNull),
            pauseMinutes = pauseMinutes,
        )
    }

    override fun writeAlertState(state: GuestLocalAlertState) {
        prefs.edit().apply {
            putString(KEY_CYCLE_KEY, state.cycleKey)
            putString(KEY_PAUSED_SEVERITY, state.pausedSeverity?.name)
            putNullableLong(KEY_PAUSED_UNTIL_MS, state.pausedUntilMs)
            putNullableLong(KEY_LAST_NOTIFIED_AT_MS, state.lastNotifiedAtMs)
            putString(KEY_LAST_NOTIFIED_SEVERITY, state.lastNotifiedSeverity?.name)
            putInt(KEY_PAUSE_MINUTES, state.pauseMinutes)
        }.apply()
    }

    override fun readCachedSnapshot(): SharedMonitorSnapshot? {
        val raw = prefs.getString(KEY_CACHED_SNAPSHOT, null) ?: return null
        return try {
            gson.fromJson(raw, CachedSnapshotDto::class.java)?.toDomain()
        } catch (_: Exception) {
            null
        }
    }

    override fun writeCachedSnapshot(snapshot: SharedMonitorSnapshot?) {
        prefs.edit().apply {
            if (snapshot == null) {
                remove(KEY_CACHED_SNAPSHOT)
            } else {
                putString(KEY_CACHED_SNAPSHOT, gson.toJson(CachedSnapshotDto.fromDomain(snapshot)))
            }
        }.apply()
    }

    override fun readCachedHistory(): List<SharedHistoryEntry> {
        val raw = prefs.getString(KEY_CACHED_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<CachedHistoryDto>>() {}.type
            val list: List<CachedHistoryDto>? = gson.fromJson(raw, type)
            list?.mapNotNull(CachedHistoryDto::toDomain) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun writeCachedHistory(entries: List<SharedHistoryEntry>) {
        prefs.edit().apply {
            if (entries.isEmpty()) {
                remove(KEY_CACHED_HISTORY)
            } else {
                putString(
                    KEY_CACHED_HISTORY,
                    gson.toJson(entries.take(50).map(CachedHistoryDto::fromDomain)),
                )
            }
        }.apply()
    }

    override fun readTheme(): ThemePreference {
        val raw = prefs.getString(KEY_THEME, null) ?: return ThemePreference.SYSTEM
        return try {
            ThemePreference.valueOf(raw)
        } catch (_: IllegalArgumentException) {
            ThemePreference.SYSTEM
        }
    }

    override fun writeTheme(theme: ThemePreference) {
        prefs.edit().putString(KEY_THEME, theme.name).apply()
    }

    override fun readUploadedFcmToken(): String? = prefs.getString(KEY_UPLOADED_FCM_TOKEN, null)

    override fun writeUploadedFcmToken(token: String?) {
        prefs.edit().apply {
            if (token == null) remove(KEY_UPLOADED_FCM_TOKEN) else putString(KEY_UPLOADED_FCM_TOKEN, token)
        }.apply()
    }

    override fun clearMonitorCache() {
        prefs.edit()
            .remove(KEY_CYCLE_KEY)
            .remove(KEY_PAUSED_SEVERITY)
            .remove(KEY_PAUSED_UNTIL_MS)
            .remove(KEY_LAST_NOTIFIED_AT_MS)
            .remove(KEY_LAST_NOTIFIED_SEVERITY)
            .remove(KEY_CACHED_SNAPSHOT)
            .remove(KEY_CACHED_HISTORY)
            .remove(KEY_UPLOADED_FCM_TOKEN)
            .apply()
    }

    private fun parseSeverityOrNull(value: String): SharedAlertSeverity? = try {
        SharedAlertSeverity.valueOf(value)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun android.content.SharedPreferences.getNullableLong(key: String): Long? =
        if (contains(key)) getLong(key, 0L) else null

    private fun android.content.SharedPreferences.Editor.putNullableLong(key: String, value: Long?) {
        if (value == null) remove(key) else putLong(key, value)
    }

    private data class CachedSnapshotDto(
        val monitorId: String,
        val hostGeneration: String,
        val balanceCents: Long?,
        val limitCents: Long,
        val repeatMinutes: Int,
        val pauseMinutes: Int,
        val needsReference: Boolean,
        val severity: String,
        val alertCycleId: String?,
        val alertOpenedAtMs: Long?,
        val alertEmissionCount: Int,
        val guestAlertActive: Boolean,
        val guestAlertEligibleAtMs: Long?,
        val lastObservedAtMs: Long?,
        val referenceConfirmedAtMs: Long?,
        val syncedAtMs: Long,
        val snapshotVersion: Long,
        val serverTimeMs: Long,
    ) {
        fun toDomain(): SharedMonitorSnapshot? = try {
            SharedMonitorSnapshot(
                monitorId = monitorId,
                hostGeneration = hostGeneration,
                balanceCents = balanceCents,
                limitCents = limitCents,
                repeatMinutes = repeatMinutes,
                pauseMinutes = pauseMinutes,
                needsReference = needsReference,
                severity = SharedAlertSeverity.valueOf(severity),
                alertCycleId = alertCycleId,
                alertOpenedAtMs = alertOpenedAtMs,
                alertEmissionCount = alertEmissionCount,
                guestAlertActive = guestAlertActive,
                guestAlertEligibleAtMs = guestAlertEligibleAtMs,
                lastObservedAtMs = lastObservedAtMs,
                referenceConfirmedAtMs = referenceConfirmedAtMs,
                syncedAtMs = syncedAtMs,
                snapshotVersion = snapshotVersion,
                serverTimeMs = serverTimeMs,
            )
        } catch (_: Exception) {
            null
        }

        companion object {
            fun fromDomain(snapshot: SharedMonitorSnapshot) = CachedSnapshotDto(
                monitorId = snapshot.monitorId,
                hostGeneration = snapshot.hostGeneration,
                balanceCents = snapshot.balanceCents,
                limitCents = snapshot.limitCents,
                repeatMinutes = snapshot.repeatMinutes,
                pauseMinutes = snapshot.pauseMinutes,
                needsReference = snapshot.needsReference,
                severity = snapshot.severity.name,
                alertCycleId = snapshot.alertCycleId,
                alertOpenedAtMs = snapshot.alertOpenedAtMs,
                alertEmissionCount = snapshot.alertEmissionCount,
                guestAlertActive = snapshot.guestAlertActive,
                guestAlertEligibleAtMs = snapshot.guestAlertEligibleAtMs,
                lastObservedAtMs = snapshot.lastObservedAtMs,
                referenceConfirmedAtMs = snapshot.referenceConfirmedAtMs,
                syncedAtMs = snapshot.syncedAtMs,
                snapshotVersion = snapshot.snapshotVersion,
                serverTimeMs = snapshot.serverTimeMs,
            )
        }
    }

    private data class CachedHistoryDto(
        val hostEntryId: Long,
        val kind: String,
        val amountCents: Long,
        val balanceAfterCents: Long,
        val recordedAtMs: Long,
    ) {
        fun toDomain(): SharedHistoryEntry? = try {
            SharedHistoryEntry(
                hostEntryId = hostEntryId,
                kind = FinancialEntryKind.valueOf(kind),
                amountCents = amountCents,
                balanceAfterCents = balanceAfterCents,
                recordedAtMs = recordedAtMs,
            )
        } catch (_: Exception) {
            null
        }

        companion object {
            fun fromDomain(entry: SharedHistoryEntry) = CachedHistoryDto(
                hostEntryId = entry.hostEntryId,
                kind = entry.kind.name,
                amountCents = entry.amountCents,
                balanceAfterCents = entry.balanceAfterCents,
                recordedAtMs = entry.recordedAtMs,
            )
        }
    }

    private companion object {
        const val PREFS_NAME = "guest_monitor_state_v1"
        const val KEY_CYCLE_KEY = "cycle_key"
        const val KEY_PAUSED_SEVERITY = "paused_severity"
        const val KEY_PAUSED_UNTIL_MS = "paused_until_ms"
        const val KEY_LAST_NOTIFIED_AT_MS = "last_notified_at_ms"
        const val KEY_LAST_NOTIFIED_SEVERITY = "last_notified_severity"
        const val KEY_PAUSE_MINUTES = "pause_minutes"
        const val KEY_CACHED_SNAPSHOT = "cached_snapshot"
        const val KEY_CACHED_HISTORY = "cached_history"
        const val KEY_THEME = "guest_theme"
        const val KEY_UPLOADED_FCM_TOKEN = "uploaded_fcm_token"
    }
}

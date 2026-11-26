package br.com.c6tag.diagnostico.remote.sync

import br.com.c6tag.diagnostico.BuildConfig
import br.com.c6tag.diagnostico.monitor.FinancialEntryKind
import br.com.c6tag.diagnostico.monitor.HistoryFilter
import br.com.c6tag.diagnostico.remote.SharedAlertSeverity
import br.com.c6tag.diagnostico.remote.access.HttpsConnectionFactory
import br.com.c6tag.diagnostico.remote.access.PairingAccessJson
import br.com.c6tag.diagnostico.remote.access.SecretValue
import br.com.c6tag.diagnostico.remote.access.canonicalOwnerApiOrigin
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.io.StringWriter
import java.net.MalformedURLException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal interface SharedMonitorSyncClient {
    val canonicalOrigin: String?

    suspend fun publishHostSnapshot(
        monitorId: String,
        accessToken: SecretValue,
        upload: HostSnapshotUpload,
    ): SharedSyncResult<SharedMonitorSnapshot>

    suspend fun fetchMonitorSnapshot(
        monitorId: String,
        accessToken: SecretValue,
    ): SharedSyncResult<SharedMonitorSnapshot?>

    suspend fun fetchMonitorHistory(
        monitorId: String,
        accessToken: SecretValue,
        beforeEntryId: Long? = null,
        limit: Int = 30,
        filter: HistoryFilter = HistoryFilter.ALL,
    ): SharedSyncResult<SharedHistoryPage>

    suspend fun registerPushToken(
        accessToken: SecretValue,
        fcmToken: String,
    ): SharedSyncResult<Unit>

    suspend fun deletePushToken(
        accessToken: SecretValue,
    ): SharedSyncResult<Unit>
}

internal class HttpsSharedMonitorSyncClient(
    ownerApiOrigin: String = BuildConfig.OWNER_API_ORIGIN,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectionFactory: HttpsConnectionFactory = HttpsConnectionFactory { url ->
        val connection = url.openConnection()
        if (connection !is HttpsURLConnection) {
            throw IOException("HTTPS connection unavailable")
        }
        connection
    },
) : SharedMonitorSyncClient {
    override val canonicalOrigin: String? = canonicalOwnerApiOrigin(ownerApiOrigin)
    private val configFailure: SharedSyncFailureReason? = when {
        ownerApiOrigin.isEmpty() -> SharedSyncFailureReason.NOT_CONFIGURED
        canonicalOrigin == null -> SharedSyncFailureReason.INVALID_ORIGIN
        else -> null
    }

    override suspend fun publishHostSnapshot(
        monitorId: String,
        accessToken: SecretValue,
        upload: HostSnapshotUpload,
    ): SharedSyncResult<SharedMonitorSnapshot> {
        if (!PairingAccessJson.isCanonicalUuid(monitorId)) {
            return SharedSyncResult.Failure(SharedSyncFailureReason.MALFORMED_RESPONSE)
        }
        val body = encodeHostSnapshotUpload(upload)
        return execute(
            method = "PUT",
            path = "/api/v1/monitors/$monitorId/snapshot",
            accessToken = accessToken,
            body = body,
        ).flatMapSuccess { bytes ->
            val snapshot = parseSnapshotEnvelope(bytes, allowNull = false)
            if (snapshot == null) {
                SharedSyncResult.Failure(SharedSyncFailureReason.MALFORMED_RESPONSE)
            } else {
                SharedSyncResult.Success(snapshot)
            }
        }
    }

    override suspend fun fetchMonitorSnapshot(
        monitorId: String,
        accessToken: SecretValue,
    ): SharedSyncResult<SharedMonitorSnapshot?> {
        if (!PairingAccessJson.isCanonicalUuid(monitorId)) {
            return SharedSyncResult.Failure(SharedSyncFailureReason.MALFORMED_RESPONSE)
        }
        return execute(
            method = "GET",
            path = "/api/v1/monitors/$monitorId/snapshot",
            accessToken = accessToken,
            body = null,
        ).flatMapSuccess { bytes ->
            try {
                SharedSyncResult.Success(parseSnapshotEnvelope(bytes, allowNull = true))
            } catch (_: IllegalArgumentException) {
                SharedSyncResult.Failure(SharedSyncFailureReason.MALFORMED_RESPONSE)
            }
        }
    }

    override suspend fun fetchMonitorHistory(
        monitorId: String,
        accessToken: SecretValue,
        beforeEntryId: Long?,
        limit: Int,
        filter: HistoryFilter,
    ): SharedSyncResult<SharedHistoryPage> {
        if (!PairingAccessJson.isCanonicalUuid(monitorId)) {
            return SharedSyncResult.Failure(SharedSyncFailureReason.MALFORMED_RESPONSE)
        }
        val clampedLimit = limit.coerceIn(1, 100)
        val query = buildString {
            append("?limit=").append(clampedLimit)
            append("&filter=").append(filter.toApiQueryValue())
            if (beforeEntryId != null && beforeEntryId > 0L) {
                append("&beforeEntryId=").append(beforeEntryId)
            }
        }
        return execute(
            method = "GET",
            path = "/api/v1/monitors/$monitorId/history$query",
            accessToken = accessToken,
            body = null,
        ).flatMapSuccess { bytes ->
            val page = parseHistoryEnvelope(bytes)
            if (page == null) {
                SharedSyncResult.Failure(SharedSyncFailureReason.MALFORMED_RESPONSE)
            } else {
                SharedSyncResult.Success(page)
            }
        }
    }

    override suspend fun registerPushToken(
        accessToken: SecretValue,
        fcmToken: String,
    ): SharedSyncResult<Unit> {
        val trimmed = fcmToken.trim()
        if (trimmed.isEmpty() || trimmed.length > 512 || trimmed.any(Char::isWhitespace)) {
            return SharedSyncResult.Failure(SharedSyncFailureReason.MALFORMED_RESPONSE)
        }
        val writer = StringWriter()
        JsonWriter(writer).use { json ->
            json.beginObject()
            json.name("fcmToken").value(trimmed)
            json.endObject()
        }
        return execute(
            method = "PUT",
            path = "/api/v1/installation/push-token",
            accessToken = accessToken,
            body = writer.toString().toByteArray(StandardCharsets.UTF_8),
        ).flatMapSuccess { SharedSyncResult.Success(Unit) }
    }

    override suspend fun deletePushToken(accessToken: SecretValue): SharedSyncResult<Unit> =
        execute(
            method = "DELETE",
            path = "/api/v1/installation/push-token",
            accessToken = accessToken,
            body = null,
        ).flatMapSuccess { SharedSyncResult.Success(Unit) }

    private suspend fun execute(
        method: String,
        path: String,
        accessToken: SecretValue,
        body: ByteArray?,
    ): SharedSyncResult<ByteArray> {
        configFailure?.let { return SharedSyncResult.Failure(it) }
        val origin = canonicalOrigin ?: return SharedSyncResult.Failure(SharedSyncFailureReason.NOT_CONFIGURED)
        val bearer = accessToken.reveal()
        if (!PairingAccessJson.isSafeBearer(bearer)) {
            return SharedSyncResult.Failure(SharedSyncFailureReason.UNAUTHENTICATED)
        }
        if (body != null && body.size > MAX_REQUEST_BYTES) {
            return SharedSyncResult.Failure(SharedSyncFailureReason.MALFORMED_RESPONSE)
        }
        val url = try {
            URL(origin + path)
        } catch (_: MalformedURLException) {
            return SharedSyncResult.Failure(SharedSyncFailureReason.INVALID_ORIGIN)
        }

        return suspendCancellableCoroutine { continuation ->
            val activeConnection = AtomicReference<HttpsURLConnection?>()
            continuation.invokeOnCancellation {
                activeConnection.get()?.disconnect()
            }

            ioDispatcher.dispatch(continuation.context) {
                if (!continuation.isActive) return@dispatch
                var connection: HttpsURLConnection? = null
                val result: SharedSyncResult<ByteArray> = try {
                    connection = connectionFactory.open(url)
                    activeConnection.set(connection)
                    connection.requestMethod = method
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("Authorization", "Bearer $bearer")
                    connection.doInput = true
                    connection.doOutput = body != null
                    if (body != null) {
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.outputStream.use { it.write(body) }
                    }
                    connection.connect()
                    val status = connection.responseCode
                    when {
                        status == 401 -> SharedSyncResult.Failure(SharedSyncFailureReason.UNAUTHENTICATED)
                        status == 403 -> SharedSyncResult.Failure(SharedSyncFailureReason.FORBIDDEN)
                        status == 404 -> SharedSyncResult.Failure(SharedSyncFailureReason.NOT_FOUND)
                        status in 200..299 -> {
                            if (status == 204) {
                                SharedSyncResult.Success(ByteArray(0))
                            } else if (connection.contentLengthLong > MAX_RESPONSE_BYTES) {
                                SharedSyncResult.Failure(SharedSyncFailureReason.MALFORMED_RESPONSE)
                            } else {
                                val bytes = connection.inputStream?.use(::readBounded)
                                if (bytes == null) {
                                    SharedSyncResult.Failure(SharedSyncFailureReason.MALFORMED_RESPONSE)
                                } else {
                                    SharedSyncResult.Success(bytes)
                                }
                            }
                        }

                        status in 300..399 -> SharedSyncResult.Failure(SharedSyncFailureReason.MALFORMED_RESPONSE)
                        else -> SharedSyncResult.Failure(SharedSyncFailureReason.SERVER_ERROR)
                    }
                } catch (_: IOException) {
                    SharedSyncResult.Failure(SharedSyncFailureReason.NETWORK_UNAVAILABLE)
                } catch (_: RuntimeException) {
                    SharedSyncResult.Failure(SharedSyncFailureReason.NETWORK_UNAVAILABLE)
                } finally {
                    try {
                        connection?.disconnect()
                    } catch (_: RuntimeException) {
                        // Safe disconnect.
                    } finally {
                        if (connection != null) {
                            activeConnection.compareAndSet(connection, null)
                        }
                    }
                }
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
        }
    }

    private fun readBounded(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return output.toByteArray()
            if (output.size() + read > MAX_RESPONSE_BYTES) return null
            output.write(buffer, 0, read)
        }
    }

    private inline fun <T, R> SharedSyncResult<T>.flatMapSuccess(
        transform: (T) -> SharedSyncResult<R>,
    ): SharedSyncResult<R> = when (this) {
        is SharedSyncResult.Failure -> this
        is SharedSyncResult.Success -> transform(value)
    }

    internal companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_REQUEST_BYTES = 64 * 1024
        private const val MAX_RESPONSE_BYTES = 128 * 1024

        fun encodeHostSnapshotUpload(upload: HostSnapshotUpload): ByteArray {
            val writer = StringWriter()
            JsonWriter(writer).use { json ->
                json.beginObject()
                json.name("balanceCents")
                if (upload.balanceCents == null) json.nullValue() else json.value(upload.balanceCents)
                json.name("limitCents").value(upload.limitCents)
                json.name("repeatMinutes").value(upload.repeatMinutes)
                json.name("pauseMinutes").value(upload.pauseMinutes)
                json.name("needsReference").value(upload.needsReference)
                json.name("alertCycleId")
                if (upload.alertCycleId == null) json.nullValue() else json.value(upload.alertCycleId)
                json.name("alertOpenedAtMs")
                if (upload.alertOpenedAtMs == null) json.nullValue() else json.value(upload.alertOpenedAtMs)
                json.name("alertEmissionCount").value(upload.alertEmissionCount)
                json.name("lastObservedAtMs")
                if (upload.lastObservedAtMs == null) json.nullValue() else json.value(upload.lastObservedAtMs)
                json.name("referenceConfirmedAtMs")
                if (upload.referenceConfirmedAtMs == null) json.nullValue() else json.value(upload.referenceConfirmedAtMs)
                json.name("entries")
                json.beginArray()
                upload.entries.take(100).forEach { entry ->
                    json.beginObject()
                    json.name("hostEntryId").value(entry.hostEntryId)
                    json.name("kind").value(entry.kind.toApiKind())
                    json.name("amountCents").value(entry.amountCents)
                    json.name("balanceAfterCents").value(entry.balanceAfterCents)
                    json.name("recordedAtMs").value(entry.recordedAtMs)
                    json.endObject()
                }
                json.endArray()
                json.endObject()
            }
            return writer.toString().toByteArray(StandardCharsets.UTF_8)
        }

        fun parseSnapshotEnvelope(bytes: ByteArray, allowNull: Boolean): SharedMonitorSnapshot? {
            val text = String(bytes, StandardCharsets.UTF_8)
            var foundData = false
            var snapshot: SharedMonitorSnapshot? = null
            try {
                JsonReader(StringReader(text)).use { reader ->
                    reader.setStrictness(Strictness.STRICT)
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "data" -> {
                                foundData = true
                                if (reader.peek() == JsonToken.NULL) {
                                    reader.nextNull()
                                    if (!allowNull) throw IllegalArgumentException("Null snapshot")
                                    snapshot = null
                                } else {
                                    snapshot = parseSnapshotObject(reader)
                                }
                            }

                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
            } catch (e: Exception) {
                if (allowNull) throw IllegalArgumentException("Malformed snapshot", e)
                return null
            }
            if (!foundData) {
                if (allowNull) throw IllegalArgumentException("Missing data")
                return null
            }
            return snapshot
        }

        private fun parseSnapshotObject(reader: JsonReader): SharedMonitorSnapshot {
            var monitorId: String? = null
            var hostGeneration: String? = null
            var balanceCents: Long? = null
            var limitCents: Long? = null
            var repeatMinutes: Int? = null
            var pauseMinutes: Int? = null
            var needsReference: Boolean? = null
            var severity: SharedAlertSeverity? = null
            var alertCycleId: String? = null
            var alertOpenedAtMs: Long? = null
            var alertEmissionCount: Int? = null
            var guestAlertActive: Boolean? = null
            var guestAlertEligibleAtMs: Long? = null
            var lastObservedAtMs: Long? = null
            var referenceConfirmedAtMs: Long? = null
            var syncedAtMs: Long? = null
            var snapshotVersion: Long? = null
            var serverTimeMs: Long? = null

            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "monitorId" -> monitorId = reader.nextString()
                    "hostGeneration" -> hostGeneration = reader.nextString()
                    "balanceCents" -> balanceCents = reader.nextNullableLong()
                    "limitCents" -> limitCents = reader.nextLong()
                    "repeatMinutes" -> repeatMinutes = reader.nextInt()
                    "pauseMinutes" -> pauseMinutes = reader.nextInt()
                    "needsReference" -> needsReference = reader.nextBoolean()
                    "severity" -> severity = parseApiSeverity(reader.nextString())
                    "alertCycleId" -> alertCycleId = reader.nextNullableString()
                    "alertOpenedAtMs" -> alertOpenedAtMs = reader.nextNullableLong()
                    "alertEmissionCount" -> alertEmissionCount = reader.nextInt()
                    "guestAlertActive" -> guestAlertActive = reader.nextBoolean()
                    "guestAlertEligibleAtMs" -> guestAlertEligibleAtMs = reader.nextNullableLong()
                    "lastObservedAtMs" -> lastObservedAtMs = reader.nextNullableLong()
                    "referenceConfirmedAtMs" -> referenceConfirmedAtMs = reader.nextNullableLong()
                    "syncedAtMs" -> syncedAtMs = reader.nextLong()
                    "snapshotVersion" -> snapshotVersion = reader.nextLong()
                    "serverTimeMs" -> serverTimeMs = reader.nextLong()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            return SharedMonitorSnapshot(
                monitorId = requireNotNull(monitorId),
                hostGeneration = requireNotNull(hostGeneration),
                balanceCents = balanceCents,
                limitCents = requireNotNull(limitCents),
                repeatMinutes = requireNotNull(repeatMinutes),
                pauseMinutes = requireNotNull(pauseMinutes),
                needsReference = requireNotNull(needsReference),
                severity = requireNotNull(severity),
                alertCycleId = alertCycleId,
                alertOpenedAtMs = alertOpenedAtMs,
                alertEmissionCount = requireNotNull(alertEmissionCount),
                guestAlertActive = requireNotNull(guestAlertActive),
                guestAlertEligibleAtMs = guestAlertEligibleAtMs,
                lastObservedAtMs = lastObservedAtMs,
                referenceConfirmedAtMs = referenceConfirmedAtMs,
                syncedAtMs = requireNotNull(syncedAtMs),
                snapshotVersion = requireNotNull(snapshotVersion),
                serverTimeMs = requireNotNull(serverTimeMs),
            )
        }

        fun parseHistoryEnvelope(bytes: ByteArray): SharedHistoryPage? {
            val text = String(bytes, StandardCharsets.UTF_8)
            return try {
                var page: SharedHistoryPage? = null
                JsonReader(StringReader(text)).use { reader ->
                    reader.setStrictness(Strictness.STRICT)
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "data" -> page = parseHistoryData(reader)
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                page
            } catch (_: Exception) {
                null
            }
        }

        private fun parseHistoryData(reader: JsonReader): SharedHistoryPage {
            val entries = mutableListOf<SharedHistoryEntry>()
            var hasMore: Boolean? = null
            var nextBeforeEntryId: Long? = null

            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "entries" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            entries.add(parseHistoryEntry(reader))
                        }
                        reader.endArray()
                    }

                    "hasMore" -> hasMore = reader.nextBoolean()
                    "nextBeforeEntryId" -> nextBeforeEntryId = reader.nextNullableLong()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            return SharedHistoryPage(
                entries = entries,
                hasMore = requireNotNull(hasMore),
                nextBeforeEntryId = nextBeforeEntryId,
            )
        }

        private fun parseHistoryEntry(reader: JsonReader): SharedHistoryEntry {
            var hostEntryId: Long? = null
            var kind: FinancialEntryKind? = null
            var amountCents: Long? = null
            var balanceAfterCents: Long? = null
            var recordedAtMs: Long? = null

            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "hostEntryId" -> hostEntryId = reader.nextLong()
                    "kind" -> kind = parseApiKind(reader.nextString())
                    "amountCents" -> amountCents = reader.nextLong()
                    "balanceAfterCents" -> balanceAfterCents = reader.nextLong()
                    "recordedAtMs" -> recordedAtMs = reader.nextLong()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            return SharedHistoryEntry(
                hostEntryId = requireNotNull(hostEntryId),
                kind = requireNotNull(kind),
                amountCents = requireNotNull(amountCents),
                balanceAfterCents = requireNotNull(balanceAfterCents),
                recordedAtMs = requireNotNull(recordedAtMs),
            )
        }

        private fun JsonReader.nextNullableLong(): Long? =
            if (peek() == JsonToken.NULL) {
                nextNull()
                null
            } else {
                nextLong()
            }

        private fun JsonReader.nextNullableString(): String? =
            if (peek() == JsonToken.NULL) {
                nextNull()
                null
            } else {
                nextString()
            }

        private fun FinancialEntryKind.toApiKind(): String = when (this) {
            FinancialEntryKind.REFERENCE -> "Reference"
            FinancialEntryKind.TAG_DEBIT -> "TagDebit"
            FinancialEntryKind.PIX_CREDIT -> "PixCredit"
        }

        private fun parseApiKind(value: String): FinancialEntryKind = when (value) {
            "Reference" -> FinancialEntryKind.REFERENCE
            "TagDebit" -> FinancialEntryKind.TAG_DEBIT
            "PixCredit" -> FinancialEntryKind.PIX_CREDIT
            else -> throw IllegalArgumentException("Unknown entry kind: $value")
        }

        private fun parseApiSeverity(value: String): SharedAlertSeverity = when (value) {
            "Unknown" -> SharedAlertSeverity.UNKNOWN
            "Normal" -> SharedAlertSeverity.NORMAL
            "Attention" -> SharedAlertSeverity.ATTENTION
            "Critical" -> SharedAlertSeverity.CRITICAL
            else -> throw IllegalArgumentException("Unknown severity: $value")
        }
    }
}

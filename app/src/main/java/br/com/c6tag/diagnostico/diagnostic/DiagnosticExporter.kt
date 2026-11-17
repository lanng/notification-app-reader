package br.com.c6tag.diagnostico.diagnostic

import android.util.JsonWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

internal enum class DiagnosticExportError {
    DESTINATION_UNAVAILABLE,
    WRITE_FAILED,
}

internal sealed interface DiagnosticExportResult {
    data object Success : DiagnosticExportResult

    data class Failure(val error: DiagnosticExportError) : DiagnosticExportResult
}

internal class DiagnosticExporter(
    private val recorder: DiagnosticRecorder,
    private val targetPackage: String,
    private val appVersionName: String,
    private val appVersionCode: Long,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    /**
     * Runs inside the recorder's IO snapshot callback. The stream returned by
     * [openOutput] is owned and closed here before the cursor is released.
     */
    suspend fun export(openOutput: () -> OutputStream?): DiagnosticExportResult =
        recorder.exportSnapshot { metadata, events ->
            try {
                val output = openOutput()
                    ?: return@exportSnapshot DiagnosticExportResult.Failure(
                        DiagnosticExportError.DESTINATION_UNAVAILABLE,
                    )
                OutputStreamWriter(output, StandardCharsets.UTF_8).buffered().use { textWriter ->
                    JsonWriter(textWriter).apply {
                        beginObject()
                        name("schemaVersion").value(DOCUMENT_SCHEMA_VERSION)
                        name("appVersionName").value(appVersionName)
                        name("appVersionCode").value(appVersionCode)
                        name("targetPackage").value(targetPackage)
                        name("exportedAtMs").value(nowEpochMillis())
                        name("snapshot")
                        beginObject()
                        name("total").value(metadata.total)
                        name("minId").nullableValue(metadata.minId)
                        name("maxId").nullableValue(metadata.maxId)
                        endObject()
                        name("events").beginArray()
                        events.forEach { writeEvent(it) }
                        endArray()
                        endObject()
                        flush()
                    }
                }
                DiagnosticExportResult.Success
            } catch (_: Exception) {
                DiagnosticExportResult.Failure(DiagnosticExportError.WRITE_FAILED)
            }
        }

    private fun JsonWriter.writeEvent(stored: StoredDiagnosticEvent) {
        val event = stored.event
        beginObject()
        name("id").value(stored.id)
        name("type").value(event.type.name)
        name("observedAtMs").value(event.observedAtMs)
        name("postedAtMs").nullableValue(event.postedAtMs)
        name("metadata")
        beginObject()
        name("packageName").nullableValue(event.packageName)
        name("key").nullableValue(event.key)
        name("notificationId").nullableValue(event.notificationId?.toLong())
        name("tag").nullableValue(event.tag)
        name("groupKey").nullableValue(event.groupKey)
        name("groupSummary").nullableValue(event.groupSummary)
        endObject()
        name("title").nullableValue(event.title)
        name("text").nullableValue(event.text)
        name("bigText").nullableValue(event.bigText)
        name("lines").beginArray()
        event.lines.forEach { value(it) }
        endArray()
        name("removalReason").nullableValue(event.removalReason?.toLong())
        name("interpretation")
        val interpretation = event.interpretation
        if (interpretation == null) {
            nullValue()
        } else {
            beginObject()
            name("kind").value(interpretation.kind.name)
            name("amountCentsLong").nullableValue(interpretation.amountCents)
            name("reason").nullableValue(interpretation.reason)
            endObject()
        }
        endObject()
    }

    private fun JsonWriter.nullableValue(value: String?) {
        if (value == null) nullValue() else value(value)
    }

    private fun JsonWriter.nullableValue(value: Long?) {
        if (value == null) nullValue() else value(value)
    }

    private fun JsonWriter.nullableValue(value: Boolean?) {
        if (value == null) nullValue() else value(value)
    }

    private companion object {
        const val DOCUMENT_SCHEMA_VERSION = 1
    }
}

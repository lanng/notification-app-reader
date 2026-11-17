package br.com.c6tag.diagnostico.diagnostic

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticExporterTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun emptySnapshotIsVersionedUtf8JsonAndNullProviderIsNotSuccess() = withRecorder("empty") { recorder ->
        val exporter = exporter(recorder)
        val output = ByteArrayOutputStream()

        assertEquals(DiagnosticExportResult.Success, runBlocking { exporter.export { output } })

        val json = JSONObject(output.toString(Charsets.UTF_8.name()))
        assertEquals(1, json.getInt("schemaVersion"))
        assertEquals("2.4.1", json.getString("appVersionName"))
        assertEquals(20401L, json.getLong("appVersionCode"))
        assertEquals(TARGET_PACKAGE, json.getString("targetPackage"))
        assertEquals(EXPORTED_AT, json.getLong("exportedAtMs"))
        assertEquals(0L, json.getJSONObject("snapshot").getLong("total"))
        assertEquals(0, json.getJSONArray("events").length())
        assertEquals(
            DiagnosticExportResult.Failure(DiagnosticExportError.DESTINATION_UNAVAILABLE),
            runBlocking { exporter.export { null } },
        )
    }

    @Test
    fun exportsDistinctIdsInDescendingOrderAndPreservesPayloadAndLongIntegers() =
        withRecorder("payload") { recorder ->
            val repeated = richEvent(
                observedAtMs = Long.MIN_VALUE,
                postedAtMs = Long.MAX_VALUE,
                amountCents = Long.MAX_VALUE,
            )
            recorder.recordNotification(TARGET_PACKAGE) { repeated }
            recorder.recordNotification(TARGET_PACKAGE) { repeated }
            recorder.recordNotification(TARGET_PACKAGE) {
                DiagnosticEvent(
                    type = DiagnosticEventType.REMOVED,
                    observedAtMs = 0L,
                    packageName = TARGET_PACKAGE,
                    title = null,
                    text = "áé 漢字 😀 \"aspas\"\nlinha",
                )
            }
            val output = ByteArrayOutputStream()

            assertEquals(DiagnosticExportResult.Success, runBlocking { exporter(recorder).export { output } })

            val rawJson = output.toString(Charsets.UTF_8.name())
            val json = JSONObject(rawJson)
            val events = json.getJSONArray("events")
            assertEquals(3, events.length())
            val ids = (0 until events.length()).map { events.getJSONObject(it).getLong("id") }
            assertEquals(ids.distinct(), ids)
            assertEquals(ids.sortedDescending(), ids)
            val snapshot = json.getJSONObject("snapshot")
            assertEquals(ids.size.toLong(), snapshot.getLong("total"))
            assertEquals(ids.last(), snapshot.getLong("minId"))
            assertEquals(ids.first(), snapshot.getLong("maxId"))

            val exported = events.getJSONObject(1)
            assertEquals("POSTED_OR_UPDATED", exported.getString("type"))
            assertEquals(Long.MIN_VALUE, exported.getLong("observedAtMs"))
            assertEquals(Long.MAX_VALUE, exported.getLong("postedAtMs"))
            assertEquals("通知 \"引用\"\n第二行 — café", exported.getJSONObject("metadata").getString("key"))
            assertEquals("Título 漢字", exported.getString("title"))
            assertEquals("Texto\ncom quebra", exported.getString("text"))
            assertEquals("Texto expandido 🚗", exported.getString("bigText"))
            assertEquals("linha \"um\"", exported.getJSONArray("lines").getString(0))
            assertEquals("TAG_DEBIT", exported.getJSONObject("interpretation").getString("kind"))
            assertEquals(Long.MAX_VALUE, exported.getJSONObject("interpretation").getLong("amountCentsLong"))
            assertTrue(rawJson.contains("\"amountCentsLong\":${Long.MAX_VALUE}"))
            assertFalse(rawJson.contains("amountCentsLong\":${Long.MAX_VALUE}.0"))
            assertFalse(rawJson.contains("codecVersion"))
            assertFalse(rawJson.contains("payload_json"))

            val nullable = events.getJSONObject(0)
            assertTrue(nullable.isNull("postedAtMs"))
            assertTrue(nullable.isNull("title"))
            assertTrue(nullable.isNull("interpretation"))
            assertEquals("áé 漢字 😀 \"aspas\"\nlinha", nullable.getString("text"))
        }

    @Test
    fun opensAndClosesOutputOnRecorderWorkerAndReportsWriteOrCloseFailures() =
        withRecorder("failures") { recorder ->
            val exporter = exporter(recorder)
            var openThread: Thread? = null
            var wasClosed = false
            val failingOutput = object : OutputStream() {
                override fun write(value: Int) {
                    throw IOException("private-provider-detail")
                }

                override fun close() {
                    wasClosed = true
                }
            }

            val writeFailure = runBlocking {
                exporter.export {
                    openThread = Thread.currentThread()
                    failingOutput
                }
            }
            assertEquals(DiagnosticExportResult.Failure(DiagnosticExportError.WRITE_FAILED), writeFailure)
            assertTrue(wasClosed)
            assertFalse(Thread.currentThread() == openThread)

            val closeFailure = runBlocking {
                exporter.export {
                    object : ByteArrayOutputStream() {
                        override fun close() {
                            throw IOException("private-close-detail")
                        }
                    }
                }
            }
            assertEquals(DiagnosticExportResult.Failure(DiagnosticExportError.WRITE_FAILED), closeFailure)
            val openFailure = runBlocking {
                exporter.export { throw IOException("private-open-detail") }
            }
            assertEquals(DiagnosticExportResult.Failure(DiagnosticExportError.WRITE_FAILED), openFailure)
            assertTrue(runBlocking { recorder.page(limit = 10) }.isEmpty())
        }

    @Test
    fun appendQueuedAfterSnapshotBeginsRemainsInDatabaseButOutsideExportFile() =
        withRecorder("snapshot-order") { recorder ->
            runBlocking {
                recorder.recordNotification(TARGET_PACKAGE) {
                    richEvent(10L, 11L, 12L)
                }
                val openStarted = CountDownLatch(1)
                val releaseOutput = CountDownLatch(1)
                val exportingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                try {
                    val output = ByteArrayOutputStream()
                    val export = exportingScope.async {
                        exporter(recorder).export {
                            openStarted.countDown()
                            check(releaseOutput.await(5, TimeUnit.SECONDS))
                            output
                        }
                    }
                    assertTrue(withContext(Dispatchers.IO) { openStarted.await(5, TimeUnit.SECONDS) })
                    recorder.recordNotification(TARGET_PACKAGE) {
                        richEvent(20L, 21L, 22L)
                    }
                    releaseOutput.countDown()

                    assertEquals(DiagnosticExportResult.Success, export.await())
                    assertEquals(1, JSONObject(output.toString(Charsets.UTF_8.name())).getJSONArray("events").length())
                    assertEquals(2, recorder.page(limit = 10).size)
                } finally {
                    releaseOutput.countDown()
                    exportingScope.cancel()
                }
            }
        }

    @Test
    fun cancellingCallerDoesNotCloseOutputWhileAcceptedSnapshotIsWriting() =
        withRecorder("cancel-open-stream") { recorder ->
            runBlocking {
                recorder.recordNotification(TARGET_PACKAGE) {
                    richEvent(10L, 11L, 12L)
                }
                assertEquals(1, recorder.page(limit = 10).size)

                val writeStarted = CountDownLatch(1)
                val releaseWrite = CountDownLatch(1)
                val output = BlockingOutputStream(writeStarted, releaseWrite)
                val exportingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val export = exportingScope.async {
                    exporter(recorder).export { output }
                }
                try {
                    assertTrue(withContext(Dispatchers.IO) {
                        writeStarted.await(5, TimeUnit.SECONDS)
                    })
                    assertTrue(output.isWriteInProgress)

                    export.cancelAndJoin()

                    assertTrue(export.isCancelled)
                    assertEquals(0, output.closeCount)
                    assertTrue(output.isWriteInProgress)

                    releaseWrite.countDown()

                    // A queued page is a barrier behind the accepted snapshot command.
                    assertEquals(1, recorder.page(limit = 10).size)
                    assertEquals(1, output.closeCount)
                    assertFalse(output.closedDuringWrite)
                    assertFalse(output.isWriteInProgress)
                    assertEquals(
                        1,
                        JSONObject(String(output.toByteArray(), Charsets.UTF_8))
                            .getJSONArray("events")
                            .length(),
                    )

                    // Follow with a write operation to show the snapshot/cursor released storage.
                    recorder.clear()
                    assertTrue(recorder.page(limit = 10).isEmpty())
                    assertEquals(1, output.closeCount)
                } finally {
                    releaseWrite.countDown()
                    export.cancelAndJoin()
                    withTimeout(5_000) { recorder.page(limit = 10) }
                    exportingScope.cancel()
                }
            }
        }

    private fun exporter(recorder: DiagnosticRecorder) = DiagnosticExporter(
        recorder = recorder,
        targetPackage = TARGET_PACKAGE,
        appVersionName = "2.4.1",
        appVersionCode = 20_401L,
        nowEpochMillis = { EXPORTED_AT },
    )

    private class BlockingOutputStream(
        private val writeStarted: CountDownLatch,
        private val releaseWrite: CountDownLatch,
    ) : OutputStream() {
        private val bytes = ByteArrayOutputStream()
        private val closeCountValue = AtomicInteger()
        private val writeInProgress = AtomicBoolean()
        private val wasClosedDuringWrite = AtomicBoolean()

        val closeCount: Int
            get() = closeCountValue.get()
        val isWriteInProgress: Boolean
            get() = writeInProgress.get()
        val closedDuringWrite: Boolean
            get() = wasClosedDuringWrite.get()

        override fun write(value: Int) {
            withWriteBarrier { bytes.write(value) }
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            withWriteBarrier { bytes.write(buffer, offset, length) }
        }

        override fun close() {
            if (writeInProgress.get()) wasClosedDuringWrite.set(true)
            closeCountValue.incrementAndGet()
            bytes.close()
        }

        fun toByteArray(): ByteArray = bytes.toByteArray()

        private inline fun withWriteBarrier(write: () -> Unit) {
            writeInProgress.set(true)
            writeStarted.countDown()
            try {
                if (!releaseWrite.await(5, TimeUnit.SECONDS)) {
                    throw IOException("Timed out waiting for the test to release the write")
                }
                write()
            } finally {
                writeInProgress.set(false)
            }
        }
    }

    private fun richEvent(
        observedAtMs: Long,
        postedAtMs: Long,
        amountCents: Long,
    ) = DiagnosticEvent(
        type = DiagnosticEventType.POSTED_OR_UPDATED,
        observedAtMs = observedAtMs,
        postedAtMs = postedAtMs,
        packageName = TARGET_PACKAGE,
        key = "通知 \"引用\"\n第二行 — café",
        notificationId = Int.MIN_VALUE,
        tag = null,
        groupKey = "grupo",
        groupSummary = false,
        title = "Título 漢字",
        text = "Texto\ncom quebra",
        bigText = "Texto expandido 🚗",
        lines = listOf("linha \"um\"", "segunda"),
        interpretation = NotificationInterpretation(
            kind = CandidateKind.TAG_DEBIT,
            amountCents = amountCents,
        ),
    )

    private inline fun withRecorder(name: String, block: (DiagnosticRecorder) -> Unit) {
        val databaseName = "diagnostic-export-test-$name.db"
        context.deleteDatabase(databaseName)
        val database = DiagnosticDatabase(context, databaseName)
        val recorderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val recorder = DiagnosticRecorder(database, TARGET_PACKAGE, scope = recorderScope)
        try {
            block(recorder)
        } finally {
            runBlocking { recorder.closeAndJoin() }
            recorderScope.cancel()
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "br.com.c6bank"
        const val EXPORTED_AT = 1_800_000_123_456L
    }
}

package br.com.c6tag.diagnostico.remote.sync

import br.com.c6tag.diagnostico.monitor.FinancialEntryKind
import br.com.c6tag.diagnostico.remote.SharedAlertSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class SharedMonitorSyncClientTest {
    @Test
    fun encodesSanitizedHostSnapshotWithoutRawNotificationText() {
        val upload = HostSnapshotUpload(
            balanceCents = 29_999L,
            limitCents = 30_000L,
            repeatMinutes = 15,
            pauseMinutes = 30,
            needsReference = false,
            alertCycleId = "cycle-1",
            alertOpenedAtMs = 1_764_000_000_000L,
            alertEmissionCount = 2,
            lastObservedAtMs = 1_764_000_010_000L,
            referenceConfirmedAtMs = 1_764_000_000_000L,
            entries = listOf(
                SharedHistoryEntry(
                    hostEntryId = 42L,
                    kind = FinancialEntryKind.TAG_DEBIT,
                    amountCents = 1_250L,
                    balanceAfterCents = 29_999L,
                    recordedAtMs = 1_764_000_010_000L,
                ),
            ),
        )
        val json = String(
            HttpsSharedMonitorSyncClient.encodeHostSnapshotUpload(upload),
            StandardCharsets.UTF_8,
        )

        assertTrue(json.contains("\"balanceCents\":29999"))
        assertTrue(json.contains("\"limitCents\":30000"))
        assertTrue(json.contains("\"alertEmissionCount\":2"))
        assertTrue(json.contains("\"kind\":\"TagDebit\""))
        assertFalse(json.contains("title"))
        assertFalse(json.contains("payload"))
    }

    @Test
    fun parsesSnapshotEnvelopeAndNullDataCleanly() {
        val nullEnvelope = """{"data":null}""".toByteArray(StandardCharsets.UTF_8)
        assertNull(HttpsSharedMonitorSyncClient.parseSnapshotEnvelope(nullEnvelope, allowNull = true))
        assertNull(HttpsSharedMonitorSyncClient.parseSnapshotEnvelope(nullEnvelope, allowNull = false))

        val validEnvelope = """
            {
              "data": {
                "monitorId": "11111111-1111-4111-8111-111111111111",
                "hostGeneration": "2",
                "balanceCents": 14999,
                "limitCents": 30000,
                "repeatMinutes": 15,
                "pauseMinutes": 30,
                "needsReference": false,
                "severity": "Critical",
                "alertCycleId": "cycle-9",
                "alertOpenedAtMs": 1764000000000,
                "alertEmissionCount": 1,
                "guestAlertActive": true,
                "guestAlertEligibleAtMs": 1764000000000,
                "lastObservedAtMs": 1764000005000,
                "referenceConfirmedAtMs": 1763990000000,
                "syncedAtMs": 1764000006000,
                "snapshotVersion": 7,
                "serverTimeMs": 1764000007000
              }
            }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val parsed = HttpsSharedMonitorSyncClient.parseSnapshotEnvelope(validEnvelope, allowNull = true)
        assertNotNull(parsed)
        assertEquals(14_999L, parsed!!.balanceCents)
        assertEquals(SharedAlertSeverity.CRITICAL, parsed.severity)
        assertTrue(parsed.guestAlertActive)
        assertEquals(7L, parsed.snapshotVersion)
    }

    @Test
    fun parsesPaginatedHistoryEnvelope() {
        val historyJson = """
            {
              "data": {
                "entries": [
                  {
                    "hostEntryId": 100,
                    "kind": "PixCredit",
                    "amountCents": 5000,
                    "balanceAfterCents": 35000,
                    "recordedAtMs": 1764000010000
                  }
                ],
                "hasMore": true,
                "nextBeforeEntryId": 100
              }
            }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val page = HttpsSharedMonitorSyncClient.parseHistoryEnvelope(historyJson)
        assertNotNull(page)
        assertEquals(1, page!!.entries.size)
        assertEquals(FinancialEntryKind.PIX_CREDIT, page.entries.first().kind)
        assertTrue(page.hasMore)
        assertEquals(100L, page.nextBeforeEntryId)
    }
}

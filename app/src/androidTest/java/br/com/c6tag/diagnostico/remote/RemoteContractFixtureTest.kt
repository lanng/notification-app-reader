package br.com.c6tag.diagnostico.remote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteContractFixtureTest {
    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets

    @Test
    fun sharedStateFixtureDocumentsVersionedFinanceAndSeparateContactTime() {
        val shared = fixture("remote/v1/shared-attention.json")

        assertEquals(1, shared.getInt("schemaVersion"))
        assertEquals("sharedState", shared.getString("messageType"))
        assertDecimalString(shared, "hostGeneration", "1")
        assertDecimalString(shared, "stateVersion", "12")
        assertEquals("synthetic-monitor", shared.getString("monitorId"))
        assertEquals("synthetic-cycle", shared.getString("cycleId"))
        assertEquals("synthetic-attention", shared.getString("episodeId"))

        assertIntegerJsonNumber(shared, "balanceCents")
        assertIntegerJsonNumber(shared, "limitCents")
        assertIntegerJsonNumber(shared, "lastFinancialUpdateAtMs")
        assertIntegerJsonNumber(shared, "lastHostContactAtMs")
        assertEquals(28_000L, shared.getLong("balanceCents"))
        assertEquals(30_000L, shared.getLong("limitCents"))
        assertFalse(shared.getBoolean("needsReview"))
        assertEquals(1_000L, shared.getLong("lastFinancialUpdateAtMs"))
        assertEquals(2_000L, shared.getLong("lastHostContactAtMs"))

        val classified = classifySharedAlert(
            shared.getLong("balanceCents"),
            shared.getLong("limitCents"),
        )
        assertEquals("attention", shared.getString("severity"))
        assertEquals(classified.name.lowercase(), shared.getString("severity"))

        val nowMs = 3_601_999L
        assertEquals(
            HostContactState.RECENT,
            hostContactState(shared.getLong("lastHostContactAtMs"), nowMs),
        )
        assertEquals(
            HostContactState.STALE,
            hostContactState(shared.getLong("lastFinancialUpdateAtMs"), nowMs),
        )
    }

    @Test
    fun occurrenceFixtureHasStableIdentityAndNoFinancialAmounts() {
        val first = fixture("remote/v1/critical-occurrence.json")
        val repeated = fixture("remote/v1/critical-occurrence.json")

        assertEquals(1, first.getInt("schemaVersion"))
        assertEquals("alertOccurrence", first.getString("messageType"))
        assertDecimalString(first, "hostGeneration", "1")
        assertDecimalString(first, "stateVersion", "13")
        assertDecimalString(first, "dueNumber", "1")
        assertEquals("synthetic-monitor", first.getString("monitorId"))
        assertEquals("synthetic-cycle", first.getString("cycleId"))
        assertEquals("synthetic-critical", first.getString("episodeId"))
        assertEquals("synthetic-support", first.getString("recipientId"))
        assertEquals("critical", first.getString("severity"))

        val identity = occurrenceIdentity(first)
        assertEquals(identity, occurrenceIdentity(repeated))

        assertIntegerJsonNumber(first, "scheduledAtMs")
        assertIntegerJsonNumber(first, "expiresAtMs")
        assertEquals(600_000L, first.getLong("scheduledAtMs"))
        assertEquals(900_000L, first.getLong("expiresAtMs"))
        assertTrue(first.getLong("scheduledAtMs") < first.getLong("expiresAtMs"))
        assertEquals(300_000L, first.getLong("expiresAtMs") - first.getLong("scheduledAtMs"))

        assertFalse(first.has("balanceCents"))
        assertFalse(first.has("limitCents"))
    }

    private fun fixture(path: String): JSONObject =
        assets.open(path).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }

    private fun assertDecimalString(json: JSONObject, field: String, expected: String) {
        val value = json.get(field)
        assertTrue("$field must be a JSON string", value is String)
        assertEquals(expected, value)
        assertTrue("$field must contain decimal digits", (value as String).matches(Regex("\\d+")))
    }

    private fun assertIntegerJsonNumber(json: JSONObject, field: String) {
        val value = json.get(field)
        assertTrue(
            "$field must be an integer JSON number, got ${value::class.java.simpleName}",
            value is Int || value is Long,
        )
    }

    private fun occurrenceIdentity(json: JSONObject): List<String> = listOf(
        json.getString("monitorId"),
        json.getString("cycleId"),
        json.getString("episodeId"),
        json.getString("recipientId"),
        json.getString("dueNumber"),
    )
}

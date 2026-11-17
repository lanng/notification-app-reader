package br.com.c6tag.diagnostico.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStatusTest {
    @Test
    fun startsUnknownForEachInstanceAndKeepsTargetConfigurationSeparate() {
        val configured = CaptureStatus(targetPackage = "synthetic.bank.package")
        val unconfigured = CaptureStatus(targetPackage = "")

        assertEquals(CaptureConnectionState.UNKNOWN, configured.state.value.connection)
        assertNull(configured.state.value.connectionObservedAtMs)
        assertTrue(configured.state.value.targetPackageConfigured)
        assertEquals(CaptureConnectionState.UNKNOWN, unconfigured.state.value.connection)
        assertNull(unconfigured.state.value.connectionObservedAtMs)
        assertEquals(false, unconfigured.state.value.targetPackageConfigured)
    }

    @Test
    fun connectionTransitionsCarryOnlyTheirCurrentProcessEvidence() {
        val status = CaptureStatus(targetPackage = "synthetic.bank.package")

        status.markConnected(observedAtMs = 101L)
        assertEquals(CaptureConnectionState.CONNECTED, status.state.value.connection)
        assertEquals(101L, status.state.value.connectionObservedAtMs)

        status.markDisconnected(observedAtMs = 202L)
        assertEquals(CaptureConnectionState.DISCONNECTED, status.state.value.connection)
        assertEquals(202L, status.state.value.connectionObservedAtMs)

        status.markUnknown(observedAtMs = 303L)
        assertEquals(CaptureConnectionState.UNKNOWN, status.state.value.connection)
        assertEquals(303L, status.state.value.connectionObservedAtMs)
    }

    @Test
    fun failuresExposeSafeCodesAndRemainStickyWithoutRawMessages() {
        val status = CaptureStatus(targetPackage = "synthetic.bank.package")

        status.recordFailure(CaptureFailureCode.ACTIVE_SNAPSHOT_QUERY_FAILED, observedAtMs = 404L)
        status.recordFailure(CaptureFailureCode.NOTIFICATION_MAPPING_FAILED, observedAtMs = 505L)

        assertEquals(2L, status.state.value.errorCount)
        assertEquals(CaptureFailureCode.NOTIFICATION_MAPPING_FAILED, status.state.value.lastError)
        assertEquals(505L, status.state.value.lastErrorAtMs)
    }
}

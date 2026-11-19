package br.com.c6tag.diagnostico.remote.probe

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProbeStopReceiverTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var store: ProbeSessionStore
    private lateinit var platform: ProbeNotificationTest.FakePlatform
    private lateinit var controller: ProbeNotificationController

    @Before
    fun setUp() {
        preferences = context.getSharedPreferences(
            "probe-receiver-test-${UUID.randomUUID()}",
            Context.MODE_PRIVATE,
        )
        store = ProbeSessionStore(context, preferences)
        platform = ProbeNotificationTest.FakePlatform()
        controller = ProbeNotificationController(context, store, platform)
    }

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
    }

    @Test
    fun matchingExplicitStopCancelsOnlyTheSyntheticNotification() {
        assertEquals(ProbeSubmissionOutcome.ACCEPTED, controller.submitCritical())
        val occurrenceId = store.currentOccurrenceId()!!
        val receiver = ProbeStopReceiver { controller }

        receiver.onReceive(context, stopIntent(occurrenceId))

        assertNull(store.currentOccurrenceId())
        assertEquals(listOf(6_099), platform.cancelledIds)
    }

    @Test
    fun unexpectedActionMissingOrWrongOccurrenceIsIgnored() {
        controller.submitCritical()
        val occurrenceId = store.currentOccurrenceId()!!
        val receiver = ProbeStopReceiver { controller }

        receiver.onReceive(context, stopIntent(occurrenceId).setAction("unexpected"))
        receiver.onReceive(context, Intent().setAction(ProbeNotificationController.STOP_ACTION))
        receiver.onReceive(context, stopIntent(UUID.randomUUID().toString()))

        assertEquals(occurrenceId, store.currentOccurrenceId())
        assertTrue(platform.cancelledIds.isEmpty())
    }

    @Test
    fun stopReceiverIsDeclaredOnlyInDebugAndIsNotExported() {
        val info = runCatching {
            context.packageManager.getReceiverInfo(
                ComponentName(context, ProbeStopReceiver::class.java),
                0,
            )
        }.getOrNull()

        assertNotNull("ProbeStopReceiver must exist in the debug manifest", info)
        assertFalse(info!!.exported)
    }

    private fun stopIntent(occurrenceId: String): Intent = Intent(
        context,
        ProbeStopReceiver::class.java,
    ).apply {
        action = ProbeNotificationController.STOP_ACTION
        putExtra(ProbeNotificationController.EXTRA_OCCURRENCE_ID, occurrenceId)
    }
}

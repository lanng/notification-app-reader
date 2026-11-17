package br.com.c6tag.diagnostico.capture

import android.app.Notification
import android.app.Notification.Builder
import android.content.Context
import android.os.Bundle
import android.os.Process
import android.service.notification.StatusBarNotification
import android.text.SpannableString
import android.text.Spanned
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.c6tag.diagnostico.diagnostic.CandidateKind
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationSnapshotMapperTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun mapsOnlyDocumentedFieldsAndKeepsObservationSeparateFromPostTime() {
        val notification = notification {
            setContentTitle(spanned("Débito C6 Tag"))
            setContentText(spanned("Valor a debitar R$ 16,91."))
            setStyle(Notification.BigTextStyle().bigText(spanned("Valor a debitar R$ 16,91.")))
            setGroup("synthetic-group")
            addExtras(
                Bundle().apply {
                    putCharSequenceArray(
                        Notification.EXTRA_TEXT_LINES,
                        arrayOf(spanned("linha sintética 1"), spanned("linha sintética 2")),
                    )
                    putString("unrelated-private-extra", "must not be copied")
                },
            )
        }
        val sbn = statusBarNotification(notification = notification)

        val event = NotificationSnapshotMapper.map(
            sbn = sbn,
            type = DiagnosticEventType.POSTED_OR_UPDATED,
            observedAtMs = OBSERVED_AT,
        )

        assertEquals(DiagnosticEventType.POSTED_OR_UPDATED, event.type)
        assertEquals(OBSERVED_AT, event.observedAtMs)
        assertEquals(POSTED_AT, event.postedAtMs)
        assertEquals(TEST_PACKAGE, event.packageName)
        assertEquals(sbn.key, event.key)
        assertEquals(42, event.notificationId)
        assertEquals("synthetic-tag", event.tag)
        assertTrue(event.groupKey!!.contains("synthetic-group"))
        assertFalse(event.groupSummary!!)
        assertEquals("Débito C6 Tag", event.title)
        assertEquals("Valor a debitar R$ 16,91.", event.text)
        assertEquals("Valor a debitar R$ 16,91.", event.bigText)
        assertEquals(listOf("linha sintética 1", "linha sintética 2"), event.lines)
        assertEquals(CandidateKind.TAG_DEBIT, event.interpretation?.kind)
        assertEquals(1691L, event.interpretation?.amountCents)
    }

    @Test
    fun preservesNotificationEventTypesAndRemovalReason() {
        val sbn = statusBarNotification(
            notification = notification {
                setContentTitle("Aviso sintético")
                setContentText("Conteúdo fictício")
            },
        )

        val removed = NotificationSnapshotMapper.map(
            sbn,
            DiagnosticEventType.REMOVED,
            OBSERVED_AT,
            removalReason = 7,
        )
        val snapshot = NotificationSnapshotMapper.map(
            sbn,
            DiagnosticEventType.ACTIVE_SNAPSHOT,
            OBSERVED_AT + 1,
        )

        assertEquals(DiagnosticEventType.REMOVED, removed.type)
        assertEquals(7, removed.removalReason)
        assertEquals(DiagnosticEventType.ACTIVE_SNAPSHOT, snapshot.type)
        assertNull(snapshot.removalReason)
        assertEquals(POSTED_AT, snapshot.postedAtMs)
        assertEquals(OBSERVED_AT + 1, snapshot.observedAtMs)
    }

    @Test
    fun recognizesGroupSummaryFromNotificationFlagRatherThanGroupMembership() {
        val groupedNotification = notification {
            setContentTitle("Aviso sintético")
            setContentText("Conteúdo fictício")
            setGroup("synthetic-group")
        }
        val summaryNotification = notification {
            setContentTitle("Aviso sintético")
            setContentText("Resumo fictício")
            setGroup("synthetic-group")
            setGroupSummary(true)
        }

        val ordinaryGroupEvent = NotificationSnapshotMapper.map(
            statusBarNotification(groupedNotification),
            DiagnosticEventType.POSTED_OR_UPDATED,
            OBSERVED_AT,
        )
        val summaryEvent = NotificationSnapshotMapper.map(
            statusBarNotification(summaryNotification),
            DiagnosticEventType.POSTED_OR_UPDATED,
            OBSERVED_AT,
        )

        assertNotNull(ordinaryGroupEvent.groupKey)
        assertFalse(ordinaryGroupEvent.groupSummary!!)
        assertEquals(CandidateKind.UNKNOWN, ordinaryGroupEvent.interpretation?.kind)
        assertNotNull(summaryEvent.groupKey)
        assertTrue(summaryEvent.groupSummary!!)
        assertEquals(CandidateKind.GROUP_SUMMARY, summaryEvent.interpretation?.kind)
    }

    @Test
    fun classifiesSanitizedPixFieldsWithoutChangingTheirOriginalText() {
        val notification = notification {
            setContentTitle(spanned("Você recebeu um Pix"))
            setContentText(spanned("Pix recebido no valor de R$ 2,00."))
        }

        val event = NotificationSnapshotMapper.map(
            statusBarNotification(notification),
            DiagnosticEventType.POSTED_OR_UPDATED,
            OBSERVED_AT,
        )

        assertEquals("Você recebeu um Pix", event.title)
        assertEquals("Pix recebido no valor de R$ 2,00.", event.text)
        assertEquals(CandidateKind.PIX_CREDIT, event.interpretation?.kind)
        assertEquals(200L, event.interpretation?.amountCents)
    }

    @Test
    fun missingOptionalPayloadFieldsRemainAbsentAndClassifyAsUnknown() {
        val notification = notification { }

        val event = NotificationSnapshotMapper.map(
            statusBarNotification(notification),
            DiagnosticEventType.POSTED_OR_UPDATED,
            OBSERVED_AT,
        )

        assertNull(event.title)
        assertNull(event.text)
        assertNull(event.bigText)
        assertTrue(event.lines.isEmpty())
        assertEquals(CandidateKind.UNKNOWN, event.interpretation?.kind)
        assertFalse(event.interpretation?.reason.isNullOrBlank())
        assertNull(event.interpretation?.amountCents)
    }

    @Test
    fun optionalTextExtrasDoNotFillMissingTitleOrLines() {
        val notification = notification {
            setContentText(spanned("Texto sintético"))
            addExtras(
                Bundle().apply {
                    putCharSequence(Notification.EXTRA_BIG_TEXT, spanned("Texto longo sintético"))
                },
            )
        }

        val event = NotificationSnapshotMapper.map(
            statusBarNotification(notification),
            DiagnosticEventType.POSTED_OR_UPDATED,
            OBSERVED_AT,
        )

        assertNull(event.title)
        assertEquals("Texto sintético", event.text)
        assertEquals("Texto longo sintético", event.bigText)
        assertTrue(event.lines.isEmpty())
    }

    @Test
    fun lifecycleEventsCannotBeMappedFromNotificationPayload() {
        val sbn = statusBarNotification(notification { setContentText("synthetic") })

        assertThrows(IllegalArgumentException::class.java) {
            NotificationSnapshotMapper.map(
                sbn,
                DiagnosticEventType.LISTENER_CONNECTED,
                OBSERVED_AT,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            NotificationSnapshotMapper.map(
                sbn,
                DiagnosticEventType.LISTENER_DISCONNECTED,
                OBSERVED_AT,
            )
        }
    }

    private fun notification(block: Builder.() -> Unit): Notification =
        Builder(context, "synthetic-test-channel").apply(block).build()

    @Suppress("DEPRECATION")
    private fun statusBarNotification(
        notification: Notification,
        id: Int = 42,
        tag: String? = "synthetic-tag",
        postTime: Long = POSTED_AT,
    ) = StatusBarNotification(
        TEST_PACKAGE,
        TEST_PACKAGE,
        id,
        tag,
        10_000,
        0,
        0,
        notification,
        Process.myUserHandle(),
        postTime,
    )

    private fun spanned(value: String): Spanned = SpannableString(value)

    private companion object {
        const val TEST_PACKAGE = "synthetic.bank.package"
        const val POSTED_AT = 1_700_000_000_000L
        const val OBSERVED_AT = 1_800_000_000_000L
    }
}

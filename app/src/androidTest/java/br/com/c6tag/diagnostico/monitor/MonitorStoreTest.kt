package br.com.c6tag.diagnostico.monitor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.c6tag.diagnostico.diagnostic.DiagnosticDatabase
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEvent
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEventType
import br.com.c6tag.diagnostico.diagnostic.DiagnosticRecorder
import br.com.c6tag.diagnostico.CollectorApplication
import br.com.c6tag.diagnostico.monitor.storage.SqliteMonitorStore
import br.com.c6tag.diagnostico.monitor.alerts.AlertSettings
import br.com.c6tag.diagnostico.monitor.alerts.AlertStatus
import br.com.c6tag.diagnostico.monitor.alerts.SubmissionOutcome
import br.com.c6tag.diagnostico.monitor.alerts.AlertRejection
import br.com.c6tag.diagnostico.monitor.alerts.EmissionIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MonitorStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var nowMs = 2_000L

    @Test
    fun referenceIsAdjustmentAndNewDebitAppliesOnceSurvivingReopen() = withDatabase { name ->
        open(name).use { helper ->
            val store = SqliteMonitorStore(helper)
            assertNull(store.overview().balanceCents)
            val session = store.beginReference(1_000L).session
            assertNotNull(session)
            assertNull(store.confirmReference(ConfirmReference(session!!.id, null, 10_000L, 5_000L, 1_100L)).rejection)
            val event = debit()
            helper.append(event)
            helper.append(event.copy(observedAtMs = 2_100L))
            assertEquals(8_309L, store.overview().balanceCents)
            assertEquals(listOf(FinancialEntryKind.TAG_DEBIT, FinancialEntryKind.REFERENCE),
                store.history(limit = 100).map { it.kind })
            assertTrue(store.pending(limit = 100).isEmpty())
        }
        open(name).use { helper ->
            val store = SqliteMonitorStore(helper)
            assertEquals(8_309L, store.overview().balanceCents)
            helper.append(debit().copy(observedAtMs = 3_000L))
            assertEquals(8_309L, store.overview().balanceCents)
            assertEquals(2, store.history(limit = 100).size)
        }
    }

    @Test
    fun concurrentCandidatesRemainPendingAcrossClearAndOnlyExplicitIdsAreIncluded() = withStore { helper, store ->
        configure(store)
        val session = store.beginReference(3_000L).session!!
        nowMs = 3_100L
        val firstId = helper.append(debit().copy(observedAtMs = 3_100L, postedAtMs = 3_050L))
        helper.clear()
        val laterId = helper.append(debit().copy(
            key = "later", observedAtMs = 3_200L, postedAtMs = 3_150L, text = "Valor a debitar R$ 20,00. Praça B.",
        ))
        assertEquals(10_000L, store.overview().balanceCents)
        assertNull(store.confirmReference(ConfirmReference(
            session.id, 1L, 20_000L, 5_000L, 3_300L, setOf(firstId),
        )).rejection)
        assertEquals(20_000L, store.overview().balanceCents)
        assertEquals(listOf(laterId), store.pending(limit = 100).map { it.observationId })
        assertEquals(PendingReason.CONCURRENT_REFERENCE, store.pending(limit = 100).single().reason)
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM monitor_sources WHERE status='PROVISIONAL'", null,
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(0L, it.getLong(0))
        }
        assertTrue(store.overview().needsReference)
        val oldRevision = store.revisions.value
        assertEquals(RejectionCode.SESSION_NOT_ACTIVE, store.confirmReference(
            ConfirmReference(session.id, 1L, 1L, 1L, 3_400L),
        ).rejection)
        assertEquals(oldRevision, store.revisions.value)
    }

    @Test
    fun latePersistenceDuringClosedFormIsPendingEvenWithPostTimeBeforeNewReference() = withStore { helper, store ->
        configure(store)
        val session = store.beginReference(3_000L).session!!
        assertNull(store.confirmReference(ConfirmReference(session.id, 1L, 20_000L, 5_000L, 3_300L)).rejection)
        nowMs = 3_400L
        helper.append(debit().copy(observedAtMs = 3_100L, postedAtMs = 3_050L))
        assertEquals(20_000L, store.overview().balanceCents)
        assertEquals(PendingReason.CONCURRENT_REFERENCE, store.pending(limit = 100).single().reason)
    }

    @Test
    fun cancellationReevaluatesProvisionalSourceOnceEvenAfterRawEvidenceIsCleared() = withStore { helper, store ->
        configure(store)
        val session = store.beginReference(3_000L).session!!
        nowMs = 3_200L
        val event = debit().copy(observedAtMs = 3_100L, postedAtMs = 3_050L)
        helper.append(event)
        helper.clear()
        assertNull(store.cancelReference(session.id, 3_300L).rejection)
        assertEquals(8_309L, store.overview().balanceCents)
        assertTrue(store.pending(limit = 100).isEmpty())
        assertEquals(RejectionCode.SESSION_NOT_ACTIVE, store.cancelReference(session.id, 3_400L).rejection)
        helper.append(event.copy(observedAtMs = 3_500L))
        assertEquals(8_309L, store.overview().balanceCents)
        assertEquals(2, store.history(limit = 100).size)
    }

    @Test
    fun cancellationBeforeFirstReferenceDoesNotInventBalance() = withStore { helper, store ->
        val session = store.beginReference(1_000L).session!!
        helper.append(debit())
        assertNull(store.cancelReference(session.id, 2_100L).rejection)
        assertNull(store.overview().balanceCents)
        assertTrue(store.history(limit = 100).isEmpty())
    }

    @Test
    fun expirationRetainsCandidatesRejectsConfirmationAndDoesNotFreezeLaterCalculation() = withStore { helper, store ->
        configure(store)
        val session = store.beginReference(3_000L).session!!
        nowMs = 3_200L
        helper.append(debit().copy(observedAtMs = 3_100L, postedAtMs = 3_050L))
        nowMs = 903_000L
        store.refresh(nowMs)
        assertNull(store.overview().referenceSession)
        assertTrue(store.overview().needsReference)
        assertEquals(1, store.pending(limit = 100).size)
        assertEquals(RejectionCode.SESSION_EXPIRED, store.confirmReference(
            ConfirmReference(session.id, 1L, 1L, 1L, nowMs),
        ).rejection)
        helper.append(debit().copy(
            key = "after-expiry", observedAtMs = 903_100L, postedAtMs = 903_050L,
            text = "Valor a debitar R$ 1,00. Outra praça.",
        ))
        assertEquals(9_900L, store.overview().balanceCents)
        assertEquals(1, store.pending(limit = 100).size)
    }

    @Test
    fun pendingResolutionIsIdempotentAndOldReferenceCommandCannotChangeCorrection() = withStore { helper, store ->
        configure(store)
        helper.append(debit())
        helper.append(debit().copy(observedAtMs = 2_200L, postedAtMs = 2_100L))
        val pending = store.pending(limit = 100).single()
        val command = ResolvePending(pending.id, 1L, PendingChoice.CONFIRM_NEW, 2_300L)
        assertNull(store.resolvePending(command).rejection)
        assertEquals(6_618L, store.overview().balanceCents)
        assertNull(store.resolvePending(command).rejection)
        assertEquals(6_618L, store.overview().balanceCents)
        val session = store.beginReference(3_000L).session!!
        store.confirmReference(ConfirmReference(session.id, 1L, 20_000L, 5_000L, 3_300L))
        assertEquals(RejectionCode.STALE_REFERENCE, store.resolvePending(command).rejection)
        assertEquals(20_000L, store.overview().balanceCents)
    }

    @Test
    fun correctionAbsorbsOldPendingWithoutIncomeAndUnreadableValueCannotBeEdited() = withStore { helper, store ->
        configure(store)
        helper.append(debit().copy(text = "Valor a debitar R$ 16,"))
        val pending = store.pending(limit = 100).single()
        assertEquals(RejectionCode.UNREADABLE_AMOUNT, store.resolvePending(
            ResolvePending(pending.id, 1L, PendingChoice.CONFIRM_NEW, 2_300L),
        ).rejection)
        val session = store.beginReference(3_000L).session!!
        store.confirmReference(ConfirmReference(session.id, 1L, -100L, 5_000L, 3_300L))
        assertEquals(-100L, store.overview().balanceCents)
        assertTrue(store.pending(limit = 100).isEmpty())
        assertTrue(store.history(limit = 100).all { it.kind == FinancialEntryKind.REFERENCE })
    }

    @Test
    fun financialWriteRollbackLeavesRawBalanceAndPublishedRevisionUnchanged() = withStore { helper, store ->
        configure(store)
        val before = store.overview()
        val revision = store.revisions.value
        helper.writableDatabase.execSQL(
            """CREATE TRIGGER reject_movement BEFORE INSERT ON monitor_entries
               WHEN NEW.source_id IS NOT NULL
               BEGIN SELECT RAISE(ABORT,'synthetic financial failure'); END""",
        )
        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) { helper.append(debit()) }
        assertEquals(before, store.overview())
        assertEquals(revision, store.revisions.value)
        assertTrue(helper.page(limit = 100).isEmpty())
        helper.writableDatabase.execSQL("DROP TRIGGER reject_movement")
        helper.append(debit())
        assertEquals(8_309L, store.overview().balanceCents)
        assertEquals(store.overview().revision, store.revisions.value)
    }

    @Test
    fun pagesFiltersAndReadsDoNotMutateFinancialRevision() = withStore { helper, store ->
        configure(store)
        helper.append(debit())
        helper.append(debit().copy(
            key = "credit", observedAtMs = 2_100L, postedAtMs = 2_050L,
            title = "Você recebeu um Pix", text = "Pix recebido no valor de R$ 1,00.",
        ))
        val revision = store.revisions.value
        val page = store.history(limit = 2)
        assertEquals(2, page.size)
        assertEquals(listOf(FinancialEntryKind.REFERENCE),
            store.history(beforeId = page.last().id, limit = 2).map { it.kind })
        assertEquals(1, store.history(limit = 100, filter = HistoryFilter.ENTRIES).size)
        assertEquals(1, store.history(limit = 100, filter = HistoryFilter.EXITS).size)
        assertEquals(1, store.history(limit = 100, filter = HistoryFilter.ADJUSTMENTS).size)
        store.overview()
        store.pending(limit = 100)
        assertEquals(revision, store.revisions.value)
        assertThrows(IllegalArgumentException::class.java) { store.history(limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { store.pending(limit = 101) }
    }

    @Test
    fun lowReferenceCycleIntentSubmissionAndPauseSurviveClearAndReopen() = withDatabase { name ->
        val original = open(name).use { helper ->
            val store = SqliteMonitorStore(helper)
            configureLow(store)
            val cycle = store.overview().alertCycle
            assertNotNull(cycle)
            assertNotNull(cycle!!.emissionIntent)
            assertNull(cycle.lastSubmittedAtMs)
            assertNull(store.recordSubmission(RecordSubmission(
                cycle.emissionIntent!!, SubmissionOutcome.ACCEPTED, 1_200L,
            )).rejection)
            val result = store.acknowledge(cycle.id, 1_300L).overview
            assertEquals(AlertStatus.PAUSED, result.alertCycle!!.status)
            assertEquals(1_801_300L, result.alertCycle.pauseUntilMs)
            helper.clear()
            result
        }
        open(name).use {
            assertEquals(original, SqliteMonitorStore(it).overview())
        }
    }

    @Test
    fun blockedSubmissionDoesNotAdvanceSendOrCreateUnlimitedRevisions() = withStore { _, store ->
        configureLow(store)
        val intent = store.overview().alertCycle?.emissionIntent
        assertNotNull(intent)
        val command = RecordSubmission(intent!!, SubmissionOutcome.PERMISSION_DENIED, 1_200L)
        store.recordSubmission(command)
        val revision = store.revisions.value
        assertEquals(RevisionCause.SUBMISSION, revision.cause)
        assertEquals(SubmissionOutcome.PERMISSION_DENIED, store.overview().alertCycle!!.lastSubmissionOutcome)
        assertNull(store.overview().alertCycle!!.lastSubmittedAtMs)
        store.recordSubmission(command)
        assertEquals(revision, store.revisions.value)
        assertEquals(intent, store.evaluateAlert(2_000L).emissionRequested)
    }

    @Test
    fun appearanceDoesNotChangeAlertButFinancialSettingsReevaluateCondition() = withStore { _, store ->
        configureLow(store)
        val before = store.overview()
        assertNull(store.changeSettings(ChangeSettings(1L,
            before.settings.copy(theme = ThemePreference.DARK), 1_500L)).rejection)
        assertEquals(ThemePreference.DARK, store.overview().settings.theme)
        assertEquals(RevisionCause.APPEARANCE, store.revisions.value.cause)
        assertEquals(before.alertCycle, store.overview().alertCycle)
        assertNull(store.changeSettings(ChangeSettings(1L,
            store.overview().settings.copy(limitCents = 20_000L), 1_600L)).rejection)
        assertEquals(AlertStatus.CLOSED, store.overview().alertCycle!!.status)
        assertEquals(RevisionCause.SETTINGS, store.revisions.value.cause)
    }

    @Test
    fun unsupportedSettingsOrStaleReferenceAreRejectedWithoutStorageMutation() = withStore { _, store ->
        configure(store)
        val before = store.overview()
        listOf(
            before.settings.copy(limitCents = -1L),
            before.settings.copy(alerts = AlertSettings(1, 30)),
            before.settings.copy(alerts = AlertSettings(15, 1)),
        ).forEach {
            assertEquals(RejectionCode.INVALID_INPUT, store.changeSettings(ChangeSettings(1L, it, 2_000L)).rejection)
            assertEquals(before, store.overview())
        }
        assertEquals(RejectionCode.STALE_REFERENCE, store.changeSettings(
            ChangeSettings(99L, before.settings.copy(limitCents = 1L), 2_000L),
        ).rejection)
    }

    @Test
    fun processRestartAndDisconnectRequireReferenceAndReconnectDoesNotRepairContinuity() = withStore { helper, store ->
        configureLow(store)
        store.startProcessSession("process-a", 1_300L)
        assertTrue(store.overview().needsReference)
        helper.append(DiagnosticEvent(DiagnosticEventType.LISTENER_CONNECTED, 1_400L))
        assertTrue(store.overview().needsReference)
        nowMs = 2_000L
        helper.append(debit().copy(
            key = "credit", observedAtMs = 2_000L, postedAtMs = 1_900L,
            title = "Você recebeu um Pix", text = "Pix recebido no valor de R$ 200,00.",
        ))
        assertEquals(40_000L, store.overview().balanceCents)
        assertEquals(AlertStatus.ACTIVE, store.overview().alertCycle!!.status)
        val session = store.beginReference(3_000L).session!!
        store.confirmReference(ConfirmReference(session.id, 1L, 40_000L, 30_000L, 3_100L))
        assertFalse(store.overview().needsReference)
        assertEquals(AlertStatus.CLOSED, store.overview().alertCycle!!.status)
        helper.append(DiagnosticEvent(DiagnosticEventType.LISTENER_DISCONNECTED, 3_200L))
        assertTrue(store.overview().needsReference)
    }

    @Test
    fun trustedCreditRecoversAlertButInsufficientCreditKeepsPause() = withStore { helper, store ->
        configureLow(store)
        val cycle = store.overview().alertCycle
        assertNotNull(cycle)
        store.acknowledge(cycle!!.id, 1_300L)
        nowMs = 2_000L
        val credit = debit().copy(
            key = "credit-a", observedAtMs = 2_000L, postedAtMs = 1_900L,
            title = "Você recebeu um Pix", text = "Pix recebido no valor de R$ 1,00.",
        )
        helper.append(credit)
        assertEquals(AlertStatus.PAUSED, store.overview().alertCycle!!.status)
        helper.append(credit.copy(
            key = "credit-b", observedAtMs = 2_100L, postedAtMs = 2_050L,
            text = "Pix recebido no valor de R$ 100,00.",
        ))
        assertEquals(AlertStatus.CLOSED, store.overview().alertCycle!!.status)
        assertNull(store.overview().alertCycle!!.emissionIntent)
    }

    @Test
    fun negativeBalanceAndOverflowRemainExactInSqlite() = withStore { helper, store ->
        val session = store.beginReference(1_000L).session!!
        store.confirmReference(ConfirmReference(session.id, null, 100L, 0L, 1_100L))
        helper.append(debit())
        assertEquals(-1_591L, store.overview().balanceCents)
        val correction = store.beginReference(3_000L).session!!
        store.confirmReference(ConfirmReference(correction.id, 1L, Long.MIN_VALUE, 0L, 3_100L))
        nowMs = 4_000L
        helper.append(debit().copy(key = "overflow", observedAtMs = 4_000L, postedAtMs = 3_900L,
            text = "Valor a debitar R$ 0,01. Outra praça."))
        assertEquals(Long.MIN_VALUE, store.overview().balanceCents)
        assertEquals(PendingReason.AMOUNT_OVERFLOW, store.pending(limit = 100).single().reason)
    }

    @Test
    fun pendingDoNotCountAndMissingRawEvidenceRemainUsable() = withStore { helper, store ->
        configure(store)
        helper.append(debit().copy(key = null))
        helper.clear()
        val pending = store.pending(limit = 100).single()
        assertFalse(pending.rawEvidenceAvailable)
        assertNull(store.resolvePending(ResolvePending(pending.id, 1L, PendingChoice.DO_NOT_COUNT, 2_300L)).rejection)
        assertEquals(10_000L, store.overview().balanceCents)
        assertTrue(store.pending(limit = 100).isEmpty())
    }

    @Test
    fun cancellationOrdersMultipleCandidatesAndKeepsConflictingSourcePending() = withStore { helper, store ->
        configure(store)
        val session = store.beginReference(3_000L).session!!
        nowMs = 3_200L
        helper.append(debit().copy(observedAtMs = 3_100L, postedAtMs = 3_050L))
        helper.append(debit().copy(
            key = "b", observedAtMs = 3_200L, postedAtMs = 3_150L, text = "Valor a debitar R$ 1,00. Praça B.",
        ))
        helper.append(debit().copy(
            key = "c", observedAtMs = 3_210L, postedAtMs = 3_160L, text = "Valor a debitar R$ 2,00. Praça C.",
        ))
        helper.append(debit().copy(
            key = "c", observedAtMs = 3_220L, postedAtMs = 3_160L, text = "Valor a debitar R$ 3,00. Praça C.",
        ))
        helper.clear()
        store.cancelReference(session.id, 3_300L)
        assertEquals(8_209L, store.overview().balanceCents)
        assertTrue(store.pending(limit = 100).any { it.reason == PendingReason.CONFLICTING_PUBLICATION })
        assertEquals(listOf(100L, 1_691L),
            store.history(limit = 100, filter = HistoryFilter.EXITS).map { it.amountCents })
    }

    @Test
    fun unresolvedPendingPreventsUntrustedCreditRecoveryAndDatabaseBalanceIsReconstructible() = withStore { helper, store ->
        configureLow(store)
        helper.append(debit().copy(key = null))
        nowMs = 2_100L
        helper.append(debit().copy(
            key = "credit", observedAtMs = 2_100L, postedAtMs = 2_050L,
            title = "Você recebeu um Pix", text = "Pix recebido no valor de R$ 200,00.",
        ))
        assertEquals(40_000L, store.overview().balanceCents)
        assertEquals(AlertStatus.ACTIVE, store.overview().alertCycle!!.status)
        helper.clear()
        val reference = store.overview().reference!!
        val reconstructed = store.history(limit = 100).filter { it.referenceId == reference.id }
            .sortedBy { it.id }.fold(reference.balanceCents) { balance, entry ->
                when (entry.kind) {
                    FinancialEntryKind.REFERENCE -> balance
                    FinancialEntryKind.PIX_CREDIT -> Math.addExact(balance, entry.amountCents)
                    FinancialEntryKind.TAG_DEBIT -> Math.subtractExact(balance, entry.amountCents)
                }
            }
        assertEquals(reconstructed, store.overview().balanceCents)
        val pending = store.pending(limit = 100).single()
        store.resolvePending(ResolvePending(pending.id, reference.id, PendingChoice.DO_NOT_COUNT, 2_300L))
        assertEquals(AlertStatus.CLOSED, store.overview().alertCycle!!.status)
    }

    @Test
    fun pendingPaginationUsesExclusiveIdsAndRawClearDoesNotChangeRevision() = withStore { helper, store ->
        configure(store)
        repeat(3) { i -> helper.append(debit().copy(key = null, observedAtMs = 2_000L + i)) }
        val before = store.revisions.value
        val first = store.pending(limit = 2)
        val second = store.pending(beforeId = first.last().id, limit = 2)
        assertEquals(2, first.size)
        assertEquals(1, second.size)
        assertTrue(second.single().id < first.last().id)
        helper.clear()
        assertEquals(before, store.revisions.value)
        assertEquals(3L, store.overview().pendingCount)
    }

    @Test
    fun oldActionsOrSubmissionsDoNotResurrectClosedAlertAndResumeRespectsDeadline() = withStore { _, store ->
        configureLow(store)
        val cycle = store.overview().alertCycle!!
        store.recordSubmission(RecordSubmission(cycle.emissionIntent!!, SubmissionOutcome.ACCEPTED, 1_200L))
        store.acknowledge(cycle.id, 1_300L)
        store.resume(cycle.id, 1_400L)
        assertEquals(AlertStatus.ACTIVE, store.overview().alertCycle!!.status)
        assertNull(store.overview().alertCycle!!.emissionIntent)
        val before = store.revisions.value
        store.acknowledge("old-cycle", 1_500L)
        assertEquals(before, store.revisions.value)
        store.changeSettings(ChangeSettings(1L, store.overview().settings.copy(limitCents = 20_000L), 1_600L))
        val closed = store.overview().alertCycle
        store.recordSubmission(RecordSubmission(cycle.emissionIntent, SubmissionOutcome.ACCEPTED, 1_700L))
        store.resume(cycle.id, 1_800L)
        assertEquals(closed, store.overview().alertCycle)
    }

    @Test
    fun everyAdapterPropagatesActiveSessionClockRejectionBeforeDoingWork() = withStore { _, store ->
        configureLow(store)
        val cycle = store.overview().alertCycle!!
        store.beginReference(2_000L)
        val before = store.overview()
        val results = listOf(
            store.changeSettings(ChangeSettings(1L, before.settings.copy(theme = ThemePreference.DARK), 1_500L)),
            store.acknowledge(cycle.id, 1_500L),
            store.resume(cycle.id, 1_500L),
            store.recordSubmission(RecordSubmission(cycle.emissionIntent!!, SubmissionOutcome.ACCEPTED, 1_500L)),
            store.startProcessSession("too-early", 1_500L),
        )
        assertEquals(List(results.size) { RejectionCode.INVALID_TIME }, results.map { it.rejection })
        assertEquals(AlertRejection.INVALID_TIME, store.evaluateAlert(1_500L).rejection)
        assertEquals(before, store.overview())
    }

    @Test
    fun refreshDoesNotHideAlertClockRejectionWhenNoReferenceSessionIsOpen() = withStore { _, store ->
        configureLow(store)
        val before = store.overview()
        assertEquals(RejectionCode.INVALID_TIME, store.refresh(1_000L).rejection)
        assertEquals(before, store.overview())
    }

    @Test
    fun coordinatorAndCaptureShareOrderAndEffectsOnlyReadCommittedState() = withDatabase { name ->
        runBlocking {
            open(name).use { helper ->
                val store = SqliteMonitorStore(helper)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val recorder = DiagnosticRecorder(helper, "synthetic.bank", Dispatchers.IO, scope)
                val publishedBalances = mutableListOf<Long?>()
                val effects = object : MonitorAlertEffects {
                    override fun synchronize(overview: MonitorOverview) {
                        assertFalse(helper.writableDatabase.inTransaction())
                    }
                    override fun submit(overview: MonitorOverview, intent: EmissionIntent): SubmissionOutcome {
                        assertFalse(helper.writableDatabase.inTransaction())
                        assertEquals(intent, store.overview().alertCycle!!.emissionIntent)
                        publishedBalances += store.overview().balanceCents
                        return SubmissionOutcome.ACCEPTED
                    }
                }
                val coordinator = MonitorCoordinator(recorder, store, effects, scope, { nowMs }, "integration-process")
                try {
                    nowMs = 1_000L
                    val first = coordinator.beginReference().session!!
                    nowMs = 1_100L
                    coordinator.confirmReference(ConfirmReference(first.id, null, 10_000L, 0L, 0L))
                    nowMs = 2_000L
                    recorder.recordNotification("synthetic.bank") { debit() }
                    val review = async(start = CoroutineStart.UNDISPATCHED) { coordinator.beginReference() }
                    recorder.recordNotification("synthetic.bank") { debit().copy(
                        key = "concurrent", observedAtMs = 2_200L, postedAtMs = 2_100L,
                        text = "Valor a debitar R$ 1,00. Praça B.",
                    ) }
                    val session = review.await().session!!
                    assertEquals(8_309L, coordinator.overview().balanceCents)
                    assertEquals(100L, coordinator.pending(limit = 10).single().value.amountCents)
                    nowMs = 2_300L
                    coordinator.confirmReference(ConfirmReference(session.id, 1L, 20_000L, 30_000L, 0L))
                    assertEquals(listOf(20_000L), publishedBalances)
                    assertEquals(2_300L, coordinator.overview().alertCycle!!.lastSubmittedAtMs)
                    assertEquals(PendingReason.CONCURRENT_REFERENCE, coordinator.pending(limit = 10).single().reason)
                    val revision = store.revisions.value
                    repeat(3) { coordinator.overview(); coordinator.history(limit = 10) }
                    assertEquals(revision, store.revisions.value)
                    assertEquals(1, publishedBalances.size)
                } finally {
                    coordinator.close()
                    recorder.closeAndJoin()
                    scope.cancel()
                }
            }
        }
    }

    @Test
    fun applicationProvidesOneSharedCoordinatorWithAsynchronousBootstrap() = runBlocking {
        val application = context.applicationContext as CollectorApplication
        val access = runCatching { application.monitorCoordinator }
        assertTrue("Application must compose the monitor", access.isSuccess)
        val coordinator = access.getOrThrow()
        assertSame(coordinator, application.monitorCoordinator)
        val overview = coordinator.overview()
        assertEquals(overview.revision, coordinator.revisions.value)
    }

    @Test
    fun coordinatorCancellationAndPendingDecisionUseCommittedSharedStore() = withDatabase { name ->
        runBlocking {
            open(name).use { helper ->
                val store = SqliteMonitorStore(helper)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val recorder = DiagnosticRecorder(helper, "synthetic.bank", Dispatchers.IO, scope)
                val coordinator = MonitorCoordinator(
                    recorder, store, DisabledMonitorAlertEffects, scope, { nowMs }, "cancel-process",
                )
                try {
                    nowMs = 1_000L
                    val first = coordinator.beginReference().session!!
                    nowMs = 1_100L
                    coordinator.confirmReference(ConfirmReference(first.id, null, 10_000L, 0L, 0L))
                    nowMs = 3_000L
                    val review = coordinator.beginReference().session!!
                    nowMs = 3_100L
                    recorder.recordNotification("synthetic.bank") {
                        debit().copy(observedAtMs = 3_100L, postedAtMs = 3_050L)
                    }
                    assertEquals(10_000L, coordinator.overview().balanceCents)
                    nowMs = 3_200L
                    assertNull(coordinator.cancelReference(review.id).rejection)
                    assertEquals(8_309L, coordinator.overview().balanceCents)
                    nowMs = 3_400L
                    recorder.recordNotification("synthetic.bank") {
                        debit().copy(observedAtMs = 3_400L, postedAtMs = 3_300L)
                    }
                    val pending = coordinator.pending(limit = 10).single()
                    nowMs = 3_500L
                    val command = ResolvePending(pending.id, 1L, PendingChoice.CONFIRM_NEW)
                    assertNull(coordinator.resolvePending(command).rejection)
                    assertEquals(6_618L, coordinator.overview().balanceCents)
                    assertNull(coordinator.resolvePending(command).rejection)
                    assertEquals(6_618L, coordinator.overview().balanceCents)
                } finally {
                    coordinator.close()
                    recorder.closeAndJoin()
                    scope.cancel()
                }
            }
        }
    }

    private fun configureLow(store: SqliteMonitorStore) {
        val session = store.beginReference(1_000L).session!!
        assertNull(store.confirmReference(ConfirmReference(session.id, null, 20_000L, 30_000L, 1_100L)).rejection)
    }

    private fun configure(store: SqliteMonitorStore) {
        val session = store.beginReference(1_000L).session!!
        assertNull(store.confirmReference(ConfirmReference(session.id, null, 10_000L, 5_000L, 1_100L)).rejection)
    }

    private fun withStore(block: (DiagnosticDatabase, SqliteMonitorStore) -> Unit) = withDatabase { name ->
        open(name).use { block(it, SqliteMonitorStore(it)) }
    }

    private fun open(name: String) = DiagnosticDatabase(
        context, name, monitoredPackageName = "synthetic.bank", monitorClock = { nowMs },
    )

    private fun debit() = DiagnosticEvent(
        DiagnosticEventType.POSTED_OR_UPDATED, 2_000L, postedAtMs = 1_900L,
        packageName = "synthetic.bank", key = "new-charge", title = "Débito C6 Tag",
        text = "Valor a debitar R$ 16,91. Praça A.",
    )

    private fun withDatabase(block: (String) -> Unit) {
        val name = "monitor-store-${UUID.randomUUID()}.db"
        try { block(name) } finally { context.deleteDatabase(name) }
    }
}

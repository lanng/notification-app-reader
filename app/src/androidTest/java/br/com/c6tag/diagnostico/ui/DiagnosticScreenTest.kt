package br.com.c6tag.diagnostico.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import br.com.c6tag.diagnostico.capture.CaptureConnectionState
import br.com.c6tag.diagnostico.capture.CaptureFailureCode
import br.com.c6tag.diagnostico.capture.CaptureStatusState
import br.com.c6tag.diagnostico.diagnostic.CandidateKind
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEvent
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEventType
import br.com.c6tag.diagnostico.diagnostic.DiagnosticRecorderError
import br.com.c6tag.diagnostico.diagnostic.DiagnosticRecorderState
import br.com.c6tag.diagnostico.diagnostic.NotificationInterpretation
import br.com.c6tag.diagnostico.diagnostic.StoredDiagnosticEvent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DiagnosticScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var state: MutableState<DiagnosticUiState>
    private var settingsRequests = 0
    private var clearRequests = 0
    private var exportRequests = 0
    private var exportCancels = 0
    private var exportConfirms = 0
    private var previousPageRequests = 0
    private var retryRequests = 0

    @Test
    fun overviewSeparatesAuthorizationFromObservedConnectionAcrossStates() {
        render(
            state(
                targetPackage = "",
                authorization = AuthorizationStatus.CHECK_PENDING,
                connection = CaptureConnectionState.UNKNOWN,
            ),
        )

        compose.onNodeWithText("Diagnóstico: não calcula saldo").assertIsDisplayed()
        compose.onNodeWithText("A estimativa futura depende das notificações recebidas e pode estar incompleta.")
            .assertIsDisplayed()
        compose.onNodeWithText("Origem C6 não configurada: captura desativada").assertIsDisplayed()
        compose.onNodeWithText("Checagem de acesso pendente").assertIsDisplayed()
        compose.onNodeWithText("Conexão observada: desconhecida").assertIsDisplayed()

        state.value = state.value.copy(
            targetPackage = TARGET_PACKAGE,
            authorization = AuthorizationStatus.NOT_AUTHORIZED,
        )
        compose.onNodeWithText("Acesso especial não autorizado").assertIsDisplayed()

        state.value = state.value.copy(authorization = AuthorizationStatus.AUTHORIZED)
        compose.onNodeWithText("Acesso especial autorizado").assertIsDisplayed()
        compose.onNodeWithText("Conexão observada: desconhecida").assertIsDisplayed()

        state.value = state.value.copy(
            captureStatus = captureStatus(CaptureConnectionState.CONNECTED, observedAtMs = 1_800_000_000_000L),
        )
        compose.onNodeWithText("Conexão observada: conectada").assertIsDisplayed()
        compose.onNodeWithText("Horário da observação:", substring = true).assertIsDisplayed()

        state.value = state.value.copy(
            captureStatus = captureStatus(CaptureConnectionState.DISCONNECTED, observedAtMs = 1_800_000_000_001L),
        )
        compose.onNodeWithText("Conexão observada: desconectada").assertIsDisplayed()
    }

    @Test
    fun settingsActionUsesCallbackAndRendersUpdatedAuthorization() {
        render(state(authorization = AuthorizationStatus.NOT_AUTHORIZED))

        compose.onNodeWithText("Abrir configurações de acesso").performClick()

        assertEquals(1, settingsRequests)
        compose.onNodeWithText("Acesso especial autorizado").assertIsDisplayed()
    }

    @Test
    fun settingsLaunchFailureShowsItsOwnMessageWithoutChangingAuthorizationOrOtherStatus() {
        render(
            state(
                authorization = AuthorizationStatus.AUTHORIZED,
                connection = CaptureConnectionState.CONNECTED,
                recorderState = DiagnosticRecorderState(
                    errorCount = 2L,
                    lastError = DiagnosticRecorderError.STORE_OPERATION_FAILED,
                ),
                settingsLaunchError = SettingsLaunchError.OPEN_FAILED,
            ),
        )

        compose.onNodeWithText("Acesso especial autorizado").assertIsDisplayed()
        compose.onNodeWithText("Conexão observada: conectada").assertIsDisplayed()
        compose.onNodeWithText("Falhas internas ao gravar registros (2 ocorrências).").assertIsDisplayed()
        compose.onNodeWithText("Não foi possível abrir as configurações de acesso às notificações")
            .assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodesWithText("Não foi possível checar o acesso especial")
                .fetchSemanticsNodes().size,
        )

        state.value = state.value.copy(authorization = AuthorizationStatus.NOT_AUTHORIZED)
        compose.onNodeWithText("Acesso especial não autorizado").assertIsDisplayed()
        compose.onNodeWithText("Não foi possível abrir as configurações de acesso às notificações")
            .assertIsDisplayed()

        state.value = state.value.copy(
            authorization = AuthorizationStatus.CHECK_FAILED,
            settingsLaunchError = null,
        )
        compose.onNodeWithText("Não foi possível checar o acesso especial").assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodesWithText("Não foi possível abrir as configurações de acesso às notificações")
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun detailShowsStoredFieldsSeparatelyAndKeepsLongTextScrollable() {
        val longBody = "Texto integral preservado ".repeat(80)
        val event = StoredDiagnosticEvent(
            id = 42L,
            event = DiagnosticEvent(
                type = DiagnosticEventType.REMOVED,
                observedAtMs = 1_800_000_000_000L,
                postedAtMs = 1_799_999_000_000L,
                packageName = TARGET_PACKAGE,
                key = "local-key",
                notificationId = 13,
                tag = "notification-tag",
                groupKey = "group-key",
                groupSummary = true,
                title = "Resumo",
                text = "Texto curto",
                bigText = longBody,
                lines = listOf("Linha 1", "Linha 2"),
                removalReason = 7,
                interpretation = NotificationInterpretation(CandidateKind.GROUP_SUMMARY),
            ),
        )
        render(state(events = listOf(event)))

        compose.onNodeWithTag("diagnostic-event-42").performClick()
        compose.onNodeWithText("Detalhe do registro").assertIsDisplayed()
        compose.onNodeWithText("Tipo: REMOVIDA").assertIsDisplayed()
        compose.onNodeWithText("ID local: 42").assertIsDisplayed()
        compose.onNodeWithText("Chave: local-key").assertIsDisplayed()
        compose.onNodeWithText("Pacote: $TARGET_PACKAGE").assertIsDisplayed()
        compose.onNodeWithText("ID da notificação: 13").assertIsDisplayed()
        compose.onNodeWithText("Tag: notification-tag").assertIsDisplayed()
        compose.onNodeWithText("Chave do grupo: group-key").assertIsDisplayed()
        compose.onNodeWithText("Resumo do grupo: sim").assertIsDisplayed()
        compose.onNodeWithText("Horário observado:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Horário informado no post:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Motivo da remoção: 7").assertIsDisplayed()
        compose.onNodeWithText("Título: Resumo").assertIsDisplayed()
        compose.onNodeWithText("Texto: Texto curto").assertIsDisplayed()
        val detailScroller = compose.onNodeWithTag("event-detail-scroll")
        detailScroller.performScrollToNode(
            androidx.compose.ui.test.hasText("Texto expandido", substring = true),
        )
        compose.onNodeWithText("Texto expandido:", substring = true).assertIsDisplayed()
        detailScroller.performScrollToNode(androidx.compose.ui.test.hasText(longBody, substring = true))
        compose.onNodeWithText(longBody, substring = true).assertIsDisplayed()
        detailScroller.performScrollToNode(androidx.compose.ui.test.hasText("Linhas", substring = true))
        compose.onNodeWithText("Linhas").assertIsDisplayed()
        detailScroller.performScrollToNode(androidx.compose.ui.test.hasText("1. Linha 1"))
        compose.onNodeWithText("1. Linha 1").assertIsDisplayed()
        detailScroller.performScrollToNode(androidx.compose.ui.test.hasText("2. Linha 2"))
        compose.onNodeWithText("2. Linha 2").assertIsDisplayed()
    }

    @Test
    fun detailLabelsMissingMetadataInsteadOfInventingValues() {
        val event = StoredDiagnosticEvent(
            id = 50L,
            event = DiagnosticEvent(
                type = DiagnosticEventType.LISTENER_CONNECTED,
                observedAtMs = 1_800_000_000_000L,
            ),
        )
        render(state(events = listOf(event)))

        compose.onNodeWithTag("diagnostic-event-50").performClick()

        listOf(
            "Tipo: LISTENER CONECTADO",
            "Classificação observada: não disponível",
            "Horário informado no post: não informado",
            "Motivo da remoção: não informado",
            "Chave: não informado",
            "Pacote: não informado",
            "ID da notificação: não informado",
            "Tag: não informado",
            "Chave do grupo: não informado",
            "Resumo do grupo: não informado",
            "Título: não informado",
            "Texto: não informado",
            "Texto expandido: não informado",
        ).forEach { label ->
            assertEquals(
                label,
                1,
                compose.onAllNodesWithText(label).fetchSemanticsNodes().size,
            )
        }
        val detailScroller = compose.onNodeWithTag("event-detail-scroll")
        detailScroller.performScrollToNode(androidx.compose.ui.test.hasText("não informadas"))
        compose.onNodeWithText("não informadas").assertIsDisplayed()
    }

    @Test
    fun historyUsesAccessibleLabelsForObservationsAndExperimentalCandidates() {
        val candidate = event(20L)
        val snapshot = candidate.copy(
            id = 21L,
            event = candidate.event.copy(
                type = DiagnosticEventType.ACTIVE_SNAPSHOT,
                interpretation = null,
            ),
        )
        val summary = candidate.copy(
            id = 22L,
            event = candidate.event.copy(
                groupSummary = true,
                interpretation = NotificationInterpretation(CandidateKind.GROUP_SUMMARY),
            ),
        )
        val removed = candidate.copy(
            id = 23L,
            event = candidate.event.copy(
                type = DiagnosticEventType.REMOVED,
                interpretation = null,
            ),
        )
        val unknown = candidate.copy(
            id = 24L,
            event = candidate.event.copy(
                interpretation = NotificationInterpretation(
                    kind = CandidateKind.UNKNOWN,
                    reason = "Formato não reconhecido.",
                ),
            ),
        )
        render(state(events = listOf(snapshot, summary, removed, unknown, candidate)))

        listOf(
            "Classificação observada: SNAPSHOT",
            "Classificação observada: RESUMO",
            "Classificação observada: REMOVIDA",
            "Classificação observada: NÃO RECONHECIDA",
            "Classificação observada: Candidato experimental: possível débito",
        ).forEach { label ->
            assertEquals(
                label,
                1,
                compose.onAllNodesWithText(label).fetchSemanticsNodes().size,
            )
        }
    }

    @Test
    fun clearCancelDoesNotInvokeCallbackAndConfirmationClearsVisibleHistory() {
        render(state(events = listOf(event(7L))))

        compose.onNodeWithText("Limpar registros").performClick()
        compose.onNodeWithText("Os registros internos serão apagados. Exportações já existentes não serão apagadas; eventos novos ainda podem chegar.")
            .assertExists()
        compose.onNodeWithText("Cancelar").performClick()
        assertEquals(0, clearRequests)
        compose.onNodeWithTag("diagnostic-event-7").assertExists()

        compose.onNodeWithText("Limpar registros").performClick()
        compose.onNodeWithText("Apagar registros").performClick()
        assertEquals(1, clearRequests)
        assertEquals(0, compose.onAllNodesWithTag("diagnostic-event-7").fetchSemanticsNodes().size)
        compose.onNodeWithText("Histórico vazio").assertIsDisplayed()
    }

    @Test
    fun manualPaginationAddsOlderEventsAndShowsSafePersistenceFailure() {
        render(
            state(
                events = listOf(event(4L)),
                hasMore = true,
                historyError = HistoryError.LOAD_FAILED,
            ),
        )

        compose.onNodeWithText("Falha ao carregar o histórico. Tente novamente.").assertIsDisplayed()
        compose.onNodeWithText("Carregar anteriores").performClick()

        assertEquals(1, previousPageRequests)
        compose.onNodeWithTag("diagnostic-event-4").assertIsDisplayed()
        compose.onNodeWithTag("diagnostic-event-3").assertIsDisplayed()
    }

    @Test
    fun historyLoadFailureCanBeRetriedWithoutDiscardingVisibleRecords() {
        render(
            state(
                events = listOf(event(10L)),
                historyError = HistoryError.LOAD_FAILED,
            ),
        )

        compose.onNodeWithText("Falha ao carregar o histórico. Tente novamente.").assertIsDisplayed()
        compose.onNodeWithText("Tentar carregar novamente").performClick()

        assertEquals(1, retryRequests)
        compose.onNodeWithTag("diagnostic-event-10").assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodesWithText("Falha ao carregar o histórico. Tente novamente.")
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun clearFailureIsShownWhileExistingHistoryRemainsVisible() {
        render(
            state(
                events = listOf(event(11L)),
                historyError = HistoryError.CLEAR_FAILED,
            ),
        )

        compose.onNodeWithText("Não foi possível limpar os registros. O histórico foi mantido.")
            .assertIsDisplayed()
        compose.onNodeWithTag("diagnostic-event-11").assertIsDisplayed()
    }

    @Test
    fun exportRequiresVisibleSensitiveDataWarningAndOnlyConfirmationInvokesPickerCallback() {
        render(state())

        compose.onNodeWithText("Exportar diagnóstico").performClick()
        compose.onNodeWithText(
            "O arquivo pode conter nomes, CPF parcial, locais e o conteúdo de notificações. " +
                "Revise e oculte dados pessoais antes de compartilhar; o app não anonimiza o arquivo. " +
                "Um destino em nuvem pode sincronizar o arquivo mesmo que este app não tenha acesso à internet.",
        ).assertIsDisplayed()
        assertEquals(1, exportRequests)
        assertEquals(0, exportConfirms)

        compose.onNodeWithText("Cancelar").performClick()
        assertEquals(1, exportCancels)
        assertEquals(0, exportConfirms)

        compose.onNodeWithText("Exportar diagnóstico").performClick()
        compose.onNodeWithText("Escolher destino").performClick()
        assertEquals(1, exportConfirms)
    }

    @Test
    fun exportStatusIsSafeAndClearOrDuplicateExportIsDisabledWhileBusy() {
        render(state(events = listOf(event(31L)), isExporting = true))

        compose.onNodeWithText("Exportando diagnóstico…").assertIsDisplayed()
        compose.onNodeWithText("Exportar diagnóstico").assertIsNotEnabled()
        compose.onNodeWithText("Limpar registros").assertIsNotEnabled()

        state.value = state.value.copy(isExporting = false, exportStatus = ExportUiStatus.SUCCESS)
        compose.onNodeWithText("Arquivo de diagnóstico exportado.").assertIsDisplayed()

        state.value = state.value.copy(exportStatus = ExportUiStatus.FAILURE)
        compose.onNodeWithText(
            "Não foi possível concluir a exportação. Um arquivo parcial pode permanecer no destino; " +
                "tente novamente ou escolha outro destino.",
        ).assertIsDisplayed()
    }

    @Test
    fun captureAndRecorderErrorsAreVisibleWithoutRawExceptionText() {
        render(
            state(
                captureStatus = captureStatus(
                    CaptureConnectionState.UNKNOWN,
                    error = CaptureFailureCode.NOTIFICATION_MAPPING_FAILED,
                ),
                recorderState = DiagnosticRecorderState(
                    errorCount = 2L,
                    lastError = DiagnosticRecorderError.STORE_OPERATION_FAILED,
                ),
            ),
        )

        compose.onNodeWithText("Falha ao processar uma notificação (1 ocorrência).").assertIsDisplayed()
        compose.onNodeWithText("Falhas internas ao gravar registros (2 ocorrências).").assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodesWithText("synthetic sensitive exception").fetchSemanticsNodes().size,
        )
    }

    private fun render(initial: DiagnosticUiState) {
        state = mutableStateOf(initial)
        settingsRequests = 0
        clearRequests = 0
        exportRequests = 0
        exportCancels = 0
        exportConfirms = 0
        previousPageRequests = 0
        retryRequests = 0
        compose.setContent {
            DiagnosticScreen(
                state = state.value,
                actions = DiagnosticScreenActions(
                    onOpenSettings = {
                        settingsRequests++
                        state.value = state.value.copy(authorization = AuthorizationStatus.AUTHORIZED)
                    },
                    onLoadPrevious = {
                        previousPageRequests++
                        state.value = state.value.copy(
                            events = state.value.events + event(3L),
                            hasMore = false,
                        )
                    },
                    onRetryHistory = {
                        retryRequests++
                        state.value = state.value.copy(historyError = null)
                    },
                    onOpenEvent = { id ->
                        state.value = state.value.copy(
                            selectedEvent = state.value.events.firstOrNull { it.id == id },
                        )
                    },
                    onCloseDetail = { state.value = state.value.copy(selectedEvent = null) },
                    onRequestClear = {
                        state.value = state.value.copy(clearConfirmationVisible = true)
                    },
                    onCancelClear = {
                        state.value = state.value.copy(clearConfirmationVisible = false)
                    },
                    onConfirmClear = {
                        clearRequests++
                        state.value = state.value.copy(
                            events = emptyList(),
                            selectedEvent = null,
                            clearConfirmationVisible = false,
                        )
                    },
                    onRequestExport = {
                        exportRequests++
                        state.value = state.value.copy(exportConfirmationVisible = true)
                    },
                    onCancelExport = {
                        exportCancels++
                        state.value = state.value.copy(exportConfirmationVisible = false)
                    },
                    onConfirmExport = {
                        exportConfirms++
                        state.value = state.value.copy(exportConfirmationVisible = false)
                    },
                ),
            )
        }
    }

    private fun state(
        targetPackage: String = TARGET_PACKAGE,
        authorization: AuthorizationStatus = AuthorizationStatus.AUTHORIZED,
        connection: CaptureConnectionState = CaptureConnectionState.UNKNOWN,
        captureStatus: CaptureStatusState = captureStatus(connection),
        recorderState: DiagnosticRecorderState = DiagnosticRecorderState(),
        events: List<StoredDiagnosticEvent> = emptyList(),
        hasMore: Boolean = false,
        historyError: HistoryError? = null,
        settingsLaunchError: SettingsLaunchError? = null,
        isExporting: Boolean = false,
        exportStatus: ExportUiStatus? = null,
    ) = DiagnosticUiState(
        targetPackage = targetPackage,
        authorization = authorization,
        captureStatus = captureStatus,
        recorderState = recorderState,
        events = events,
        hasMore = hasMore,
        historyError = historyError,
        settingsLaunchError = settingsLaunchError,
        isExporting = isExporting,
        exportStatus = exportStatus,
    )

    private fun captureStatus(
        connection: CaptureConnectionState,
        observedAtMs: Long? = null,
        error: CaptureFailureCode? = null,
    ) = CaptureStatusState(
        connection = connection,
        connectionObservedAtMs = observedAtMs,
        targetPackageConfigured = true,
        errorCount = if (error == null) 0 else 1,
        lastError = error,
        lastErrorAtMs = if (error == null) null else 1_800_000_000_000L,
    )

    private fun event(id: Long) = StoredDiagnosticEvent(
        id = id,
        event = DiagnosticEvent(
            type = DiagnosticEventType.POSTED_OR_UPDATED,
            observedAtMs = 1_800_000_000_000L,
            packageName = TARGET_PACKAGE,
            title = "Débito C6 Tag",
            text = "Valor a debitar R$ 16,91.",
            interpretation = NotificationInterpretation(
                kind = CandidateKind.TAG_DEBIT,
                amountCents = 1_691L,
            ),
        ),
    )

    private companion object {
        const val TARGET_PACKAGE = "br.com.c6bank"
    }
}

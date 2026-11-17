package br.com.c6tag.diagnostico.ui.monitor

import br.com.c6tag.diagnostico.capture.CaptureStatusState
import br.com.c6tag.diagnostico.monitor.MonitorOverview
import br.com.c6tag.diagnostico.monitor.FinancialEntry
import br.com.c6tag.diagnostico.monitor.HistoryFilter
import br.com.c6tag.diagnostico.monitor.PendingChoice
import br.com.c6tag.diagnostico.monitor.PendingMovement
import br.com.c6tag.diagnostico.monitor.ReferenceSession
import br.com.c6tag.diagnostico.monitor.ThemePreference
import br.com.c6tag.diagnostico.ui.AuthorizationStatus

internal data class MonitorUiState(
    val overview: MonitorOverview = MonitorOverview(),
    val capture: CaptureStatusState = CaptureStatusState(targetPackageConfigured = false),
    val authorization: AuthorizationStatus = AuthorizationStatus.CHECK_PENDING,
    val notices: NoticeAccess = NoticeAccess.UNKNOWN,
    val recent: List<FinancialEntry> = emptyList(),
    val history: List<FinancialEntry> = emptyList(),
    val financialSelection: FinancialEntry? = null,
    val historyFilter: HistoryFilter = HistoryFilter.ALL,
    val historyHasMore: Boolean = false,
    val historyLoading: Boolean = false,
    val historyError: String? = null,
    val pending: List<PendingMovement> = emptyList(),
    val pendingHasMore: Boolean = false,
    val pendingLoading: Boolean = false,
    val pendingError: String? = null,
    val pendingSelection: PendingReviewSelection? = null,
    val settingsDraft: MonitorSettingsDraft? = null,
    val noticeSettingsError: String? = null,
    val captureSettingsError: String? = null,
    val isLoading: Boolean = true,
    val isBusy: Boolean = false,
    val error: String? = null,
    val referenceDraft: BalanceDraft? = null,
)

internal data class PendingReviewSelection(
    val movement: PendingMovement,
    val expectedReferenceId: Long?,
    val confirmation: PendingChoice? = null,
    val stale: Boolean = false,
)

internal data class MonitorSettingsDraft(
    val limit: String,
    val repeatMinutes: Int,
    val pauseMinutes: Int,
    val invalid: Boolean = false,
)

internal data class BalanceDraft(
    val session: ReferenceSession,
    val balance: String = "",
    val limit: String = "300,00",
    val confirmationVisible: Boolean = false,
    val invalidInput: Boolean = false,
    val sessionUnavailable: Boolean = false,
)

internal enum class NoticeAccess { UNKNOWN, AVAILABLE, DENIED, CHANNEL_BLOCKED, SILENT }

internal data class MonitorActions(
    val onReference: () -> Unit = {},
    val onAcknowledge: (String) -> Unit = {},
    val onResume: (String) -> Unit = {},
    val onOpenCaptureSettings: () -> Unit = {},
    val onNoticeExplanation: () -> Unit = {},
    val onHistory: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onTheme: (ThemePreference) -> Unit = {},
    val onHistoryFilter: (HistoryFilter) -> Unit = {},
    val onOpenFinancialEntry: (Long) -> Unit = {},
    val onCloseFinancialEntry: () -> Unit = {},
    val onLoadMoreHistory: () -> Unit = {},
    val onRetryHistory: () -> Unit = {},
    val onPendingReview: () -> Unit = {},
    val onLoadMorePending: () -> Unit = {},
    val onRetryPending: () -> Unit = {},
    val onOpenPending: (Long) -> Unit = {},
    val onClosePending: () -> Unit = {},
    val onRequestPendingDecision: (PendingChoice) -> Unit = {},
    val onDismissPendingDecision: () -> Unit = {},
    val onConfirmPendingDecision: () -> Unit = {},
    val onSettingsLimit: (String) -> Unit = {},
    val onSettingsRepeat: (Int) -> Unit = {},
    val onSettingsPause: (Int) -> Unit = {},
    val onSaveSettings: () -> Unit = {},
    val onOpenNoticeSettings: () -> Unit = {},
    val onDiagnostic: () -> Unit = {},
)

internal data class ReferenceActions(
    val onBalance: (String) -> Unit = {},
    val onLimit: (String) -> Unit = {},
    val onReview: () -> Unit = {},
    val onDismissConfirmation: () -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onOpenCaptureSettings: () -> Unit = {},
)

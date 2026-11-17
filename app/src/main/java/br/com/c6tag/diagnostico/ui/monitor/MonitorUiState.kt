package br.com.c6tag.diagnostico.ui.monitor

import br.com.c6tag.diagnostico.capture.CaptureStatusState
import br.com.c6tag.diagnostico.monitor.MonitorOverview
import br.com.c6tag.diagnostico.monitor.FinancialEntry
import br.com.c6tag.diagnostico.monitor.ReferenceSession
import br.com.c6tag.diagnostico.monitor.ThemePreference
import br.com.c6tag.diagnostico.ui.AuthorizationStatus

internal data class MonitorUiState(
    val overview: MonitorOverview = MonitorOverview(),
    val capture: CaptureStatusState = CaptureStatusState(targetPackageConfigured = false),
    val authorization: AuthorizationStatus = AuthorizationStatus.CHECK_PENDING,
    val notices: NoticeAccess = NoticeAccess.UNKNOWN,
    val recent: List<FinancialEntry> = emptyList(),
    val isLoading: Boolean = true,
    val isBusy: Boolean = false,
    val error: String? = null,
    val referenceDraft: BalanceDraft? = null,
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

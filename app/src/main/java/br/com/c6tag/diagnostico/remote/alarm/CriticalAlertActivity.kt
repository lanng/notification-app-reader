package br.com.c6tag.diagnostico.remote.alarm

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.c6tag.diagnostico.CollectorApplication
import br.com.c6tag.diagnostico.MainActivity
import br.com.c6tag.diagnostico.R
import br.com.c6tag.diagnostico.ui.monitor.brl
import br.com.c6tag.diagnostico.ui.theme.MonitorTheme
import kotlinx.coroutines.delay

class CriticalAlertActivity : ComponentActivity() {
    private var initialOccurrenceId by mutableStateOf<String?>(null)
    private var initialIsGuest by mutableStateOf(false)
    private var initialBalanceCents by mutableStateOf<Long?>(null)
    private var initialLimitCents by mutableStateOf(0L)
    private var initialCycleId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAlarmWindow()
        readExtras(intent)

        setContent {
            val activeAlarm by CriticalAlarmPlayer.active.collectAsStateWithLifecycle()

            LaunchedEffect(activeAlarm) {
                if (activeAlarm == null && initialOccurrenceId != null) {
                    finish()
                }
            }

            val occurrenceId = activeAlarm?.occurrenceId ?: initialOccurrenceId
            val isGuest = activeAlarm?.isGuest ?: initialIsGuest
            val balanceCents = activeAlarm?.balanceCents ?: initialBalanceCents
            val limitCents = activeAlarm?.limitCents ?: initialLimitCents
            val cycleId = activeAlarm?.cycleId ?: initialCycleId
            val startedAtElapsedMs = activeAlarm?.session?.startedAtElapsedMs

            MonitorTheme {
                CriticalAlarmScreen(
                    isGuest = isGuest,
                    balanceCents = balanceCents,
                    limitCents = limitCents,
                    startedAtElapsedMs = startedAtElapsedMs,
                    onStopAlarm = {
                        CriticalAlertController.stopAndCancel(applicationContext, occurrenceId)
                        finish()
                    },
                    onAcknowledgeAndPause = {
                        CriticalAlertController.stopAndCancel(applicationContext, occurrenceId)
                        val app = application as? CollectorApplication
                        app?.receiveCriticalAlertAcknowledge(isGuest, cycleId) {}
                        finish()
                    },
                    onOpenApp = {
                        CriticalAlertController.stopAndCancel(applicationContext, occurrenceId)
                        startActivity(
                            Intent(this@CriticalAlertActivity, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            },
                        )
                        finish()
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readExtras(intent)
    }

    private fun configureAlarmWindow() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun readExtras(incoming: Intent?) {
        if (incoming == null) return
        initialOccurrenceId = incoming.getStringExtra(EXTRA_OCCURRENCE_ID)
        initialIsGuest = incoming.getBooleanExtra(EXTRA_IS_GUEST, false)
        initialBalanceCents = if (incoming.hasExtra(EXTRA_BALANCE_CENTS)) {
            incoming.getLongExtra(EXTRA_BALANCE_CENTS, 0L)
        } else {
            null
        }
        initialLimitCents = incoming.getLongExtra(EXTRA_LIMIT_CENTS, 0L)
        initialCycleId = incoming.getStringExtra(EXTRA_CYCLE_ID)
    }

    companion object {
        const val ACTION_SHOW_CRITICAL_ALARM = "br.com.c6tag.diagnostico.action.SHOW_CRITICAL_ALARM"
        const val EXTRA_OCCURRENCE_ID = "occurrence_id"
        const val EXTRA_IS_GUEST = "is_guest"
        const val EXTRA_BALANCE_CENTS = "balance_cents"
        const val EXTRA_LIMIT_CENTS = "limit_cents"
        const val EXTRA_CYCLE_ID = "cycle_id"
    }
}

@Composable
private fun CriticalAlarmScreen(
    isGuest: Boolean,
    balanceCents: Long?,
    limitCents: Long,
    startedAtElapsedMs: Long?,
    onStopAlarm: () -> Unit,
    onAcknowledgeAndPause: () -> Unit,
    onOpenApp: () -> Unit,
) {
    BackHandler(enabled = true) {
        onStopAlarm()
    }

    var remainingSeconds by remember(startedAtElapsedMs) { mutableIntStateOf(30) }
    LaunchedEffect(startedAtElapsedMs) {
        if (startedAtElapsedMs == null) {
            remainingSeconds = 0
            return@LaunchedEffect
        }
        while (true) {
            val elapsed = (SystemClock.elapsedRealtime() - startedAtElapsedMs).coerceAtLeast(0L)
            val leftMs = (CriticalAlarmPlayer.DURATION_MS - elapsed).coerceAtLeast(0L)
            remainingSeconds = ((leftMs + 999L) / 1000L).toInt()
            if (leftMs <= 0L) break
            delay(250L)
        }
    }

    val halfLimitCents = limitCents / 2L + limitCents % 2L

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF9E1010))
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                color = Color(0x33FFFFFF),
                shape = RoundedCornerShape(999.dp),
            ) {
                Text(
                    text = stringResource(
                        if (isGuest) R.string.critical_alarm_role_guest else R.string.critical_alarm_role_host,
                    ),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            Text(
                text = stringResource(R.string.critical_alarm_screen_title),
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.critical_alarm_screen_subtitle),
                color = Color(0xFFFFE5E5),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.monitor_estimated_balance),
                color = Color(0xFFFFD6D6),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = balanceCents?.let(::brl) ?: stringResource(R.string.guest_balance_unavailable),
                color = Color.White,
                fontSize = 48.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("critical-alarm-balance"),
            )
            if (limitCents > 0L) {
                Text(
                    text = stringResource(
                        R.string.critical_alarm_limits_detail,
                        brl(limitCents),
                        brl(halfLimitCents),
                    ),
                    color = Color(0xFFFFE5E5),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            if (remainingSeconds > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.critical_alarm_sound_countdown, remainingSeconds),
                    color = Color(0xFFFFD6D6),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = onStopAlarm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF9E1010),
                ),
                contentPadding = PaddingValues(vertical = 18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag("critical-alarm-stop"),
            ) {
                Text(
                    text = stringResource(R.string.critical_alarm_stop_button),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            OutlinedButton(
                onClick = onAcknowledgeAndPause,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xAAFFFFFF)),
                contentPadding = PaddingValues(vertical = 14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("critical-alarm-pause"),
            ) {
                Text(stringResource(R.string.critical_alarm_pause_button))
            }

            TextButton(
                onClick = onOpenApp,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFFE5E5)),
            ) {
                Text(stringResource(R.string.critical_alarm_open_app))
            }
        }
    }
}

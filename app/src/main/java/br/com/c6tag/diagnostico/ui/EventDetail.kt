package br.com.c6tag.diagnostico.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import br.com.c6tag.diagnostico.R
import br.com.c6tag.diagnostico.diagnostic.CandidateKind
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEvent
import br.com.c6tag.diagnostico.diagnostic.StoredDiagnosticEvent
import java.math.BigDecimal
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun EventDetail(
    storedEvent: StoredDiagnosticEvent,
    onBack: () -> Unit,
) {
    val event = storedEvent.event
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag("event-detail-scroll")
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.back_to_history))
        }
        Text(
            text = stringResource(R.string.event_detail_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.event_type, eventTypeLabel(event.type)),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(stringResource(R.string.event_id, storedEvent.id))
        Text(
            text = stringResource(R.string.event_classification, classificationLabel(event)),
            style = MaterialTheme.typography.bodyLarge,
        )
        ShowInterpretation(event)

        Text(
            text = stringResource(
                R.string.event_observed_at,
                formatDiagnosticTimestamp(event.observedAtMs),
            ),
        )
        Text(
            text = stringResource(
                R.string.event_posted_at,
                event.postedAtMs?.let(::formatDiagnosticTimestamp)
                    ?: stringResource(R.string.missing_value),
            ),
        )
        Text(
            text = stringResource(
                R.string.event_removal_reason,
                event.removalReason?.toString() ?: stringResource(R.string.missing_value),
            ),
        )

        Text(
            text = stringResource(R.string.metadata_title),
            style = MaterialTheme.typography.titleMedium,
        )
        MetadataField(
            label = stringResource(R.string.metadata_key),
            value = event.key ?: stringResource(R.string.missing_value),
        )
        MetadataField(
            label = stringResource(R.string.metadata_package),
            value = event.packageName ?: stringResource(R.string.missing_value),
        )
        MetadataField(
            label = stringResource(R.string.metadata_notification_id),
            value = event.notificationId?.toString() ?: stringResource(R.string.missing_value),
        )
        MetadataField(
            label = stringResource(R.string.metadata_tag),
            value = event.tag ?: stringResource(R.string.missing_value),
        )
        MetadataField(
            label = stringResource(R.string.metadata_group_key),
            value = event.groupKey ?: stringResource(R.string.missing_value),
        )
        MetadataField(
            label = stringResource(R.string.metadata_group_summary),
            value = when (event.groupSummary) {
                true -> stringResource(R.string.group_yes)
                false -> stringResource(R.string.group_no)
                null -> stringResource(R.string.missing_value)
            },
        )

        MetadataField(
            label = stringResource(R.string.event_title),
            value = event.title ?: stringResource(R.string.missing_value),
        )
        MetadataField(
            label = stringResource(R.string.event_text),
            value = event.text ?: stringResource(R.string.missing_value),
        )
        MetadataField(
            label = stringResource(R.string.event_big_text),
            value = event.bigText ?: stringResource(R.string.missing_value),
        )
        Text(stringResource(R.string.event_lines), style = MaterialTheme.typography.labelLarge)
        if (event.lines.isEmpty()) {
            Text(stringResource(R.string.lines_missing), style = MaterialTheme.typography.bodyLarge)
        } else {
            event.lines.forEachIndexed { index, line ->
                Text("${index + 1}. $line", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun ShowInterpretation(event: DiagnosticEvent) {
    val interpretation = event.interpretation
    when (interpretation?.kind) {
        CandidateKind.TAG_DEBIT,
        CandidateKind.PIX_CREDIT,
        -> {
            interpretation.amountCents?.let { cents ->
                Text(
                    text = stringResource(
                        R.string.candidate_amount,
                        formatCandidateAmount(cents),
                    ),
                )
            }
        }

        CandidateKind.UNKNOWN ->
            interpretation.reason?.let { reason ->
                Text(stringResource(R.string.classification_reason, reason))
            }

        CandidateKind.GROUP_SUMMARY,
        null,
        -> Unit
    }
}

@Composable
private fun MetadataField(
    label: String,
    value: String,
) {
    Text(
        text = stringResource(R.string.metadata_field, label, value),
        style = MaterialTheme.typography.bodyLarge,
    )
}

internal fun formatDiagnosticTimestamp(timestampMs: Long): String =
    DateFormat.getDateTimeInstance(
        DateFormat.MEDIUM,
        DateFormat.MEDIUM,
        Locale.getDefault(),
    ).format(Date(timestampMs))

private fun formatCandidateAmount(amountCents: Long): String =
    NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"))
        .format(BigDecimal.valueOf(amountCents, 2))

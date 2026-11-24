package br.com.c6tag.diagnostico.ui.monitor

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

@Composable
internal fun BrlMoneyField(
    value: String,
    onValueChange: (BrlMoneyInputChange) -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    identityKey: Any? = Unit,
) {
    var edit by remember(identityKey) { mutableStateOf(moneyEditFromCanonical(value)) }
    var observedValue by remember(identityKey) { mutableStateOf(value) }
    var pendingLocalValue by remember(identityKey) { mutableStateOf<String?>(null) }

    SideEffect {
        if (value != observedValue) {
            if (value != pendingLocalValue) {
                edit = moneyEditFromCanonical(value)
            }
            pendingLocalValue = null
            observedValue = value
        }
    }

    val presentation = moneyPresentation(edit)
    OutlinedTextField(
        value = TextFieldValue(
            text = edit.digits,
            selection = TextRange(edit.selection.start, edit.selection.end),
        ),
        onValueChange = { proposed ->
            val previous = edit
            when (val result = applyMoneyEdit(
                previous = previous,
                proposedText = proposed.text,
                selection = BrlMoneySelection(proposed.selection.start, proposed.selection.end),
            )) {
                is BrlMoneyEditResult.Accepted -> {
                    edit = result.edit
                    val canonicalText = moneyCanonicalText(result.edit)
                    pendingLocalValue = canonicalText.takeIf { it != value }
                    if (proposed.text != previous.digits) {
                        onValueChange(BrlMoneyInputChange(canonicalText))
                    }
                }
                is BrlMoneyEditResult.Rejected -> {
                    onValueChange(BrlMoneyInputChange(moneyCanonicalText(previous), invalid = true))
                }
            }
        },
        modifier = modifier,
        label = label,
        prefix = { Text("R$") },
        supportingText = supportingText,
        isError = isError,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            autoCorrectEnabled = false,
        ),
        visualTransformation = VisualTransformation {
            TransformedText(
                text = AnnotatedString(presentation.text),
                offsetMapping = object : OffsetMapping {
                    override fun originalToTransformed(offset: Int): Int =
                        presentation.originalToTransformed[
                            offset.coerceIn(0, presentation.originalToTransformed.lastIndex)
                        ]

                    override fun transformedToOriginal(offset: Int): Int =
                        presentation.transformedToOriginal[
                            offset.coerceIn(0, presentation.transformedToOriginal.lastIndex)
                        ]
                },
            )
        },
    )
}

package br.com.c6tag.diagnostico.ui.monitor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.platform.testTag
import br.com.c6tag.diagnostico.ui.theme.MonitorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BrlMoneyFieldTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun digitsProduceCanonicalMoneyAndCursorMovementDoesNotEmitMoneyChanges() {
        val value = mutableStateOf("")
        val changes = mutableListOf<BrlMoneyInputChange>()
        compose.setContent {
            MonitorTheme {
                BrlMoneyField(
                    value = value.value,
                    onValueChange = { change ->
                        changes += change
                        if (!change.invalid) {
                            value.value = change.canonicalText
                        }
                    },
                    label = { Text("Valor") },
                    modifier = Modifier.fillMaxWidth().testTag("money-field"),
                )
            }
        }

        compose.onNodeWithTag("money-field").performTextInput("124500")

        assertEquals(BrlMoneyInputChange("1245,00"), changes.last())
        compose.onNodeWithTag("money-field").assertTextContains("1.245,00")
        compose.runOnIdle { changes.clear() }
        compose.onNodeWithTag("money-field").performTextInputSelection(TextRange(2, 2))

        compose.runOnIdle { assertTrue(changes.isEmpty()) }
    }

    @Test
    fun overflowReportsInvalidAndKeepsTheLastCanonicalValue() {
        val value = mutableStateOf("1245,00")
        val changes = mutableListOf<BrlMoneyInputChange>()
        compose.setContent {
            MonitorTheme {
                BrlMoneyField(
                    value = value.value,
                    onValueChange = { change ->
                        changes += change
                        if (!change.invalid) {
                            value.value = change.canonicalText
                        }
                    },
                    label = { Text("Valor") },
                    modifier = Modifier.fillMaxWidth().testTag("money-field"),
                )
            }
        }

        compose.onNodeWithTag("money-field").performTextReplacement("9223372036854775808")

        assertEquals(BrlMoneyInputChange("1245,00", invalid = true), changes.last())
        compose.onNodeWithTag("money-field").assertTextContains("1.245,00")
    }

    @Test
    fun malformedTextReportsInvalidWithoutReplacingTheLastCanonicalValue() {
        val value = mutableStateOf("1245,00")
        val changes = mutableListOf<BrlMoneyInputChange>()
        compose.setContent {
            MonitorTheme {
                BrlMoneyField(
                    value = value.value,
                    onValueChange = { change ->
                        changes += change
                        if (!change.invalid) {
                            value.value = change.canonicalText
                        }
                    },
                    label = { Text("Valor") },
                    modifier = Modifier.fillMaxWidth().testTag("money-field"),
                )
            }
        }

        compose.onNodeWithTag("money-field").performTextReplacement("abc")

        assertEquals(BrlMoneyInputChange("1245,00", invalid = true), changes.last())
        compose.onNodeWithTag("money-field").assertTextContains("1.245,00")
    }

    @Test
    fun clearingTheFieldEmitsAnAbsentCanonicalValue() {
        val value = mutableStateOf("1245,00")
        val changes = mutableListOf<BrlMoneyInputChange>()
        compose.setContent {
            MonitorTheme {
                BrlMoneyField(
                    value = value.value,
                    onValueChange = { change ->
                        changes += change
                        if (!change.invalid) {
                            value.value = change.canonicalText
                        }
                    },
                    label = { Text("Valor") },
                    modifier = Modifier.fillMaxWidth().testTag("money-field"),
                )
            }
        }

        compose.onNodeWithTag("money-field").performTextClearance()

        assertEquals(BrlMoneyInputChange(""), changes.last())
        compose.onNodeWithText("R$").assertIsDisplayed()
    }
}

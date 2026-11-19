package br.com.c6tag.diagnostico.remote.probe

import android.content.Intent
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import br.com.c6tag.diagnostico.R

internal object DebugAlertProbeEntry {
    @Composable
    fun Content() {
        val context = LocalContext.current
        TextButton(
            onClick = {
                context.startActivity(Intent(context, AlertProbeActivity::class.java))
            },
            modifier = Modifier.testTag("probe-open"),
        ) {
            Text(stringResource(R.string.probe_open))
        }
    }
}

package br.com.c6tag.diagnostico.remote.probe

import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.com.c6tag.diagnostico.R

internal object DebugAlertProbeEntry {
    @Composable
    fun Content() {
        val context = LocalContext.current
        OutlinedButton(
            onClick = {
                context.startActivity(Intent(context, AlertProbeActivity::class.java))
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("probe-open"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                stringResource(R.string.probe_open),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
            )
        }
    }
}

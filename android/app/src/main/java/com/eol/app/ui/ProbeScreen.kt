package com.eol.app.ui

import android.util.Log
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.eol.app.CameraProbe
import com.eol.app.CodecProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


@Composable
fun ProbeScreen(modifier: Modifier = Modifier, log: Boolean = true) {
    val ctx = LocalContext.current

    var report by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.Default) {
            val camReport = CameraProbe.probeCamera(
                context = ctx
            )
            val codReport = CodecProbe.probeCodec()

            if (log) {
                for (l in camReport.lines()) Log.i("Eol.camera_report", l)
                for (l in codReport.lines()) Log.i("Eol.codec_report", l)
            }

            buildString {
                appendLine(camReport)
                appendLine(codReport)
            }
        }
    }

    Text(
        text = report ?: "Probing...",
        modifier = modifier.verticalScroll(rememberScrollState())
    )
}
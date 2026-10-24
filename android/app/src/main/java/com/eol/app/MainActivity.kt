package com.eol.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.eol.app.ui.theme.EolTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cameraReport = CameraProbe.probeCamera(
            context = this
        )
        val codecReport = CodecProbe.probeCodec()

        val report = buildString {
            appendLine(cameraReport)
            appendLine(codecReport)
        }
        report.lines().forEach { Log.i("EolCamera", it) }
        enableEdgeToEdge()
        setContent {
            EolTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        report = report,
                        modifier = Modifier.padding(innerPadding)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}

@Composable
fun Greeting(report: String, modifier: Modifier = Modifier) {
    Text(
        text = report,
        modifier = modifier,
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    EolTheme {
        Greeting("Android")
    }
}
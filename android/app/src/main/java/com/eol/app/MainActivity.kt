package com.eol.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.eol.app.capture.BaselineCapture
import com.eol.app.capture.FrameLog
import com.eol.app.ui.BaselineCaptureScreen
import com.eol.app.ui.HomeScreen
import com.eol.app.ui.HomeAction
import com.eol.app.ui.ProbeScreen
import com.eol.app.ui.theme.EolTheme

enum class Screen {
    Home,
    Probe,
    BaselineCapture,
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var screen by rememberSaveable { mutableStateOf(Screen.Home) }
            EolTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    when (screen) {
                        Screen.Home -> HomeScreen(
                            listOf(
                                HomeAction("Probe") { screen = Screen.Probe },
                                HomeAction("Baseline Capture") { screen = Screen.BaselineCapture}
                            ), modifier = Modifier.padding(innerPadding)
                        )

                        Screen.Probe -> {
                            BackHandler { screen = Screen.Home }
                            ProbeScreen(modifier = Modifier.padding(innerPadding), log = true)
                        }

                        Screen.BaselineCapture -> {
                            BackHandler { screen = Screen.Home }
                            BaselineCaptureScreen(modifier = Modifier.padding(innerPadding))
                        }
                    }

                }
            }
        }
    }
}
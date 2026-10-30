package com.eol.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.eol.app.CameraProbe
import com.eol.app.capture.recordBaseline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

sealed interface CaptureState {
    data object Idle : CaptureState
    data class Running(val progress: Double) : CaptureState
    data class Done(val file: File, val failed: Int, val lost: Int) : CaptureState
    data class Failed(val message: String) : CaptureState
}

@Composable
fun BaselineCaptureScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var cameraAuthorized by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            cameraAuthorized = granted
        }
    LaunchedEffect(Unit) {
        if (!cameraAuthorized) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }
    val scope = rememberCoroutineScope()

    var captureState: CaptureState by remember { mutableStateOf(CaptureState.Idle) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (cameraAuthorized) {
            when (val s = captureState) {
                CaptureState.Idle -> ParameterForm(modifier = Modifier) { camId, time ->
                    captureState = CaptureState.Running(0.0)
                    scope.launch {
                        captureState = try {
                            val r = recordBaseline(context, camId, time) { captureState = CaptureState.Running(it) }
                            CaptureState.Done(r.file, r.failedCaptures, r.lostBuffers)
                        } catch (e: Exception) {
                            // Cancellation (Back pressed) must propagate, not show as a failure.
                            if (e is CancellationException) throw e
                            CaptureState.Failed(message = e.message ?: e.javaClass.simpleName)
                        }
                    }
                }

                is CaptureState.Running -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Capturing: %.0f %%".format(s.progress.coerceAtMost(1.0) * 100))
                        LinearProgressIndicator(progress = { s.progress.toFloat() })
                    }
                }

                is CaptureState.Failed -> {
                    Column {
                        Text(
                            "Error while capturing: ${s.message}",
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = { captureState = CaptureState.Idle }) {
                            Text("New capture")
                        }
                    }
                }

                is CaptureState.Done -> {
                    Column {
                        Text(buildString {
                            appendLine("Capture done")
                            appendLine("Failed captures: ${s.failed}")
                            appendLine("Lost buffers: ${s.lost}")
                            appendLine("Output file: ${s.file.path}")
                        })
                        Button(onClick = { captureState = CaptureState.Idle }) {
                            Text("New capture")
                        }
                    }
                }
            }
        } else {
            Text("Missing camera authorization, please check your configuration.")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParameterForm(modifier: Modifier = Modifier, onStart: (camId: String, time: Int) -> Unit) {
    val context = LocalContext.current
    val cameraManager = context.getSystemService(CameraManager::class.java)
    val availableCamera = remember { cameraManager.cameraIdList }
    if (availableCamera.size == 0) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No Available cameras")
        }
        return
    }

    val camId = rememberTextFieldState(availableCamera[0])
    var camMenu by remember { mutableStateOf(false) }

    val secondText = rememberTextFieldState("60")

    val second = secondText.text.toString().toIntOrNull()
    val canStart = (second ?: 0) > 0

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .fillMaxWidth()
    ) {
        ExposedDropdownMenuBox(
            expanded = camMenu,
            modifier = Modifier,
            onExpandedChange = { camMenu = it }
        ) {
            OutlinedTextField(
                // The `menuAnchor` modifier must be passed to the text field to handle
                // expanding/collapsing the menu on click. A read-only text field has
                // the anchor type `PrimaryNotEditable`.
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                state = camId,
                readOnly = true,
                lineLimits = TextFieldLineLimits.SingleLine,
                label = { Text("Camera Id") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = camMenu) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            )
            ExposedDropdownMenu(expanded = camMenu, onDismissRequest = { camMenu = false }) {
                availableCamera.forEach { avCam ->
                    val cars = cameraManager.getCameraCharacteristics(avCam)
                    DropdownMenuItem(
                        text = {
                            Text(
                                "${avCam}: Facing ${
                                    CameraProbe.FACING[cars.get(
                                        CameraCharacteristics.LENS_FACING
                                    )]
                                }",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        },
                        onClick = {
                            camId.setTextAndPlaceCursorAtEnd(avCam)
                            camMenu = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }


            }
        }
        OutlinedTextField(
            state = secondText,
            modifier = Modifier,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            label = { Text("Record time (seconds)") }
        )
        Button(
            onClick = { onStart(camId.text.toString(), second ?: 0) },
            enabled = canStart
        ) {
            Text("Start capture")
        }
    }
}
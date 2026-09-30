/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.soundflashlight // Replace with your actual package name

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    
    private lateinit var cameraManager: CameraManager
    private var cameraId: String? = null
    
    // Request permissions launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
            // Permission granted
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraId = cameraManager.cameraIdList.firstOrNull { 
            cameraManager.getCameraCharacteristics(it)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true 
        }

        // Request permissions on startup
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SoundFlashlightScreen(
                        onTriggerFlashlight = { duration -> triggerFlashlight(duration) },
                        checkPermission = { 
                            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED 
                        }
                    )
                }
            }
        }
    }

    private fun triggerFlashlight(durationMs: Long) {
        if (cameraId == null) return
        CoroutineScope(Dispatchers.Main).launch {
            try {
                cameraManager.setTorchMode(cameraId!!, true)
                delay(durationMs)
                cameraManager.setTorchMode(cameraId!!, false)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

@Composable
fun SoundFlashlightScreen(
    onTriggerFlashlight: (Long) -> Unit,
    checkPermission: () -> Boolean
) {
    var isListening by remember { mutableStateOf(false) }
    var threshold by remember { mutableFloatStateOf(15000f) }
    var duration by remember { mutableFloatStateOf(1000f) } // in milliseconds
    var currentAmplitude by remember { mutableIntStateOf(0) }

    // Audio listening coroutine
    LaunchedEffect(isListening) {
        if (isListening && checkPermission()) {
            withContext(Dispatchers.IO) {
                val sampleRate = 44100
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate, channelConfig, audioFormat, bufferSize
                )

                val buffer = ShortArray(bufferSize)
                audioRecord.startRecording()

                try {
                    while (isActive && isListening) {
                        val readSize = audioRecord.read(buffer, 0, bufferSize)
                        if (readSize > 0) {
                            val maxAmplitude = buffer.maxOfOrNull { abs(it.toInt()) } ?: 0
                            currentAmplitude = maxAmplitude

                            if (maxAmplitude > threshold) {
                                onTriggerFlashlight(duration.toLong())
                                // Cooldown to prevent constant triggering while light is on
                                delay(duration.toLong() + 500L) 
                            }
                        }
                        delay(50) // Polling rate
                    }
                } finally {
                    audioRecord.stop()
                    audioRecord.release()
                    currentAmplitude = 0
                }
            }
        }
    }

    Column(
        modifier = Modifier.padding(24.dp).fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Sound Flashlight", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))

        Text("Current Sound Level: $currentAmplitude")
        LinearProgressIndicator(
            progress = { (currentAmplitude / 32767f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text("Activation Threshold: ${threshold.toInt()}")
        Slider(
            value = threshold,
            onValueChange = { threshold = it },
            valueRange = 1000f..32000f // 16-bit PCM max is 32767
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text("Flashlight Duration: ${duration.toInt()} ms")
        Slider(
            value = duration,
            onValueChange = { duration = it },
            valueRange = 100f..5000f // 100ms to 5 seconds
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { isListening = !isListening },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text(if (isListening) "Stop Listening" else "Start Listening")
        }
    }
}

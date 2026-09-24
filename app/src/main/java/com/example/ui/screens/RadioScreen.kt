package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.AudioCaptureService
import com.example.ui.components.WaveformVisualizer
import com.example.viewmodel.OperatingMode
import com.example.viewmodel.SplinterMeshViewModel

@Composable
fun RadioScreen(
    viewModel: SplinterMeshViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val operatingMode by viewModel.operatingMode.collectAsState()
    val isRadioActive by viewModel.isRadioActive.collectAsState()
    val radioModeTitle by viewModel.radioModeTitle.collectAsState()
    val isDucking by viewModel.audioMeshEngine.isAudioDuckingActive.collectAsState()
    val radioAmp by viewModel.audioMeshEngine.radioAmplitude.collectAsState()

    // Music file streaming state
    val trackTitle by viewModel.musicStreamManager.trackTitle.collectAsState()
    val isMusicPlaying by viewModel.musicStreamManager.isPlaying.collectAsState()
    val currentTrackUri by viewModel.musicStreamManager.currentTrackUri.collectAsState()
    val durationMs by viewModel.musicStreamManager.trackDurationMs.collectAsState()
    val currentPosMs by viewModel.musicStreamManager.currentPositionMs.collectAsState()

    // Client volume state
    val clientVolume by viewModel.clientRadioVolume.collectAsState()

    // MediaProjection permission launcher for System Audio Capture
    val mediaProjectionManager = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                // Ensure foreground service is running before calling getMediaProjection (mandatory on Android 14+)
                AudioCaptureService.startService(context)
                val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                val projection = mpManager?.getMediaProjection(result.resultCode, result.data!!)
                if (projection != null) {
                    viewModel.setMediaProjectionForRadio(projection)
                } else {
                    AudioCaptureService.stopService(context)
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                AudioCaptureService.stopService(context)
            }
        } else {
            AudioCaptureService.stopService(context)
        }
    }

    // Audio file picker launcher for streaming local music files
    val musicPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.loadMusicTrack(uri)
            viewModel.playMusic()
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Radio Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isRadioActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isRadioActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Radio,
                                contentDescription = "Radio",
                                tint = if (isRadioActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = "Splinter Radio",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                                color = if (isRadioActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = when {
                                    isRadioActive && operatingMode == OperatingMode.HOST -> "BROADCASTING: $radioModeTitle"
                                    isRadioActive && operatingMode == OperatingMode.CLIENT -> "RECEIVING: $radioModeTitle"
                                    else -> "OFFLINE / STANDBY"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (isRadioActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (isRadioActive && operatingMode == OperatingMode.HOST) {
                        Button(
                            onClick = { viewModel.stopRadio() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("stop_radio_button")
                        ) {
                            Icon(imageVector = Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("STOP")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Real-Time Radio Waveform Visualizer
                WaveformVisualizer(
                    amplitude = if (isRadioActive) radioAmp.coerceAtLeast(0.2f) else 0.05f,
                    isActive = isRadioActive,
                    waveColor = MaterialTheme.colorScheme.secondary,
                    secondaryColor = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Smart Audio Ducking Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isDucking) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isDucking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (isDucking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isDucking) Icons.Default.VolumeDown else Icons.Default.VolumeUp,
                        contentDescription = "Ducking",
                        tint = if (isDucking) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = if (isDucking) "Smart Audio Ducking ACTIVE (-75%)" else "Smart Audio Ducking Ready",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (isDucking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Whenever someone speaks on the intercom, music volume automatically ducks by 75% on all client nodes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // HOST CONTROLS
        if (operatingMode == OperatingMode.HOST) {
            // Option 1: Local Music File Streaming
            Text(
                text = "STREAM MUSIC FILE TO CLIENTS",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black, letterSpacing = 1.sp),
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Local Music Player & Mesh Streamer",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Pick and stream any music file from your device (MP3, M4A, FLAC, WAV) to all connected mesh nodes in real-time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    if (currentTrackUri != null) {
                        // Track Card
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = "Track",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = trackTitle ?: "Loaded Track",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "${formatMs(currentPosMs)} / ${formatMs(durationMs)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    }
                                }

                                if (durationMs > 0) {
                                    Slider(
                                        value = currentPosMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                                        onValueChange = { viewModel.seekMusic(it.toLong()) },
                                        valueRange = 0f..durationMs.toFloat(),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp),
                                        colors = SliderDefaults.colors(
                                            thumbColor = MaterialTheme.colorScheme.primary,
                                            activeTrackColor = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (isMusicPlaying) {
                                        Button(
                                            onClick = { viewModel.pauseMusic() },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("pause_music_button"),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.Pause, contentDescription = "Pause")
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("PAUSE")
                                        }
                                    } else {
                                        Button(
                                            onClick = { viewModel.playMusic() },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("play_music_button"),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Play")
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("PLAY")
                                        }
                                    }

                                    OutlinedButton(
                                        onClick = { viewModel.stopMusic() },
                                        modifier = Modifier.testTag("stop_music_button"),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.Stop, contentDescription = "Stop")
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("STOP")
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    Button(
                        onClick = { musicPickerLauncher.launch("audio/*") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("choose_music_file_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.FolderOpen, contentDescription = "Pick Music")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (currentTrackUri == null) "CHOOSE MUSIC FILE" else "CHOOSE DIFFERENT FILE", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Option 2: Universal Host Audio Capture (System Loopback) Section
            Text(
                text = "UNIVERSAL HOST AUDIO CAPTURE",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black, letterSpacing = 1.sp),
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "System-Wide Audio Loopback",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Streams live audio from background apps (Spotify, YouTube, Apple Music, SoundCloud, Chrome) directly to all mesh clients.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            // Ensure foreground service is running before projection request
                            AudioCaptureService.startService(context)
                            val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                            if (mpManager != null) {
                                mediaProjectionManager.launch(mpManager.createScreenCaptureIntent())
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("start_loopback_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Cast, contentDescription = "Loopback")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("START SYSTEM AUDIO CAPTURE", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            // CLIENT MODE CONTROLS
            Text(
                text = "CLIENT RADIO RECEIVER",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black, letterSpacing = 1.sp),
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Mesh Radio Receiver & Volume",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isRadioActive) "Receiving live music broadcast from Host (Node-0)." else "Standing by. Music will play automatically when the Host starts streaming.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Client Volume Control
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { viewModel.setClientRadioVolume(0f) },
                            modifier = Modifier.testTag("mute_volume_button")
                        ) {
                            Icon(
                                imageVector = if (clientVolume <= 0.01f) Icons.Default.VolumeMute else Icons.Default.VolumeDown,
                                contentDescription = "Mute",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        Slider(
                            value = clientVolume,
                            onValueChange = { viewModel.setClientRadioVolume(it) },
                            valueRange = 0f..1f,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("client_radio_volume_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )

                        IconButton(
                            onClick = { viewModel.setClientRadioVolume(1f) },
                            modifier = Modifier.testTag("max_volume_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.VolumeUp,
                                contentDescription = "Max",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Volume: ${(clientVolume * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = { viewModel.setClientRadioVolume(0f) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Mute", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = { viewModel.setClientRadioVolume(0.5f) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("50%", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = { viewModel.setClientRadioVolume(1.0f) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("100%", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

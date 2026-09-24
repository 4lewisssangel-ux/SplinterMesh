package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.example.ui.components.WaveformVisualizer
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusYellow
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

    // MediaProjection permission launcher for System Audio Capture
    val mediaProjectionManager = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                val projection = mpManager?.getMediaProjection(result.resultCode, result.data!!)
                if (projection != null) {
                    viewModel.setMediaProjectionForRadio(projection)
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                                text = if (isRadioActive) "BROADCASTING: $radioModeTitle" else "OFFLINE / STANDBY",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (isRadioActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (isRadioActive) {
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

                // Real-Time Radio Waveform
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
                        text = "Whenever someone speaks on the intercom, music volume instantly ducks by 75% on all client nodes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Universal Host Audio Capture (System Loopback) Section
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
                    text = "Captures all audio playing system-wide (Spotify, YouTube, Apple Music, MP3s) via Android AudioPlaybackCapture API without needing manual file picking.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
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
    }
}

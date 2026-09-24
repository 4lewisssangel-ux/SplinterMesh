package com.example.ui.screens

import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.components.ActiveSpeakersBar
import com.example.ui.components.WaveformVisualizer
import com.example.viewmodel.SplinterMeshViewModel

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun IntercomScreen(
    viewModel: SplinterMeshViewModel,
    modifier: Modifier = Modifier
) {
    val isPttPressed by viewModel.isPttPressed.collectAsState()
    val isHandsFree by viewModel.isHandsFreeActive.collectAsState()
    val intercomAmp by viewModel.audioMeshEngine.intercomAmplitude.collectAsState()
    val activeSpeakerId by viewModel.audioMeshEngine.activeSpeakerNodeId.collectAsState()
    val isDucking by viewModel.audioMeshEngine.isAudioDuckingActive.collectAsState()
    val nodes by viewModel.nodes.collectAsState()
    val myNodeId by viewModel.myNodeId.collectAsState()

    val isTransmitting = isPttPressed || isHandsFree
    val isReceiving = activeSpeakerId != null && activeSpeakerId != myNodeId

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Connected Nodes Presence
        ActiveSpeakersBar(
            nodes = nodes,
            activeSpeakerId = activeSpeakerId,
            modifier = Modifier.testTag("active_speakers_bar")
        )

        // Active Transmission Status Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    isTransmitting -> MaterialTheme.colorScheme.primaryContainer
                    isReceiving -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            ),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = when {
                            isTransmitting -> Icons.Default.Mic
                            isReceiving -> Icons.Default.VolumeUp
                            else -> Icons.Default.MicOff
                        },
                        contentDescription = "Status Icon",
                        tint = when {
                            isTransmitting -> MaterialTheme.colorScheme.primary
                            isReceiving -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            isTransmitting -> "LIVE ON AIR (YOU ARE TRANSMITTING)"
                            isReceiving -> "RECEIVING FROM NODE-$activeSpeakerId"
                            else -> "CHANNEL STANDBY (LISTEN / PTT)"
                        },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Black),
                        color = when {
                            isTransmitting -> MaterialTheme.colorScheme.onPrimaryContainer
                            isReceiving -> MaterialTheme.colorScheme.onSecondaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Real-Time Waveform Visualizer
                WaveformVisualizer(
                    amplitude = intercomAmp,
                    isActive = isTransmitting || isReceiving,
                    waveColor = when {
                        isTransmitting -> MaterialTheme.colorScheme.primary
                        isReceiving -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.outline
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Large PTT (Push-to-Talk) Button
        val infiniteTransition = rememberInfiniteTransition(label = "ptt_pulse")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = if (isPttPressed) 1.12f else 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse_scale"
        )

        val ringAlpha by infiniteTransition.animateFloat(
            initialValue = 0.8f,
            targetValue = if (isPttPressed) 0.2f else 0.8f,
            animationSpec = infiniteRepeatable(
                animation = tween(600),
                repeatMode = RepeatMode.Reverse
            ),
            label = "ring_alpha"
        )

        Box(
            modifier = Modifier
                .size(220.dp),
            contentAlignment = Alignment.Center
        ) {
            // Animated outer glow rings when pressed
            if (isPttPressed) {
                Box(
                    modifier = Modifier
                        .size((220 * pulseScale).dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = ringAlpha),
                                    Color.Transparent
                                )
                            )
                        )
                )
            }

            // Outer border ring
            Box(
                modifier = Modifier
                    .size(190.dp)
                    .clip(CircleShape)
                    .border(
                        width = if (isPttPressed) 4.dp else 2.dp,
                        color = if (isPttPressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        shape = CircleShape
                    )
                    .background(
                        if (isPttPressed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    )
                    .pointerInteropFilter { motionEvent ->
                        when (motionEvent.action) {
                            MotionEvent.ACTION_DOWN -> {
                                viewModel.startPtt()
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                viewModel.stopPtt()
                                true
                            }
                            else -> false
                        }
                    }
                    .testTag("ptt_button"),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "PTT Icon",
                        tint = if (isPttPressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .size(54.dp)
                            .scale(if (isPttPressed) 1.2f else 1f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isPttPressed) "TRANSMITTING" else "HOLD TO TALK",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        ),
                        color = if (isPttPressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isPttPressed) "Release to End" else "Push-to-Talk (PTT)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Hands-Free Mode Toggle Button
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isHandsFree) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
            ),
            border = if (isHandsFree) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Hands-Free / Open-Mic Mode",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (isHandsFree) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isHandsFree) "Continuous microphone broadcasting active" else "Single-tap to keep mic live without holding",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isHandsFree) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Button(
                    onClick = { viewModel.toggleHandsFree() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isHandsFree) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (isHandsFree) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("handsfree_toggle")
                ) {
                    Text(
                        text = if (isHandsFree) "DISABLE" else "ENABLE",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Low-Latency Audio Protocol Details
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                InfoColumn(label = "AUDIO PROTOCOL", value = "Low-Latency UDP (8889)")
                InfoColumn(label = "SAMPLE RATE", value = "16kHz PCM High-Q")
                InfoColumn(label = "DUCKING", value = if (isDucking) "ACTIVE (-75%)" else "OFF")
            }
        }
    }
}

@Composable
private fun InfoColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp),
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

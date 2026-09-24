package com.example.network

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

class SystemAudioCaptureManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val audioMeshEngine: AudioMeshEngine
) {
    private var captureRecord: AudioRecord? = null
    private var mediaProjection: MediaProjection? = null
    private val isCapturing = AtomicBoolean(false)

    private val _isBroadcasting = MutableStateFlow(false)
    val isBroadcasting: StateFlow<Boolean> = _isBroadcasting.asStateFlow()

    private val _broadcastMode = MutableStateFlow("Inactive") // "System Loopback", "Inactive"
    val broadcastMode: StateFlow<String> = _broadcastMode.asStateFlow()

    private var captureJob: Job? = null

    fun setMediaProjection(projection: MediaProjection) {
        this.mediaProjection = projection
    }

    @SuppressLint("MissingPermission")
    fun startSystemLoopbackCapture(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Android 10+ required for AudioPlaybackCapture
            return false
        }
        val projection = mediaProjection ?: return false

        stopBroadcasting()
        isCapturing.set(true)
        _isBroadcasting.value = true
        _broadcastMode.value = "System Audio (Spotify/YT/Music)"

        // Start Foreground Service required on Android 10+ for MediaProjection capture
        try {
            com.example.service.AudioCaptureService.startService(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        captureJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                // Register callback on projection as required on newer Android versions
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    try {
                        projection.registerCallback(object : MediaProjection.Callback() {
                            override fun onStop() {
                                stopBroadcasting()
                            }
                        }, null)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val minBuf = AudioRecord.getMinBufferSize(
                    AudioMeshEngine.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(AudioMeshEngine.BUFFER_SIZE * 4)

                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AudioMeshEngine.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()

                val record = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(minBuf)
                    .build()

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    stopBroadcasting()
                    return@launch
                }

                captureRecord = record
                record.startRecording()
                val buffer = ByteArray(AudioMeshEngine.BUFFER_SIZE)

                while (isCapturing.get() && isActive) {
                    val readBytes = record.read(buffer, 0, buffer.size)
                    if (readBytes > 0) {
                        // Calculate level
                        var sum = 0.0
                        for (i in 0 until readBytes - 1 step 2) {
                            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
                            sum += sample * sample
                        }
                        val rms = kotlin.math.sqrt(sum / (readBytes / 2))
                        val level = ((rms / 10000.0) * 100).toInt().coerceIn(10, 100).toByte()

                        audioMeshEngine.broadcastRadioAudioFrame(buffer, readBytes, level)
                    } else if (readBytes < 0) {
                        break
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                stopBroadcasting()
            } finally {
                stopCaptureInternal()
            }
        }
        return true
    }

    fun stopBroadcasting() {
        isCapturing.set(false)
        _isBroadcasting.value = false
        _broadcastMode.value = "Inactive"

        try {
            com.example.service.AudioCaptureService.stopService(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        captureJob?.cancel()
        stopCaptureInternal()
    }

    private fun stopCaptureInternal() {
        try {
            captureRecord?.stop()
            captureRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        captureRecord = null
    }
}

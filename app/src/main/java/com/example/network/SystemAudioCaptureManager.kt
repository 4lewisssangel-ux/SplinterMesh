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
                        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                        projection.registerCallback(object : MediaProjection.Callback() {
                            override fun onStop() {
                                stopBroadcasting()
                            }
                        }, mainHandler)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                // AudioPlaybackCapture on Android often requires native sample rates (48000 or 44100 Hz, stereo/mono)
                val sampleRateCandidates = intArrayOf(48000, 44100, 16000)
                val channelCandidates = intArrayOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)

                var activeRecord: AudioRecord? = null
                var activeSampleRate = 48000
                var activeChannels = 2

                for (sRate in sampleRateCandidates) {
                    for (ch in channelCandidates) {
                        try {
                            val minBuf = AudioRecord.getMinBufferSize(
                                sRate,
                                ch,
                                AudioFormat.ENCODING_PCM_16BIT
                            )
                            if (minBuf > 0) {
                                val audioFormat = AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(sRate)
                                    .setChannelMask(ch)
                                    .build()

                                val testRecord = AudioRecord.Builder()
                                    .setAudioPlaybackCaptureConfig(config)
                                    .setAudioFormat(audioFormat)
                                    .setBufferSizeInBytes(minBuf * 2)
                                    .build()

                                if (testRecord.state == AudioRecord.STATE_INITIALIZED) {
                                    activeRecord = testRecord
                                    activeSampleRate = sRate
                                    activeChannels = if (ch == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
                                    break
                                } else {
                                    testRecord.release()
                                }
                            }
                        } catch (e: Exception) {
                            // Try next candidate
                        }
                    }
                    if (activeRecord != null) break
                }

                val record = activeRecord
                if (record == null) {
                    stopBroadcasting()
                    return@launch
                }

                captureRecord = record
                record.startRecording()

                val rawBufferSize = 2048
                val rawShortBuffer = ShortArray(rawBufferSize)

                val targetSampleRate = AudioMeshEngine.SAMPLE_RATE // 16000
                val targetFrameSize = AudioMeshEngine.BUFFER_SIZE // 640 bytes = 320 shorts
                val targetBytes = ByteArray(targetFrameSize)
                var targetOffset = 0

                while (isCapturing.get() && isActive) {
                    val readShorts = record.read(rawShortBuffer, 0, rawShortBuffer.size)
                    if (readShorts > 0) {
                        // Step 1: Convert to mono if stereo
                        val monoShorts: ShortArray
                        if (activeChannels > 1) {
                            val monoCount = readShorts / activeChannels
                            monoShorts = ShortArray(monoCount)
                            for (i in 0 until monoCount) {
                                monoShorts[i] = ((rawShortBuffer[i * 2].toInt() + rawShortBuffer[i * 2 + 1].toInt()) / 2).toShort()
                            }
                        } else {
                            monoShorts = rawShortBuffer.copyOf(readShorts)
                        }

                        // Step 2: Resample to 16000 Hz if needed
                        val resampledShorts: ShortArray
                        if (activeSampleRate == targetSampleRate) {
                            resampledShorts = monoShorts
                        } else {
                            val ratio = activeSampleRate.toDouble() / targetSampleRate
                            val outCount = (monoShorts.size / ratio).toInt().coerceAtLeast(0)
                            resampledShorts = ShortArray(outCount)
                            for (i in 0 until outCount) {
                                val srcIdx = (i * ratio).toInt().coerceIn(0, monoShorts.size - 1)
                                resampledShorts[i] = monoShorts[srcIdx]
                            }
                        }

                        // Step 3: Pack into 640-byte frames and broadcast
                        for (sample in resampledShorts) {
                            targetBytes[targetOffset++] = (sample.toInt() and 0xFF).toByte()
                            targetBytes[targetOffset++] = ((sample.toInt() shr 8) and 0xFF).toByte()

                            if (targetOffset >= targetFrameSize) {
                                var sum = 0.0
                                for (j in 0 until targetFrameSize - 1 step 2) {
                                    val s = (targetBytes[j + 1].toInt() shl 8) or (targetBytes[j].toInt() and 0xFF)
                                    sum += s * s
                                }
                                val rms = kotlin.math.sqrt(sum / (targetFrameSize / 2))
                                val level = ((rms / 12000.0) * 100).toInt().coerceIn(5, 100).toByte()

                                audioMeshEngine.broadcastRadioAudioFrame(targetBytes, targetFrameSize, level)
                                targetOffset = 0
                            }
                        }
                    } else if (readShorts < 0) {
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
        audioMeshEngine.setRadioTransmitting(false)

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

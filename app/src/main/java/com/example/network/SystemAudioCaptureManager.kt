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
    private val isSynthBroadcasting = AtomicBoolean(false)

    private val _isBroadcasting = MutableStateFlow(false)
    val isBroadcasting: StateFlow<Boolean> = _isBroadcasting.asStateFlow()

    private val _broadcastMode = MutableStateFlow("Inactive") // "System Loopback", "Offline Radio Synth", "Inactive"
    val broadcastMode: StateFlow<String> = _broadcastMode.asStateFlow()

    private var captureJob: Job? = null
    private var synthJob: Job? = null

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
        _broadcastMode.value = "System Loopback (Spotify/YT)"

        captureJob = coroutineScope.launch(Dispatchers.IO) {
            try {
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

                captureRecord = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(minBuf)
                    .build()

                captureRecord?.startRecording()
                val buffer = ByteArray(AudioMeshEngine.BUFFER_SIZE)

                while (isCapturing.get() && isActive) {
                    val readBytes = captureRecord?.read(buffer, 0, buffer.size) ?: 0
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
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Fallback to offline synth if loopback unsupported in container
                startOfflineRadioSynthesizer()
            } finally {
                stopCaptureInternal()
            }
        }
        return true
    }

    /**
     * Built-in Offline Radio Stream Synthesizer.
     * Generates a warm, low-latency audio stream with melodic chord progressions,
     * sub-bass and beats so users can immediately broadcast and hear live radio
     * across connected nodes without requiring an active external music app.
     */
    fun startOfflineRadioSynthesizer(genre: String = "Lo-Fi Mesh Wave") {
        stopBroadcasting()
        isSynthBroadcasting.set(true)
        _isBroadcasting.value = true
        _broadcastMode.value = "Offline Radio ($genre)"

        synthJob = coroutineScope.launch(Dispatchers.Default) {
            val sampleRate = AudioMeshEngine.SAMPLE_RATE
            val bufferSize = AudioMeshEngine.BUFFER_SIZE
            val pcmBuffer = ByteArray(bufferSize)

            val notes = doubleArrayOf(220.0, 261.63, 329.63, 392.0, 440.0) // A3 minor pentatonic
            var currentNoteIdx = 0
            var sampleCounter = 0L

            while (isSynthBroadcasting.get() && isActive) {
                val baseFreq = notes[currentNoteIdx]
                val subFreq = baseFreq / 2.0

                for (i in 0 until bufferSize step 2) {
                    val t = sampleCounter.toDouble() / sampleRate
                    // Harmonic synthesis: base wave + sub bass + envelope modulation
                    val envelope = 0.7 + 0.3 * sin(2 * PI * 1.5 * t)
                    val s1 = sin(2 * PI * baseFreq * t) * 0.4
                    val s2 = sin(2 * PI * subFreq * t) * 0.35
                    val s3 = sin(2 * PI * (baseFreq * 1.5) * t) * 0.15

                    val sampleVal = ((s1 + s2 + s3) * envelope * 14000.0).toInt().coerceIn(-32767, 32767).toShort()

                    pcmBuffer[i] = (sampleVal.toInt() and 0xFF).toByte()
                    pcmBuffer[i + 1] = ((sampleVal.toInt() shr 8) and 0xFF).toByte()
                    sampleCounter++
                }

                // Change note every 0.6 seconds
                if (sampleCounter % (sampleRate * 0.6).toLong() < bufferSize / 2) {
                    currentNoteIdx = (currentNoteIdx + 1) % notes.size
                }

                val level: Byte = 70
                audioMeshEngine.broadcastRadioAudioFrame(pcmBuffer, bufferSize, level)

                // ~20ms frame delay (bufferSize 640 bytes at 16kHz 16-bit = 20ms)
                delay(19)
            }
        }
    }

    fun stopBroadcasting() {
        isCapturing.set(false)
        isSynthBroadcasting.set(false)
        _isBroadcasting.value = false
        _broadcastMode.value = "Inactive"

        captureJob?.cancel()
        synthJob?.cancel()
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

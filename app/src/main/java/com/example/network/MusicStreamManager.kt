package com.example.network

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class MusicStreamManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val audioMeshEngine: AudioMeshEngine
) {
    private var streamJob: Job? = null
    private val isStreaming = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _trackTitle = MutableStateFlow<String?>(null)
    val trackTitle: StateFlow<String?> = _trackTitle.asStateFlow()

    private val _currentTrackUri = MutableStateFlow<Uri?>(null)
    val currentTrackUri: StateFlow<Uri?> = _currentTrackUri.asStateFlow()

    private val _trackDurationMs = MutableStateFlow(0L)
    val trackDurationMs: StateFlow<Long> = _trackDurationMs.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private var localAudioTrack: AudioTrack? = null

    fun loadTrack(uri: Uri) {
        stopStreaming()
        _currentTrackUri.value = uri

        // Extract Title and Duration
        var title = "Local Music Track"
        var duration = 0L

        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            duration = durStr?.toLongOrNull() ?: 0L

            if (!metaTitle.isNullOrBlank()) {
                title = if (!metaArtist.isNullOrBlank()) "$metaTitle - $metaArtist" else metaTitle
            } else {
                // Query display name from ContentResolver
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx != -1 && cursor.moveToFirst()) {
                        title = cursor.getString(nameIdx) ?: title
                    }
                }
            }
            retriever.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        _trackTitle.value = title
        _trackDurationMs.value = duration
        _currentPositionMs.value = 0L
    }

    fun play() {
        val uri = _currentTrackUri.value ?: return
        if (isStreaming.get()) {
            if (isPaused.get()) {
                isPaused.set(false)
                _isPlaying.value = true
                localAudioTrack?.play()
            }
            return
        }

        isStreaming.set(true)
        isPaused.set(false)
        _isPlaying.value = true
        audioMeshEngine.setRadioTransmitting(true)

        initLocalAudioTrack()

        streamJob = coroutineScope.launch(Dispatchers.IO) {
            var extractor: MediaExtractor? = null
            var codec: MediaCodec? = null

            try {
                extractor = MediaExtractor()
                extractor.setDataSource(context, uri, null)

                var audioTrackIndex = -1
                var inputFormat: MediaFormat? = null

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioTrackIndex = i
                        inputFormat = format
                        break
                    }
                }

                if (audioTrackIndex == -1 || inputFormat == null) {
                    stopStreaming()
                    return@launch
                }

                extractor.selectTrack(audioTrackIndex)

                // Seek if starting from a non-zero position
                val startPositionUs = _currentPositionMs.value * 1000L
                if (startPositionUs > 0) {
                    extractor.seekTo(startPositionUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                }
                val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: ""
                val sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else 44100
                val channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else 2

                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(inputFormat, null, null, 0)
                codec.start()

                val info = MediaCodec.BufferInfo()
                var isEOS = false

                // We send 640 bytes (320 16-bit samples) at 16000Hz mono = 20ms of audio
                val outFrameSize = AudioMeshEngine.BUFFER_SIZE
                val outFrameBytes = ByteArray(outFrameSize)
                var outFrameOffset = 0

                val targetSampleRate = AudioMeshEngine.SAMPLE_RATE // 16000

                var nextFrameTime = System.currentTimeMillis()

                while (isStreaming.get() && isActive) {
                    if (isPaused.get()) {
                        delay(50)
                        nextFrameTime = System.currentTimeMillis()
                        continue
                    }

                    // Feed input buffer
                    if (!isEOS) {
                        val inIndex = codec.dequeueInputBuffer(10000)
                        if (inIndex >= 0) {
                            val inBuffer = codec.getInputBuffer(inIndex)
                            if (inBuffer != null) {
                                inBuffer.clear()
                                val sampleSize = extractor.readSampleData(inBuffer, 0)
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    isEOS = true
                                } else {
                                    val presentationTimeUs = extractor.sampleTime
                                    codec.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
                                    extractor.advance()
                                    _currentPositionMs.value = presentationTimeUs / 1000
                                }
                            }
                        }
                    }

                    // Drain output buffer
                    val outIndex = codec.dequeueOutputBuffer(info, 10000)
                    if (outIndex >= 0) {
                        val outBuffer = codec.getOutputBuffer(outIndex)
                        if (outBuffer != null && info.size > 0) {
                            outBuffer.position(info.offset)
                            outBuffer.limit(info.offset + info.size)

                            // Read 16-bit PCM samples from decoder
                            val shortBuffer = outBuffer.asShortBuffer()
                            val numSamples = info.size / 2
                            val rawShorts = ShortArray(numSamples)
                            shortBuffer.get(rawShorts)

                            // Downsample / mix to 16000Hz mono
                            val resampledShorts = resampleTo16kMono(
                                rawShorts,
                                srcSampleRate = sampleRate,
                                srcChannels = channelCount,
                                targetSampleRate = targetSampleRate
                            )

                            // Pack into 640-byte (320-short) chunks
                            for (sample in resampledShorts) {
                                outFrameBytes[outFrameOffset++] = (sample.toInt() and 0xFF).toByte()
                                outFrameBytes[outFrameOffset++] = ((sample.toInt() shr 8) and 0xFF).toByte()

                                if (outFrameOffset >= outFrameSize) {
                                    // Calculate RMS level for visualizer
                                    val rms = calculateShortRms(outFrameBytes, outFrameSize)
                                    val level = ((rms / 12000.0) * 100).toInt().coerceIn(10, 100).toByte()

                                    // Broadcast to mesh clients via UDP
                                    audioMeshEngine.broadcastRadioAudioFrame(outFrameBytes, outFrameSize, level)

                                    // Play locally on host audio track
                                    localAudioTrack?.write(outFrameBytes, 0, outFrameSize)

                                    outFrameOffset = 0

                                    // Timing regulation: 20ms per frame
                                    nextFrameTime += 20
                                    val now = System.currentTimeMillis()
                                    val sleepTime = nextFrameTime - now
                                    if (sleepTime > 0) {
                                        delay(sleepTime)
                                    } else if (sleepTime < -100) {
                                        nextFrameTime = now
                                    }
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)

                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            // Track ended
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    codec?.stop()
                    codec?.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                try {
                    extractor?.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                stopStreaming()
            }
        }
    }

    private fun resampleTo16kMono(
        input: ShortArray,
        srcSampleRate: Int,
        srcChannels: Int,
        targetSampleRate: Int
    ): ShortArray {
        // Step 1: Convert to mono if multi-channel
        val monoShorts: ShortArray
        if (srcChannels > 1) {
            val monoLength = input.size / srcChannels
            monoShorts = ShortArray(monoLength)
            for (i in 0 until monoLength) {
                var sum = 0
                for (ch in 0 until srcChannels) {
                    sum += input[i * srcChannels + ch]
                }
                monoShorts[i] = (sum / srcChannels).toShort()
            }
        } else {
            monoShorts = input
        }

        // Step 2: Resample rate if needed
        if (srcSampleRate == targetSampleRate) {
            return monoShorts
        }

        val ratio = srcSampleRate.toDouble() / targetSampleRate
        val outLength = (monoShorts.size / ratio).toInt().coerceAtLeast(0)
        val resampled = ShortArray(outLength)

        for (i in 0 until outLength) {
            val srcIdx = (i * ratio).toInt().coerceIn(0, monoShorts.size - 1)
            resampled[i] = monoShorts[srcIdx]
        }
        return resampled
    }

    private fun calculateShortRms(bytes: ByteArray, length: Int): Double {
        var sum = 0.0
        val sampleCount = length / 2
        for (i in 0 until length - 1 step 2) {
            val sample = (bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)
            sum += sample * sample
        }
        return if (sampleCount > 0) sqrt(sum / sampleCount) else 0.0
    }

    fun pause() {
        isPaused.set(true)
        _isPlaying.value = false
        localAudioTrack?.pause()
    }

    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceIn(0L, _trackDurationMs.value)
        _currentPositionMs.value = clamped
        if (isStreaming.get()) {
            stopStreaming()
            _currentPositionMs.value = clamped
            play()
        }
    }

    fun stopStreaming() {
        isStreaming.set(false)
        isPaused.set(false)
        _isPlaying.value = false
        audioMeshEngine.setRadioTransmitting(false)
        streamJob?.cancel()

        try {
            localAudioTrack?.stop()
            localAudioTrack?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        localAudioTrack = null
    }

    private fun initLocalAudioTrack() {
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                AudioMeshEngine.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(AudioMeshEngine.BUFFER_SIZE * 4)

            localAudioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AudioMeshEngine.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            localAudioTrack?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

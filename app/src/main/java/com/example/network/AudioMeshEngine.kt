package com.example.network

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.example.data.model.MeshConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

class AudioMeshEngine(
    private val coroutineScope: CoroutineScope
) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BUFFER_SIZE = 640 // ~20ms audio frame at 16kHz 16-bit
        const val HEADER_SIZE = 8
        const val PACKET_SIZE = HEADER_SIZE + BUFFER_SIZE
    }

    private var udpSocket: DatagramSocket? = null
    private var isRunning = AtomicBoolean(false)
    private var isRecording = AtomicBoolean(false)
    private var isRadioStreaming = AtomicBoolean(false)

    // Audio recording & playback instances
    private var audioRecord: AudioRecord? = null
    private var intercomAudioTrack: AudioTrack? = null
    private var radioAudioTrack: AudioTrack? = null

    // Target addresses for sending audio
    private var targetIpList = mutableListOf<InetAddress>()
    private var myNodeId: Int = 0

    // Smart ducking state
    private var lastIntercomVoiceMs: Long = 0L
    private val isDucked = AtomicBoolean(false)

    // UI state flows
    private val _intercomAmplitude = MutableStateFlow(0f)
    val intercomAmplitude: StateFlow<Float> = _intercomAmplitude.asStateFlow()

    private val _radioAmplitude = MutableStateFlow(0f)
    val radioAmplitude: StateFlow<Float> = _radioAmplitude.asStateFlow()

    private val _activeSpeakerNodeId = MutableStateFlow<Int?>(null)
    val activeSpeakerNodeId: StateFlow<Int?> = _activeSpeakerNodeId.asStateFlow()

    private val _isAudioDuckingActive = MutableStateFlow(false)
    val isAudioDuckingActive: StateFlow<Boolean> = _isAudioDuckingActive.asStateFlow()

    private var receiveJob: Job? = null
    private var duckingMonitorJob: Job? = null

    fun setNodeId(nodeId: Int) {
        myNodeId = nodeId
    }

    fun updateTargetIps(ips: List<String>) {
        synchronized(targetIpList) {
            targetIpList.clear()
            for (ip in ips) {
                try {
                    targetIpList.add(InetAddress.getByName(ip))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            // Also add subnet broadcast address
            try {
                targetIpList.add(NetworkUtils.getBroadcastAddress())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun startEngine() {
        if (isRunning.getAndSet(true)) return

        try {
            udpSocket = DatagramSocket(MeshConstants.UDP_AUDIO_PORT).apply {
                broadcast = true
                reuseAddress = true
                soTimeout = 2000
            }
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                // If binding to 8889 directly fails, try fallback
                udpSocket = DatagramSocket().apply { broadcast = true }
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }

        initAudioTracks()
        startReceivingAudio()
        startDuckingMonitor()
    }

    private fun initAudioTracks() {
        try {
            val minBufSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG_OUT,
                AUDIO_FORMAT
            ).coerceAtLeast(BUFFER_SIZE * 4)

            // Intercom Track (Voice communication)
            intercomAudioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG_OUT)
                        .build()
                )
                .setBufferSizeInBytes(minBufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            intercomAudioTrack?.play()

            // Radio Track (Media music playback)
            radioAudioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG_OUT)
                        .build()
                )
                .setBufferSizeInBytes(minBufSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            radioAudioTrack?.play()
            radioAudioTrack?.setVolume(1.0f)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startReceivingAudio() {
        receiveJob = coroutineScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(PACKET_SIZE)
            val packet = DatagramPacket(buffer, buffer.size)

            while (isActive && isRunning.get()) {
                try {
                    val socket = udpSocket ?: break
                    socket.receive(packet)

                    if (packet.length < HEADER_SIZE) continue

                    // Verify magic byte
                    if (buffer[0] != 0x53.toByte()) continue

                    val packetType = buffer[1]
                    val senderId = buffer[2].toInt()
                    val volumeLevel = buffer[4].toInt() and 0xFF

                    // Don't loop back our own voice transmissions
                    if (senderId == myNodeId && isRecording.get()) {
                        continue
                    }

                    val audioDataLength = packet.length - HEADER_SIZE
                    val audioData = buffer.copyOfRange(HEADER_SIZE, packet.length)

                    if (packetType == MeshConstants.AUDIO_TYPE_INTERCOM) {
                        // Mark voice activity for smart ducking
                        lastIntercomVoiceMs = System.currentTimeMillis()
                        applyDucking(true)

                        _activeSpeakerNodeId.value = senderId
                        _intercomAmplitude.value = (volumeLevel / 100f).coerceIn(0.1f, 1.0f)

                        intercomAudioTrack?.write(audioData, 0, audioDataLength)
                    } else if (packetType == MeshConstants.AUDIO_TYPE_RADIO) {
                        // If radio is ducked, lower volume is enforced by AudioTrack.setVolume
                        _radioAmplitude.value = (volumeLevel / 100f).coerceIn(0.05f, 1.0f)
                        radioAudioTrack?.write(audioData, 0, audioDataLength)
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // Normal timeout for non-blocking receive check
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        delay(50)
                    }
                }
            }
        }
    }

    private fun startDuckingMonitor() {
        duckingMonitorJob = coroutineScope.launch(Dispatchers.Default) {
            while (isActive && isRunning.get()) {
                delay(150)
                val now = System.currentTimeMillis()
                val isVoiceActive = isRecording.get() || (now - lastIntercomVoiceMs < 1200L)

                if (isVoiceActive != isDucked.get()) {
                    applyDucking(isVoiceActive)
                }

                if (!isVoiceActive && _activeSpeakerNodeId.value != null && !isRecording.get()) {
                    _activeSpeakerNodeId.value = null
                    _intercomAmplitude.value = 0f
                }
            }
        }
    }

    private fun applyDucking(duck: Boolean) {
        isDucked.set(duck)
        _isAudioDuckingActive.value = duck
        try {
            if (duck) {
                // Duck music by 75% -> remaining volume is 25% (0.25f)
                radioAudioTrack?.setVolume(0.25f)
            } else {
                radioAudioTrack?.setVolume(1.0f)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    fun startTransmittingIntercom() {
        if (isRecording.getAndSet(true)) return

        lastIntercomVoiceMs = System.currentTimeMillis()
        applyDucking(true)
        _activeSpeakerNodeId.value = myNodeId

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_IN,
                    AUDIO_FORMAT
                ).coerceAtLeast(BUFFER_SIZE * 2)

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_IN,
                    AUDIO_FORMAT,
                    minBuf
                )

                audioRecord?.startRecording()

                val audioBuffer = ByteArray(BUFFER_SIZE)
                var sequence: Byte = 0

                while (isRecording.get() && isActive) {
                    val readBytes = audioRecord?.read(audioBuffer, 0, BUFFER_SIZE) ?: 0
                    if (readBytes > 0) {
                        // Calculate RMS amplitude
                        val rms = calculateRms(audioBuffer, readBytes)
                        val level = ((rms / 12000.0) * 100).toInt().coerceIn(5, 100).toByte()
                        _intercomAmplitude.value = (level.toInt() and 0xFF) / 100f

                        // Construct packet
                        val packetBytes = ByteArray(HEADER_SIZE + readBytes)
                        packetBytes[0] = 0x53.toByte() // 'S'
                        packetBytes[1] = MeshConstants.AUDIO_TYPE_INTERCOM
                        packetBytes[2] = myNodeId.toByte()
                        packetBytes[3] = sequence++
                        packetBytes[4] = level
                        packetBytes[5] = 0
                        packetBytes[6] = 0
                        packetBytes[7] = 0
                        System.arraycopy(audioBuffer, 0, packetBytes, HEADER_SIZE, readBytes)

                        broadcastUdp(packetBytes)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                stopRecordingInternal()
            }
        }
    }

    fun stopTransmittingIntercom() {
        isRecording.set(false)
        stopRecordingInternal()
        _intercomAmplitude.value = 0f
        if (_activeSpeakerNodeId.value == myNodeId) {
            _activeSpeakerNodeId.value = null
        }
    }

    private fun stopRecordingInternal() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioRecord = null
    }

    fun broadcastRadioAudioFrame(pcmData: ByteArray, length: Int, level: Byte) {
        val packetBytes = ByteArray(HEADER_SIZE + length)
        packetBytes[0] = 0x53.toByte() // 'S'
        packetBytes[1] = MeshConstants.AUDIO_TYPE_RADIO
        packetBytes[2] = myNodeId.toByte()
        packetBytes[3] = 0
        packetBytes[4] = level
        packetBytes[5] = 0
        packetBytes[6] = 0
        packetBytes[7] = 0
        System.arraycopy(pcmData, 0, packetBytes, HEADER_SIZE, length)

        _radioAmplitude.value = (level.toInt() and 0xFF) / 100f
        broadcastUdp(packetBytes)
    }

    private fun broadcastUdp(packetBytes: ByteArray) {
        val socket = udpSocket ?: return
        synchronized(targetIpList) {
            for (dest in targetIpList) {
                try {
                    val datagram = DatagramPacket(
                        packetBytes,
                        packetBytes.size,
                        dest,
                        MeshConstants.UDP_AUDIO_PORT
                    )
                    socket.send(datagram)
                } catch (e: Exception) {
                    // Ignore dropped frame in UDP
                }
            }
        }
    }

    private fun calculateRms(buffer: ByteArray, length: Int): Double {
        var sum = 0.0
        val sampleCount = length / 2
        for (i in 0 until length - 1 step 2) {
            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            sum += sample * sample
        }
        return if (sampleCount > 0) sqrt(sum / sampleCount) else 0.0
    }

    fun stopEngine() {
        isRunning.set(false)
        isRecording.set(false)
        receiveJob?.cancel()
        duckingMonitorJob?.cancel()

        stopRecordingInternal()

        try {
            intercomAudioTrack?.stop()
            intercomAudioTrack?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        intercomAudioTrack = null

        try {
            radioAudioTrack?.stop()
            radioAudioTrack?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        radioAudioTrack = null

        try {
            udpSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        udpSocket = null
    }
}

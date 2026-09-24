package com.example.viewmodel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.ChatMessageEntity
import com.example.data.local.SplinterDatabase
import com.example.data.local.SplinterRepository
import com.example.data.model.MeshControlMessage
import com.example.data.model.MeshNode
import com.example.network.AudioMeshEngine
import com.example.network.ClientSocket
import com.example.network.ConnectionStatus
import com.example.network.HostServer
import com.example.network.MusicStreamManager
import com.example.network.NetworkUtils
import com.example.network.SystemAudioCaptureManager
import com.example.ui.theme.ThemeOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

enum class OperatingMode {
    HOST,
    CLIENT
}

class SplinterMeshViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val repository: SplinterRepository
    val audioMeshEngine: AudioMeshEngine
    val systemAudioCaptureManager: SystemAudioCaptureManager
    val musicStreamManager: MusicStreamManager

    // Host & Client instances
    private var hostServer: HostServer? = null
    private var clientSocket: ClientSocket? = null

    // Operating Mode
    private val _operatingMode = MutableStateFlow(OperatingMode.HOST)
    val operatingMode: StateFlow<OperatingMode> = _operatingMode.asStateFlow()

    // Device identity
    val deviceName = "Splinter-" + Build.MODEL.take(8).replace(" ", "")
    private val _myNodeId = MutableStateFlow(0)
    val myNodeId: StateFlow<Int> = _myNodeId.asStateFlow()

    // Host IP & Manual Target IP
    val localIpAddress: String = NetworkUtils.getLocalIpAddress()
    private val _targetHostIp = MutableStateFlow(localIpAddress)
    val targetHostIp: StateFlow<String> = _targetHostIp.asStateFlow()

    // Client connection status
    private val _clientStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val clientStatus: StateFlow<ConnectionStatus> = _clientStatus.asStateFlow()

    // Connected Nodes list
    private val _nodes = MutableStateFlow<List<MeshNode>>(emptyList())
    val nodes: StateFlow<List<MeshNode>> = _nodes.asStateFlow()

    // Intercom state
    private val _isPttPressed = MutableStateFlow(false)
    val isPttPressed: StateFlow<Boolean> = _isPttPressed.asStateFlow()

    private val _isHandsFreeActive = MutableStateFlow(false)
    val isHandsFreeActive: StateFlow<Boolean> = _isHandsFreeActive.asStateFlow()

    // Radio state
    private val _isRadioActive = MutableStateFlow(false)
    val isRadioActive: StateFlow<Boolean> = _isRadioActive.asStateFlow()

    private val _radioModeTitle = MutableStateFlow("Inactive")
    val radioModeTitle: StateFlow<String> = _radioModeTitle.asStateFlow()

    // Client volume slider
    private val _clientRadioVolume = MutableStateFlow(1.0f)
    val clientRadioVolume: StateFlow<Float> = _clientRadioVolume.asStateFlow()

    // Theme Customization Engine
    private val _selectedTheme = MutableStateFlow(ThemeOption.DARK_SLATE)
    val selectedTheme: StateFlow<ThemeOption> = _selectedTheme.asStateFlow()

    // Snackbar notifications
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    // Room DB Chat Messages
    val chatMessages: StateFlow<List<ChatMessageEntity>>

    init {
        val db = SplinterDatabase.getDatabase(context)
        repository = SplinterRepository(db.chatMessageDao())
        chatMessages = repository.allMessages.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        audioMeshEngine = AudioMeshEngine(viewModelScope)
        systemAudioCaptureManager = SystemAudioCaptureManager(context, viewModelScope, audioMeshEngine)
        musicStreamManager = MusicStreamManager(context, viewModelScope, audioMeshEngine)

        audioMeshEngine.startEngine()

        // Start default in Host mode (Node-0)
        startHostMode()

        // Listen for radio state updates from capture manager
        viewModelScope.launch {
            systemAudioCaptureManager.isBroadcasting.collect { broadcasting ->
                if (_operatingMode.value == OperatingMode.HOST) {
                    if (broadcasting) {
                        _isRadioActive.value = true
                        _radioModeTitle.value = systemAudioCaptureManager.broadcastMode.value
                        hostServer?.broadcastRadioState(true, _radioModeTitle.value)
                    } else if (!musicStreamManager.isPlaying.value) {
                        _isRadioActive.value = false
                        _radioModeTitle.value = "Inactive"
                        hostServer?.broadcastRadioState(false, "Inactive")
                    }
                }
            }
        }

        // Listen for music stream playback updates
        viewModelScope.launch {
            musicStreamManager.isPlaying.collect { playing ->
                if (_operatingMode.value == OperatingMode.HOST) {
                    if (playing) {
                        _isRadioActive.value = true
                        val title = musicStreamManager.trackTitle.value ?: "Music File"
                        _radioModeTitle.value = "Music: $title"
                        hostServer?.broadcastRadioState(true, _radioModeTitle.value)
                    } else if (!systemAudioCaptureManager.isBroadcasting.value) {
                        _isRadioActive.value = false
                        _radioModeTitle.value = "Inactive"
                        hostServer?.broadcastRadioState(false, "Inactive")
                    }
                }
            }
        }
    }

    fun setOperatingMode(mode: OperatingMode) {
        if (_operatingMode.value == mode) return
        _operatingMode.value = mode

        // Stop current intercom/radio
        stopPtt()
        if (_isHandsFreeActive.value) {
            toggleHandsFree()
        }
        stopRadio()

        if (mode == OperatingMode.HOST) {
            clientSocket?.disconnect()
            clientSocket = null
            startHostMode()
        } else {
            hostServer?.stopServer()
            hostServer = null
            startClientMode()
        }
    }

    private fun startHostMode() {
        _myNodeId.value = 0
        audioMeshEngine.setNodeId(0)

        hostServer?.stopServer()
        val server = HostServer(viewModelScope, hostDeviceName = "Host-$deviceName")
        hostServer = server
        server.startServer()

        viewModelScope.launch {
            server.nodesList.collect { list ->
                _nodes.value = list
                audioMeshEngine.updateTargetIps(list.filter { !it.isHost }.map { it.ipAddress })
            }
        }

        viewModelScope.launch {
            server.incomingMessages.collect { chat ->
                receiveChatMessage(chat)
            }
        }

        viewModelScope.launch {
            server.systemEvents.collect { event ->
                logSystemNotification(event)
            }
        }

        logSystemNotification("Host Mode active. Node-0 bound to $localIpAddress:8888")
    }

    private fun startClientMode() {
        hostServer?.stopServer()
        hostServer = null

        val client = ClientSocket(viewModelScope, deviceName = deviceName)
        clientSocket = client

        viewModelScope.launch {
            client.connectionStatus.collect { status ->
                _clientStatus.value = status
            }
        }

        viewModelScope.launch {
            client.assignedNodeId.collect { id ->
                if (id != null) {
                    _myNodeId.value = id
                    audioMeshEngine.setNodeId(id)
                }
            }
        }

        viewModelScope.launch {
            client.nodesList.collect { list ->
                _nodes.value = list
                val hostNode = list.firstOrNull { it.isHost }
                if (hostNode != null) {
                    audioMeshEngine.updateTargetIps(listOf(hostNode.ipAddress))
                }
            }
        }

        viewModelScope.launch {
            client.incomingMessages.collect { chat ->
                receiveChatMessage(chat)
            }
        }

        viewModelScope.launch {
            client.systemEvents.collect { event ->
                logSystemNotification(event)
            }
        }

        viewModelScope.launch {
            client.isHostBroadcastingRadio.collect { active ->
                if (_operatingMode.value == OperatingMode.CLIENT) {
                    _isRadioActive.value = active
                }
            }
        }

        viewModelScope.launch {
            client.radioTitle.collect { title ->
                if (_operatingMode.value == OperatingMode.CLIENT) {
                    _radioModeTitle.value = title
                }
            }
        }

        // Start auto-discovery by default in client mode
        client.startAutoDiscovery()
    }

    fun setTargetHostIp(ip: String) {
        _targetHostIp.value = ip
    }

    fun triggerClientManualConnect() {
        val ip = _targetHostIp.value.trim()
        if (ip.isEmpty()) {
            _snackbarMessage.value = "Please enter a valid Host IP address"
            return
        }
        clientSocket?.connectToHost(ip)
    }

    fun retryAutoDiscovery() {
        clientSocket?.startAutoDiscovery()
    }

    // Intercom: Push-to-Talk
    fun startPtt() {
        if (_isPttPressed.value) return
        _isPttPressed.value = true
        vibrateHaptic(50)
        audioMeshEngine.startTransmittingIntercom()

        val id = _myNodeId.value
        if (_operatingMode.value == OperatingMode.HOST) {
            hostServer?.broadcastSpeakingState(id, true)
        } else {
            clientSocket?.sendSpeakingState(true)
        }
    }

    fun stopPtt() {
        if (!_isPttPressed.value) return
        _isPttPressed.value = false
        if (!_isHandsFreeActive.value) {
            audioMeshEngine.stopTransmittingIntercom()
            val id = _myNodeId.value
            if (_operatingMode.value == OperatingMode.HOST) {
                hostServer?.broadcastSpeakingState(id, false)
            } else {
                clientSocket?.sendSpeakingState(false)
            }
        }
    }

    // Intercom: Hands-Free / Open-Mic Toggle
    fun toggleHandsFree() {
        val newState = !_isHandsFreeActive.value
        _isHandsFreeActive.value = newState
        vibrateHaptic(80)

        val id = _myNodeId.value
        if (newState) {
            audioMeshEngine.startTransmittingIntercom()
            if (_operatingMode.value == OperatingMode.HOST) {
                hostServer?.broadcastSpeakingState(id, true)
            } else {
                clientSocket?.sendSpeakingState(true)
            }
            _snackbarMessage.value = "Hands-Free Open Mic ON"
        } else {
            if (!_isPttPressed.value) {
                audioMeshEngine.stopTransmittingIntercom()
                if (_operatingMode.value == OperatingMode.HOST) {
                    hostServer?.broadcastSpeakingState(id, false)
                } else {
                    clientSocket?.sendSpeakingState(false)
                }
            }
            _snackbarMessage.value = "Hands-Free Open Mic OFF"
        }
    }

    // Live System Audio Broadcast ("Splinter Radio")
    fun setMediaProjectionForRadio(projection: MediaProjection) {
        try {
            // Stop file music if running
            musicStreamManager.stopStreaming()
            systemAudioCaptureManager.setMediaProjection(projection)
            val success = systemAudioCaptureManager.startSystemLoopbackCapture()
            if (success) {
                _isRadioActive.value = true
                _radioModeTitle.value = "System Audio (Spotify/YT/Music)"
                hostServer?.broadcastRadioState(true, _radioModeTitle.value)
                _snackbarMessage.value = "System Audio Capture active — Streaming music"
            } else {
                _isRadioActive.value = false
                _snackbarMessage.value = "Failed to start system audio capture"
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            _isRadioActive.value = false
            _snackbarMessage.value = "Audio capture error: ${e.localizedMessage ?: "Unknown error"}"
        }
    }

    // Local Music File Streaming
    fun loadMusicTrack(uri: Uri) {
        // Stop system audio capture if active
        if (systemAudioCaptureManager.isBroadcasting.value) {
            systemAudioCaptureManager.stopBroadcasting()
        }
        musicStreamManager.loadTrack(uri)
        _snackbarMessage.value = "Loaded: ${musicStreamManager.trackTitle.value ?: "Music File"}"
    }

    fun playMusic() {
        if (systemAudioCaptureManager.isBroadcasting.value) {
            systemAudioCaptureManager.stopBroadcasting()
        }
        musicStreamManager.play()
    }

    fun pauseMusic() {
        musicStreamManager.pause()
    }

    fun stopMusic() {
        musicStreamManager.stopStreaming()
    }

    fun seekMusic(positionMs: Long) {
        musicStreamManager.seekTo(positionMs)
    }

    fun setClientRadioVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        _clientRadioVolume.value = clamped
        audioMeshEngine.setRadioVolume(clamped)
    }

    fun stopRadio() {
        systemAudioCaptureManager.stopBroadcasting()
        musicStreamManager.stopStreaming()
        audioMeshEngine.setRadioTransmitting(false)
        _isRadioActive.value = false
        _radioModeTitle.value = "Inactive"
        if (_operatingMode.value == OperatingMode.HOST) {
            hostServer?.broadcastRadioState(false, "Inactive")
        }
    }

    // Mesh Chat & File/Photo Sharing
    fun sendTextMessage(text: String) {
        if (text.isBlank()) return
        val senderId = _myNodeId.value
        val chat = MeshControlMessage.ChatMessage(
            messageId = UUID.randomUUID().toString(),
            senderId = senderId,
            senderName = if (senderId == 0) "Host ($deviceName)" else "Node-$senderId ($deviceName)",
            text = text.trim(),
            timestamp = System.currentTimeMillis()
        )

        // Store in local DB
        viewModelScope.launch {
            repository.insertMessage(
                ChatMessageEntity(
                    messageId = chat.messageId,
                    senderId = chat.senderId,
                    senderName = chat.senderName,
                    text = chat.text,
                    timestamp = chat.timestamp,
                    isOutgoing = true
                )
            )
        }

        // Broadcast over TCP
        if (_operatingMode.value == OperatingMode.HOST) {
            hostServer?.broadcastChat(chat)
        } else {
            clientSocket?.sendChat(chat)
        }
    }

    fun sendAttachment(type: String, data: String, description: String) {
        val senderId = _myNodeId.value
        val chat = MeshControlMessage.ChatMessage(
            messageId = UUID.randomUUID().toString(),
            senderId = senderId,
            senderName = if (senderId == 0) "Host ($deviceName)" else "Node-$senderId ($deviceName)",
            text = description,
            timestamp = System.currentTimeMillis(),
            attachmentType = type,
            attachmentData = data
        )

        viewModelScope.launch {
            repository.insertMessage(
                ChatMessageEntity(
                    messageId = chat.messageId,
                    senderId = chat.senderId,
                    senderName = chat.senderName,
                    text = chat.text,
                    timestamp = chat.timestamp,
                    attachmentType = chat.attachmentType,
                    attachmentData = chat.attachmentData,
                    isOutgoing = true
                )
            )
        }

        if (_operatingMode.value == OperatingMode.HOST) {
            hostServer?.broadcastChat(chat)
        } else {
            clientSocket?.sendChat(chat)
        }
    }

    private fun receiveChatMessage(chat: MeshControlMessage.ChatMessage) {
        viewModelScope.launch {
            repository.insertMessage(
                ChatMessageEntity(
                    messageId = chat.messageId,
                    senderId = chat.senderId,
                    senderName = chat.senderName,
                    text = chat.text,
                    timestamp = chat.timestamp,
                    attachmentType = chat.attachmentType,
                    attachmentData = chat.attachmentData,
                    isOutgoing = false
                )
            )
        }
    }

    private fun logSystemNotification(text: String) {
        viewModelScope.launch {
            repository.insertMessage(
                ChatMessageEntity(
                    messageId = UUID.randomUUID().toString(),
                    senderId = -1,
                    senderName = "System",
                    text = text,
                    timestamp = System.currentTimeMillis(),
                    isSystemNotification = true
                )
            )
        }
    }

    fun clearChatHistory() {
        viewModelScope.launch {
            repository.clearMessages()
            _snackbarMessage.value = "Chat history cleared"
        }
    }

    // Theme selector
    fun setTheme(theme: ThemeOption) {
        _selectedTheme.value = theme
        _snackbarMessage.value = "Applied theme: ${theme.title}"
    }

    // Developer Support & Clipboard
    fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard?.setPrimaryClip(clip)
        _snackbarMessage.value = "Copied $label: $text"
        vibrateHaptic(30)
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    private fun vibrateHaptic(durationMs: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                v?.vibrate(durationMs)
            }
        } catch (e: Exception) {
            // Ignore if vibrator unavailable
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioMeshEngine.stopEngine()
        systemAudioCaptureManager.stopBroadcasting()
        hostServer?.stopServer()
        clientSocket?.disconnect()
    }
}

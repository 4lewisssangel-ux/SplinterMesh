package com.example.network

import com.example.data.model.MeshConstants
import com.example.data.model.MeshControlMessage
import com.example.data.model.MeshNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

enum class ConnectionStatus {
    DISCONNECTED,
    SEARCHING,
    CONNECTING,
    CONNECTED,
    FULL
}

class ClientSocket(
    private val coroutineScope: CoroutineScope,
    private val deviceName: String = "Client Device"
) {
    private var tcpSocket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null
    private var discoverySocket: DatagramSocket? = null

    private val isRunning = AtomicBoolean(false)

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _assignedNodeId = MutableStateFlow<Int?>(null)
    val assignedNodeId: StateFlow<Int?> = _assignedNodeId.asStateFlow()

    private val _hostIp = MutableStateFlow<String?>(null)
    val hostIp: StateFlow<String?> = _hostIp.asStateFlow()

    private val _nodesList = MutableStateFlow<List<MeshNode>>(emptyList())
    val nodesList: StateFlow<List<MeshNode>> = _nodesList.asStateFlow()

    private val _pingLatencyMs = MutableStateFlow<Long>(0)
    val pingLatencyMs: StateFlow<Long> = _pingLatencyMs.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<MeshControlMessage.ChatMessage>()
    val incomingMessages: SharedFlow<MeshControlMessage.ChatMessage> = _incomingMessages.asSharedFlow()

    private val _systemEvents = MutableSharedFlow<String>()
    val systemEvents: SharedFlow<String> = _systemEvents.asSharedFlow()

    private val _isHostBroadcastingRadio = MutableStateFlow(false)
    val isHostBroadcastingRadio: StateFlow<Boolean> = _isHostBroadcastingRadio.asStateFlow()

    private val _radioTitle = MutableStateFlow("Offline Mesh Radio")
    val radioTitle: StateFlow<String> = _radioTitle.asStateFlow()

    private var discoveryJob: Job? = null
    private var clientJob: Job? = null
    private var pingJob: Job? = null

    fun startAutoDiscovery() {
        if (_connectionStatus.value == ConnectionStatus.CONNECTED) return
        _connectionStatus.value = ConnectionStatus.SEARCHING

        discoveryJob?.cancel()
        discoveryJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                discoverySocket = DatagramSocket(MeshConstants.UDP_DISCOVERY_PORT).apply {
                    broadcast = true
                    reuseAddress = true
                    soTimeout = 3000
                }

                val buffer = ByteArray(512)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isActive && _connectionStatus.value == ConnectionStatus.SEARCHING) {
                    try {
                        discoverySocket?.receive(packet)
                        val text = String(packet.data, 0, packet.length).trim()
                        if (text.startsWith(MeshConstants.BEACON_MAGIC)) {
                            val parts = text.split(":")
                            if (parts.size >= 2) {
                                val discoveredHostIp = parts[1]
                                _hostIp.value = discoveredHostIp
                                _systemEvents.emit("Host discovered at $discoveredHostIp! Connecting…")
                                discoverySocket?.close()
                                connectToHost(discoveredHostIp)
                                break
                            }
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // Keep searching
                    } catch (e: Exception) {
                        delay(500)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun connectToHost(ip: String) {
        discoveryJob?.cancel()
        _hostIp.value = ip
        _connectionStatus.value = ConnectionStatus.CONNECTING

        clientJob?.cancel()
        clientJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, MeshConstants.TCP_PORT), 4000)
                tcpSocket = socket

                reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                writer = PrintWriter(socket.getOutputStream(), true)

                // Send JOIN request
                val joinMsg = MeshControlMessage.JoinRequest(deviceName).toJsonString()
                writer?.println(joinMsg)
                writer?.flush()

                // Await Join response
                val responseLine = reader?.readLine()
                if (responseLine != null) {
                    val response = MeshControlMessage.fromJsonString(responseLine)
                    if (response is MeshControlMessage.JoinResponse) {
                        if (response.status == "CONNECTED") {
                            _assignedNodeId.value = response.assignedId
                            _connectionStatus.value = ConnectionStatus.CONNECTED
                            _systemEvents.emit("Connected to Host! Assigned [Node-${response.assignedId}]")
                            startPingHeartbeat()
                            listenToServer()
                        } else {
                            _connectionStatus.value = ConnectionStatus.FULL
                            _systemEvents.emit("Connection rejected: ${response.message}")
                            disconnect()
                        }
                    } else {
                        disconnect()
                    }
                } else {
                    disconnect()
                }
            } catch (e: Exception) {
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                _systemEvents.emit("Failed to connect to Host at $ip: ${e.localizedMessage}")
                disconnect()
            }
        }
    }

    private fun startPingHeartbeat() {
        pingJob?.cancel()
        pingJob = coroutineScope.launch(Dispatchers.IO) {
            while (isActive && _connectionStatus.value == ConnectionStatus.CONNECTED) {
                delay(MeshConstants.PING_INTERVAL_MS)
                val nodeId = _assignedNodeId.value ?: continue
                val now = System.currentTimeMillis()
                val ping = MeshControlMessage.Ping(nodeId, now).toJsonString()
                sendTcp(ping)
            }
        }
    }

    private suspend fun listenToServer() {
        val inReader = reader ?: return
        try {
            while (_connectionStatus.value == ConnectionStatus.CONNECTED) {
                val line = inReader.readLine() ?: break
                val msg = MeshControlMessage.fromJsonString(line) ?: continue

                when (msg) {
                    is MeshControlMessage.Pong -> {
                        val latency = System.currentTimeMillis() - msg.timestamp
                        _pingLatencyMs.value = latency.coerceAtLeast(0)
                    }
                    is MeshControlMessage.NodeListUpdate -> {
                        _nodesList.value = msg.nodes
                    }
                    is MeshControlMessage.ChatMessage -> {
                        _incomingMessages.emit(msg)
                    }
                    is MeshControlMessage.SpeakingState -> {
                        val currentList = _nodesList.value.toMutableList()
                        val idx = currentList.indexOfFirst { it.nodeId == msg.nodeId }
                        if (idx != -1) {
                            currentList[idx] = currentList[idx].copy(isSpeaking = msg.isSpeaking)
                            _nodesList.value = currentList
                        }
                    }
                    is MeshControlMessage.RadioState -> {
                        _isHostBroadcastingRadio.value = msg.isBroadcasting
                        _radioTitle.value = msg.title
                    }
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            // Disconnection
        } finally {
            if (_connectionStatus.value == ConnectionStatus.CONNECTED) {
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                _systemEvents.emit("Disconnected from Host. Network link lost.")
            }
            disconnect()
        }
    }

    fun sendChat(chatMessage: MeshControlMessage.ChatMessage) {
        sendTcp(chatMessage.toJsonString())
    }

    fun sendSpeakingState(isSpeaking: Boolean) {
        val nodeId = _assignedNodeId.value ?: return
        val msg = MeshControlMessage.SpeakingState(nodeId, isSpeaking).toJsonString()
        sendTcp(msg)
    }

    private fun sendTcp(message: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                writer?.println(message)
                writer?.flush()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun disconnect() {
        pingJob?.cancel()
        discoveryJob?.cancel()
        clientJob?.cancel()

        try {
            tcpSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        tcpSocket = null
        reader = null
        writer = null

        try {
            discoverySocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        discoverySocket = null

        _assignedNodeId.value = null
        if (_connectionStatus.value != ConnectionStatus.FULL) {
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
        }
    }
}

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
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class HostServer(
    private val coroutineScope: CoroutineScope,
    private val hostDeviceName: String = "Host (Node-0)"
) {
    private var serverSocket: ServerSocket? = null
    private var beaconSocket: DatagramSocket? = null
    private val isRunning = AtomicBoolean(false)

    private val connectedClients = ConcurrentHashMap<Int, ClientConnection>()
    private val activeNodeMap = ConcurrentHashMap<Int, MeshNode>()

    private val _nodesList = MutableStateFlow<List<MeshNode>>(emptyList())
    val nodesList: StateFlow<List<MeshNode>> = _nodesList.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<MeshControlMessage.ChatMessage>()
    val incomingMessages: SharedFlow<MeshControlMessage.ChatMessage> = _incomingMessages.asSharedFlow()

    private val _systemEvents = MutableSharedFlow<String>()
    val systemEvents: SharedFlow<String> = _systemEvents.asSharedFlow()

    private var serverJob: Job? = null
    private var beaconJob: Job? = null
    private var heartbeatCheckJob: Job? = null

    private var hostIp: String = "192.168.43.1"

    inner class ClientConnection(
        val nodeId: Int,
        val deviceName: String,
        val socket: Socket,
        val reader: BufferedReader,
        val writer: PrintWriter
    ) {
        var lastPingTime: Long = System.currentTimeMillis()

        fun send(message: String) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    writer.println(message)
                    writer.flush()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun startServer() {
        if (isRunning.getAndSet(true)) return

        hostIp = NetworkUtils.getLocalIpAddress()

        // Register Host Node-0
        val hostNode = MeshNode(
            nodeId = 0,
            deviceName = hostDeviceName,
            ipAddress = hostIp,
            isHost = true,
            joinedAt = System.currentTimeMillis()
        )
        activeNodeMap[0] = hostNode
        updateNodesFlow()

        startTcpServer()
        startBeaconBroadcaster()
        startHeartbeatMonitor()
    }

    private fun startTcpServer() {
        serverJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(MeshConstants.TCP_PORT).apply {
                    reuseAddress = true
                }

                while (isActive && isRunning.get()) {
                    val clientSocket = serverSocket?.accept() ?: break
                    handleNewClient(clientSocket)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun handleNewClient(socket: Socket) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)

                // First message should be JOIN
                val firstLine = reader.readLine() ?: return@launch
                val joinMsg = MeshControlMessage.fromJsonString(firstLine)

                if (joinMsg is MeshControlMessage.JoinRequest) {
                    // Check capacity (Host is 1 node + up to 9 clients = 10 total)
                    if (connectedClients.size >= MeshConstants.MAX_TOTAL_NODES - 1) {
                        val fullResponse = MeshControlMessage.JoinResponse(
                            status = "FULL",
                            assignedId = -1,
                            hostIp = hostIp,
                            message = "Mesh capacity reached (10/10 nodes)"
                        ).toJsonString()
                        writer.println(fullResponse)
                        writer.flush()
                        socket.close()
                        return@launch
                    }

                    // Assign an available Node ID between 1 and 9
                    val assignedId = getNextAvailableId()
                    val clientIp = socket.inetAddress.hostAddress ?: "Unknown"

                    val connection = ClientConnection(
                        nodeId = assignedId,
                        deviceName = joinMsg.deviceName,
                        socket = socket,
                        reader = reader,
                        writer = writer
                    )

                    connectedClients[assignedId] = connection
                    val newNode = MeshNode(
                        nodeId = assignedId,
                        deviceName = joinMsg.deviceName,
                        ipAddress = clientIp,
                        isHost = false
                    )
                    activeNodeMap[assignedId] = newNode
                    updateNodesFlow()

                    // Send JoinResponse
                    val response = MeshControlMessage.JoinResponse(
                        status = "CONNECTED",
                        assignedId = assignedId,
                        hostIp = hostIp
                    ).toJsonString()
                    writer.println(response)
                    writer.flush()

                    _systemEvents.emit("[Node-$assignedId] ${joinMsg.deviceName} joined mesh")

                    // Broadcast updated node list to all clients
                    broadcastNodeList()

                    // Listen for subsequent messages from this client
                    listenToClient(connection)
                } else {
                    socket.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getNextAvailableId(): Int {
        for (i in 1 until MeshConstants.MAX_TOTAL_NODES) {
            if (!connectedClients.containsKey(i)) return i
        }
        return connectedClients.size + 1
    }

    private suspend fun listenToClient(client: ClientConnection) {
        try {
            while (isRunning.get() && client.socket.isConnected && !client.socket.isClosed) {
                val line = client.reader.readLine() ?: break
                val msg = MeshControlMessage.fromJsonString(line) ?: continue

                when (msg) {
                    is MeshControlMessage.Ping -> {
                        client.lastPingTime = System.currentTimeMillis()
                        val latency = System.currentTimeMillis() - msg.timestamp
                        val existing = activeNodeMap[client.nodeId]
                        if (existing != null) {
                            activeNodeMap[client.nodeId] = existing.copy(
                                lastPingMs = client.lastPingTime,
                                pingLatencyMs = latency.coerceAtLeast(0)
                            )
                            updateNodesFlow()
                        }
                        // Send Pong
                        val pong = MeshControlMessage.Pong(msg.timestamp).toJsonString()
                        client.send(pong)
                    }
                    is MeshControlMessage.ChatMessage -> {
                        _incomingMessages.emit(msg)
                        // Broadcast chat to all other clients
                        broadcastExcept(client.nodeId, line)
                    }
                    is MeshControlMessage.SpeakingState -> {
                        val node = activeNodeMap[msg.nodeId]
                        if (node != null) {
                            activeNodeMap[msg.nodeId] = node.copy(isSpeaking = msg.isSpeaking)
                            updateNodesFlow()
                        }
                        broadcastExcept(client.nodeId, line)
                    }
                    is MeshControlMessage.RadioState -> {
                        broadcastExcept(client.nodeId, line)
                    }
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            // Disconnection
        } finally {
            removeClient(client.nodeId, "disconnected")
        }
    }

    private fun removeClient(nodeId: Int, reason: String) {
        val client = connectedClients.remove(nodeId)
        val node = activeNodeMap.remove(nodeId)
        updateNodesFlow()

        try {
            client?.socket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (node != null) {
            coroutineScope.launch {
                _systemEvents.emit("[Node-$nodeId] ${node.deviceName} $reason")
                broadcastNodeList()
            }
        }
    }

    private fun startHeartbeatMonitor() {
        heartbeatCheckJob = coroutineScope.launch(Dispatchers.Default) {
            while (isActive && isRunning.get()) {
                delay(3000)
                val now = System.currentTimeMillis()
                for ((id, client) in connectedClients) {
                    if (now - client.lastPingTime > MeshConstants.NODE_TIMEOUT_MS) {
                        removeClient(id, "timed out (dropped)")
                    }
                }
            }
        }
    }

    private fun startBeaconBroadcaster() {
        beaconJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                beaconSocket = DatagramSocket().apply { broadcast = true }
                val broadcastAddr = NetworkUtils.getBroadcastAddress()

                while (isActive && isRunning.get()) {
                    val beaconPayload = "${MeshConstants.BEACON_MAGIC}:$hostIp:8888:${activeNodeMap.size}".toByteArray()
                    val packet = DatagramPacket(
                        beaconPayload,
                        beaconPayload.size,
                        broadcastAddr,
                        MeshConstants.UDP_DISCOVERY_PORT
                    )
                    beaconSocket?.send(packet)
                    delay(2000)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun broadcastChat(chatMessage: MeshControlMessage.ChatMessage) {
        val json = chatMessage.toJsonString()
        broadcastAll(json)
    }

    fun broadcastSpeakingState(nodeId: Int, isSpeaking: Boolean) {
        val existing = activeNodeMap[nodeId]
        if (existing != null) {
            activeNodeMap[nodeId] = existing.copy(isSpeaking = isSpeaking)
            updateNodesFlow()
        }
        val msg = MeshControlMessage.SpeakingState(nodeId, isSpeaking).toJsonString()
        broadcastAll(msg)
    }

    fun broadcastRadioState(isBroadcasting: Boolean, title: String) {
        val msg = MeshControlMessage.RadioState(isBroadcasting, title).toJsonString()
        broadcastAll(msg)
    }

    private fun broadcastNodeList() {
        val currentNodes = activeNodeMap.values.toList()
        val update = MeshControlMessage.NodeListUpdate(currentNodes, currentNodes.size).toJsonString()
        broadcastAll(update)
    }

    private fun broadcastAll(message: String) {
        for (client in connectedClients.values) {
            client.send(message)
        }
    }

    private fun broadcastExcept(excludeNodeId: Int, message: String) {
        for ((id, client) in connectedClients) {
            if (id != excludeNodeId) {
                client.send(message)
            }
        }
    }

    private fun updateNodesFlow() {
        _nodesList.value = activeNodeMap.values.sortedBy { it.nodeId }
    }

    fun getClientIps(): List<String> {
        return activeNodeMap.values.filter { !it.isHost }.map { it.ipAddress }
    }

    fun stopServer() {
        isRunning.set(false)
        serverJob?.cancel()
        beaconJob?.cancel()
        heartbeatCheckJob?.cancel()

        for ((_, client) in connectedClients) {
            try {
                client.socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        connectedClients.clear()
        activeNodeMap.clear()
        updateNodesFlow()

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        serverSocket = null

        try {
            beaconSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        beaconSocket = null
    }
}

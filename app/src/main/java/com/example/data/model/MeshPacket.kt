package com.example.data.model

import org.json.JSONArray
import org.json.JSONObject

object MeshConstants {
    const val TCP_PORT = 8888
    const val UDP_AUDIO_PORT = 8889
    const val UDP_DISCOVERY_PORT = 8887
    const val MAX_TOTAL_NODES = 10
    const val BEACON_MAGIC = "SPLINTER_HOST_BEACON"
    const val PING_INTERVAL_MS = 3000L
    const val NODE_TIMEOUT_MS = 9000L
    
    // Audio Packet Type Flags
    const val AUDIO_TYPE_INTERCOM: Byte = 0x01
    const val AUDIO_TYPE_RADIO: Byte = 0x02
}

sealed class MeshControlMessage {
    data class JoinRequest(val deviceName: String) : MeshControlMessage()
    data class JoinResponse(
        val status: String, // "CONNECTED" or "FULL"
        val assignedId: Int,
        val hostIp: String,
        val message: String = ""
    ) : MeshControlMessage()
    data class Ping(val nodeId: Int, val timestamp: Long) : MeshControlMessage()
    data class Pong(val timestamp: Long) : MeshControlMessage()
    data class NodeListUpdate(val nodes: List<MeshNode>, val activeCount: Int) : MeshControlMessage()
    data class SpeakingState(val nodeId: Int, val isSpeaking: Boolean) : MeshControlMessage()
    data class RadioState(val isBroadcasting: Boolean, val title: String) : MeshControlMessage()
    data class ChatMessage(
        val messageId: String,
        val senderId: Int,
        val senderName: String,
        val text: String,
        val timestamp: Long,
        val attachmentType: String? = null,
        val attachmentData: String? = null
    ) : MeshControlMessage()

    fun toJsonString(): String {
        val json = JSONObject()
        when (this) {
            is JoinRequest -> {
                json.put("action", "JOIN")
                json.put("device_name", deviceName)
            }
            is JoinResponse -> {
                json.put("status", status)
                json.put("assigned_id", assignedId)
                json.put("host_ip", hostIp)
                json.put("message", message)
            }
            is Ping -> {
                json.put("action", "PING")
                json.put("node_id", nodeId)
                json.put("timestamp", timestamp)
            }
            is Pong -> {
                json.put("action", "PONG")
                json.put("timestamp", timestamp)
            }
            is NodeListUpdate -> {
                json.put("action", "NODE_LIST")
                json.put("active_count", activeCount)
                val arr = JSONArray()
                for (node in nodes) {
                    val nJson = JSONObject()
                    nJson.put("id", node.nodeId)
                    nJson.put("name", node.deviceName)
                    nJson.put("ip", node.ipAddress)
                    nJson.put("is_host", node.isHost)
                    nJson.put("speaking", node.isSpeaking)
                    arr.put(nJson)
                }
                json.put("nodes", arr)
            }
            is SpeakingState -> {
                json.put("action", "SPEAKING_STATE")
                json.put("node_id", nodeId)
                json.put("speaking", isSpeaking)
            }
            is RadioState -> {
                json.put("action", "RADIO_STATE")
                json.put("is_broadcasting", isBroadcasting)
                json.put("title", title)
            }
            is ChatMessage -> {
                json.put("action", "CHAT")
                json.put("msg_id", messageId)
                json.put("sender_id", senderId)
                json.put("sender_name", senderName)
                json.put("text", text)
                json.put("timestamp", timestamp)
                if (attachmentType != null) json.put("attachment_type", attachmentType)
                if (attachmentData != null) json.put("attachment_data", attachmentData)
            }
        }
        return json.toString()
    }

    companion object {
        fun fromJsonString(jsonStr: String): MeshControlMessage? {
            return try {
                val json = JSONObject(jsonStr)
                if (json.has("status")) {
                    return JoinResponse(
                        status = json.getString("status"),
                        assignedId = json.optInt("assigned_id", -1),
                        hostIp = json.optString("host_ip", ""),
                        message = json.optString("message", "")
                    )
                }
                when (json.optString("action")) {
                    "JOIN" -> JoinRequest(json.getString("device_name"))
                    "PING" -> Ping(json.getInt("node_id"), json.getLong("timestamp"))
                    "PONG" -> Pong(json.getLong("timestamp"))
                    "NODE_LIST" -> {
                        val arr = json.optJSONArray("nodes") ?: JSONArray()
                        val list = mutableListOf<MeshNode>()
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            list.add(
                                MeshNode(
                                    nodeId = o.getInt("id"),
                                    deviceName = o.getString("name"),
                                    ipAddress = o.getString("ip"),
                                    isHost = o.optBoolean("is_host", false),
                                    isSpeaking = o.optBoolean("speaking", false)
                                )
                            )
                        }
                        NodeListUpdate(list, json.optInt("active_count", list.size))
                    }
                    "SPEAKING_STATE" -> SpeakingState(
                        nodeId = json.getInt("node_id"),
                        isSpeaking = json.getBoolean("speaking")
                    )
                    "RADIO_STATE" -> RadioState(
                        isBroadcasting = json.getBoolean("is_broadcasting"),
                        title = json.optString("title", "Live Radio")
                    )
                    "CHAT" -> ChatMessage(
                        messageId = json.optString("msg_id", System.currentTimeMillis().toString()),
                        senderId = json.getInt("sender_id"),
                        senderName = json.getString("sender_name"),
                        text = json.getString("text"),
                        timestamp = json.getLong("timestamp"),
                        attachmentType = json.optString("attachment_type", null),
                        attachmentData = json.optString("attachment_data", null)
                    )
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

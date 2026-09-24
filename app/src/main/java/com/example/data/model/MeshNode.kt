package com.example.data.model

data class MeshNode(
    val nodeId: Int,
    val deviceName: String,
    val ipAddress: String,
    val isHost: Boolean = false,
    val isSpeaking: Boolean = false,
    val isRadioBroadcasting: Boolean = false,
    val lastPingMs: Long = System.currentTimeMillis(),
    val pingLatencyMs: Long = 0,
    val joinedAt: Long = System.currentTimeMillis()
)

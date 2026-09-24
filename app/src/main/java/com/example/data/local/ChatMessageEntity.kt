package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val messageId: String,
    val senderId: Int,
    val senderName: String,
    val text: String,
    val timestamp: Long,
    val isSystemNotification: Boolean = false,
    val attachmentType: String? = null,
    val attachmentData: String? = null,
    val isOutgoing: Boolean = false
)

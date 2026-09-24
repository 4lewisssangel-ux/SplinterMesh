package com.example.data.local

import kotlinx.coroutines.flow.Flow

class SplinterRepository(private val chatMessageDao: ChatMessageDao) {
    val allMessages: Flow<List<ChatMessageEntity>> = chatMessageDao.getAllMessages()

    suspend fun insertMessage(message: ChatMessageEntity): Long {
        return chatMessageDao.insertMessage(message)
    }

    suspend fun clearMessages() {
        chatMessageDao.clearAllMessages()
    }
}

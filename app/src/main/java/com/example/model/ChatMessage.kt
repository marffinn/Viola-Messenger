package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val peerIp: String,
    val senderName: String,
    val senderIp: String,
    val messageText: String,
    val encryptedData: String,
    val encryptedAesKey: String,
    val iv: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isFromMe: Boolean,
    val isSecure: Boolean = true,
    val fileType: String? = null,
    val fileName: String? = null,
    val localFilePath: String? = null,
    val fileSize: Long = 0L
)

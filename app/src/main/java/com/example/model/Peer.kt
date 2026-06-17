package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "peers")
data class Peer(
    @PrimaryKey val ip: String,
    val alias: String,
    val publicKey: String,
    val lastSeen: Long = System.currentTimeMillis(),
    val isOnline: Boolean = false
)

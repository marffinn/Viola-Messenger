package com.example.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import com.example.model.ChatMessage
import com.example.model.Peer
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY lastSeen DESC")
    fun getAllPeers(): Flow<List<Peer>>

    @Query("SELECT * FROM peers WHERE ip = :ip LIMIT 1")
    suspend fun getPeerByIp(ip: String): Peer?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeer(peer: Peer)

    @Query("UPDATE peers SET isOnline = :isOnline, lastSeen = :lastSeen WHERE ip = :ip")
    suspend fun updateOnlineStatus(ip: String, isOnline: Boolean, lastSeen: Long = System.currentTimeMillis())

    @Query("DELETE FROM peers WHERE ip = :ip")
    suspend fun deletePeerByIp(ip: String)
}

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages WHERE peerIp = :peerIp ORDER BY timestamp ASC")
    fun getMessagesForPeer(peerIp: String): Flow<List<ChatMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessage)

    @Query("DELETE FROM chat_messages WHERE peerIp = :peerIp")
    suspend fun clearChatForPeer(peerIp: String)
}

@Database(entities = [Peer::class, ChatMessage::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
    abstract fun chatMessageDao(): ChatMessageDao
}

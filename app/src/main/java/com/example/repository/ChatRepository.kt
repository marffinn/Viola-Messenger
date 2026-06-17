package com.example.repository

import com.example.db.ChatMessageDao
import com.example.db.PeerDao
import com.example.model.ChatMessage
import com.example.model.Peer
import kotlinx.coroutines.flow.Flow

class ChatRepository(
    private val peerDao: PeerDao,
    private val chatMessageDao: ChatMessageDao
) {
    val allPeers: Flow<List<Peer>> = peerDao.getAllPeers()

    fun getMessagesForPeer(peerIp: String): Flow<List<ChatMessage>> =
        chatMessageDao.getMessagesForPeer(peerIp)

    suspend fun getPeerByIp(ip: String): Peer? =
        peerDao.getPeerByIp(ip)

    suspend fun insertPeer(peer: Peer) =
        peerDao.insertPeer(peer)

    suspend fun updateOnlineStatus(ip: String, isOnline: Boolean) =
        peerDao.updateOnlineStatus(ip, isOnline)

    suspend fun deletePeerByIp(ip: String) {
        peerDao.deletePeerByIp(ip)
        chatMessageDao.clearChatForPeer(ip)
    }

    suspend fun insertMessage(message: ChatMessage) =
        chatMessageDao.insertMessage(message)

    suspend fun clearChatForPeer(peerIp: String) =
        chatMessageDao.clearChatForPeer(peerIp)
}

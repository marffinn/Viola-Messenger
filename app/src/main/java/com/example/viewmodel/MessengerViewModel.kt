package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.db.AppDatabase
import com.example.model.ChatMessage
import com.example.model.Peer
import com.example.network.P2PMessagingEngine
import com.example.repository.ChatRepository
import com.example.security.EncryptionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.PrivateKey
import java.security.PublicKey
import java.util.Collections
import java.io.File
import android.net.Uri

data class MessengerUiState(
    val ownName: String = "",
    val ownIp: String = "127.0.0.1",
    val ownPort: Int = 12120,
    val ownAvatar: String = "👤",
    val ownFingerprint: String = "",
    val activeChatPeerIp: String? = null,
    val peers: List<Peer> = emptyList(),
    val isScanning: Boolean = false,
    val scanProgress: Float = 0f,
    val isServerRunning: Boolean = false,
    val activeChatPeerTyping: Boolean = false,
    val errorMessage: String? = null
)

class MessengerViewModel(application: Application) : AndroidViewModel(application), P2PMessagingEngine.P2PListener {

    private val sharedPrefs = application.getSharedPreferences("local_crypt_prefs", Context.MODE_PRIVATE)
    
    // Intilize Room DB & Repository
    private val db = Room.databaseBuilder(
        application.applicationContext,
        AppDatabase::class.java, "local_crypt_db"
    ).fallbackToDestructiveMigration().build()
    
    val repository = ChatRepository(db.peerDao(), db.chatMessageDao())

    private val _uiState = MutableStateFlow(MessengerUiState())
    val uiState: StateFlow<MessengerUiState> = _uiState.asStateFlow()

    // Observe active peer message stream
    val activeChatMessages: StateFlow<List<ChatMessage>> = _uiState
        .flatMapLatest { state ->
            state.activeChatPeerIp?.let { ip ->
                repository.getMessagesForPeer(ip)
            } ?: flowOf(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val p2pEngine = P2PMessagingEngine(viewModelScope, this)
    
    private var myPublicKey: PublicKey? = null
    private var myPrivateKey: PrivateKey? = null
    private var typingJob: Job? = null

    init {
        loadOrCreateIdentity()
        resolveLocalIp()
        startReceiverServer()
        observePeers()
    }

    private fun loadOrCreateIdentity() {
        var name = sharedPrefs.getString("own_name", null)
        if (name == null) {
            name = "Node_${android.os.Build.MODEL.replace(" ", "_").take(10)}"
            sharedPrefs.edit().putString("own_name", name).apply()
        }

        var pubKeyStr = sharedPrefs.getString("public_key", null)
        var privKeyStr = sharedPrefs.getString("private_key", null)

        if (pubKeyStr == null || privKeyStr == null) {
            try {
                val kp = EncryptionManager.generateKeyPair()
                pubKeyStr = EncryptionManager.publicKeyToString(kp.public)
                privKeyStr = EncryptionManager.privateKeyToString(kp.private)
                sharedPrefs.edit()
                    .putString("public_key", pubKeyStr)
                    .putString("private_key", privKeyStr)
                    .apply()
                myPublicKey = kp.public
                myPrivateKey = kp.private
            } catch (e: Exception) {
                Log.e("MessengerViewModel", "Keygen error", e)
            }
        } else {
            myPublicKey = EncryptionManager.stringToPublicKey(pubKeyStr)
            myPrivateKey = EncryptionManager.stringToPrivateKey(privKeyStr)
        }

        val fingerprint = pubKeyStr?.let { EncryptionManager.getFingerprint(it) } ?: "Unknown"
        val avatar = sharedPrefs.getString("own_avatar", "👤") ?: "👤"

        _uiState.update {
            it.copy(
                ownName = name ?: "SecureNode",
                ownFingerprint = fingerprint,
                ownPort = sharedPrefs.getInt("listening_port", P2PMessagingEngine.SECURE_PORT),
                ownAvatar = avatar
            )
        }
    }

    fun updateOwnName(newName: String) {
        if (newName.isBlank()) return
        sharedPrefs.edit().putString("own_name", newName).apply()
        _uiState.update { it.copy(ownName = newName) }
        
        // Broadcast identity update to all online peers!
        viewModelScope.launch(Dispatchers.IO) {
            uiState.value.peers.filter { it.isOnline }.forEach { peer ->
                sendHandshakeToPeer(peer.ip)
            }
        }
    }

    fun updateOwnAvatar(newAvatar: String) {
        if (newAvatar.isBlank()) return
        sharedPrefs.edit().putString("own_avatar", newAvatar).apply()
        _uiState.update { it.copy(ownAvatar = newAvatar) }
        
        // Broadcast identity update to all online peers!
        viewModelScope.launch(Dispatchers.IO) {
            uiState.value.peers.filter { it.isOnline }.forEach { peer ->
                sendHandshakeToPeer(peer.ip)
            }
        }
    }

    fun updateOwnPort(newPort: Int) {
        if (newPort !in 1024..65535) return
        sharedPrefs.edit().putInt("listening_port", newPort).apply()
        _uiState.update { it.copy(ownPort = newPort) }
        
        // Restart server with new port
        p2pEngine.stopServer()
        p2pEngine.startServer(newPort)
    }

    fun resolveLocalIp() {
        viewModelScope.launch(Dispatchers.IO) {
            val ip = getLocalIpAddress() ?: "127.0.0.1"
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(ownIp = ip) }
            }
        }
    }

    private fun startReceiverServer() {
        val port = sharedPrefs.getInt("listening_port", P2PMessagingEngine.SECURE_PORT)
        p2pEngine.startServer(port)
    }

    private fun observePeers() {
        viewModelScope.launch {
            repository.allPeers.collect { peerList ->
                _uiState.update { it.copy(peers = peerList) }
            }
        }
    }

    fun setActiveChat(peerIp: String?) {
        _uiState.update { it.copy(activeChatPeerIp = peerIp, activeChatPeerTyping = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // Explicit manual connecting to helper Node
    fun connectToPeer(ip: String, alias: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = sendHandshakeToPeer(ip)
            if (success) {
                val pubKeyStr = sharedPrefs.getString("public_key", "") ?: ""
                val peer = Peer(
                    ip = ip,
                    alias = alias,
                    publicKey = "", // Handshake callback will update this
                    isOnline = true,
                    lastSeen = System.currentTimeMillis()
                )
                repository.insertPeer(peer)
                withContext(Dispatchers.Main) {
                    setActiveChat(ip)
                }
            } else {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(errorMessage = "Could not reach peer at $ip:$PORT") }
                }
            }
        }
    }

    fun removePeer(ip: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deletePeerByIp(ip)
            if (uiState.value.activeChatPeerIp == ip) {
                withContext(Dispatchers.Main) {
                    setActiveChat(null)
                }
            }
        }
    }

    fun clearCurrentChat() {
        val currentIp = uiState.value.activeChatPeerIp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearChatForPeer(currentIp)
        }
    }

    // Send E2EE Message
    fun sendEncryptedMessage(text: String) {
        val recipientIp = uiState.value.activeChatPeerIp ?: return
        if (text.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Find recipient in database to get their Public Key
                val recipient = repository.getPeerByIp(recipientIp)
                if (recipient == null || recipient.publicKey.isBlank()) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(errorMessage = "Recipient Public Key unavailable. Initiate a Handshake first!") }
                    }
                    return@launch
                }

                // 1. Generate random session AES key
                val sessionAesKey = EncryptionManager.generateSessionAesKey()

                // 2. Encrypt message with AES key
                val aesResult = EncryptionManager.encryptWithAes(text, sessionAesKey)

                // 3. Encrypt AES key using recipient's RSA Public Key
                val recipientPubKey = EncryptionManager.stringToPublicKey(recipient.publicKey)
                val encAesKeyStr = EncryptionManager.encryptAesKeyWithRsa(sessionAesKey, recipientPubKey)

                // 4. Build message payload JSON
                val payload = JSONObject().apply {
                    put("event", "message")
                    put("senderIp", uiState.value.ownIp)
                    put("senderPort", uiState.value.ownPort)
                    put("senderName", uiState.value.ownName)
                    put("encryptedData", aesResult.encryptedDataBase64)
                    put("encryptedAesKey", encAesKeyStr)
                    put("iv", aesResult.ivBase64)
                }

                // 5. Transmit
                val success = p2pEngine.transmitEvent(recipientIp, payload)

                // 6. Save in local database regardless of online state (like WhatsApp/Signal)
                val chatMessage = ChatMessage(
                    peerIp = recipientIp,
                    senderName = uiState.value.ownName,
                    senderIp = uiState.value.ownIp,
                    messageText = text,
                    encryptedData = aesResult.encryptedDataBase64,
                    encryptedAesKey = encAesKeyStr,
                    iv = aesResult.ivBase64,
                    isFromMe = true
                )
                repository.insertMessage(chatMessage)

                // If sent successfully, make sure peer is marked online!
                if (success) {
                    repository.updateOnlineStatus(recipientIp, true)
                } else {
                    repository.updateOnlineStatus(recipientIp, false)
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(errorMessage = "Message saved locally but delivery failed. Recipient is currently offline.") }
                    }
                }
            } catch (e: Exception) {
                Log.e("MessengerViewModel", "Encryption send error", e)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(errorMessage = "Cryptographic setup failed: ${e.localizedMessage}") }
                }
            }
        }
    }

    fun sendFileOrImage(uri: Uri, fileName: String, fileType: String, fileSize: Long) {
        val recipientIp = uiState.value.activeChatPeerIp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>().applicationContext
                val inputStream = context.contentResolver.openInputStream(uri) ?: return@launch
                val bytes = inputStream.readBytes()
                inputStream.close()
                
                val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
                
                val recipient = repository.getPeerByIp(recipientIp) ?: return@launch
                if (recipient.publicKey.isBlank()) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(errorMessage = "Recipient Public Key unavailable. Initiate a Handshake first!") }
                    }
                    return@launch
                }
                
                // 1. Generate random session AES key
                val sessionAesKey = EncryptionManager.generateSessionAesKey()
                
                // 2. Encrypt Base64 data with AES key
                val aesResult = EncryptionManager.encryptWithAes(base64Data, sessionAesKey)
                
                // 3. Encrypt AES key using recipient's RSA Public Key
                val recipientPubKey = EncryptionManager.stringToPublicKey(recipient.publicKey)
                val encAesKeyStr = EncryptionManager.encryptAesKeyWithRsa(sessionAesKey, recipientPubKey)
                
                // 4. Build message payload JSON
                val payload = JSONObject().apply {
                    put("event", "message")
                    put("senderIp", uiState.value.ownIp)
                    put("senderPort", uiState.value.ownPort)
                    put("senderName", uiState.value.ownName)
                    put("encryptedData", aesResult.encryptedDataBase64)
                    put("encryptedAesKey", encAesKeyStr)
                    put("iv", aesResult.ivBase64)
                    put("fileType", fileType)
                    put("fileName", fileName)
                    put("fileSize", fileSize)
                }
                
                // 5. Save locally in Cache/Files so we can display it instantly!
                val localDir = File(context.filesDir, "received_files")
                if (!localDir.exists()) localDir.mkdirs()
                val localFile = File(localDir, "${System.currentTimeMillis()}_$fileName")
                localFile.writeBytes(bytes)
                
                // 6. Transmit
                val success = p2pEngine.transmitEvent(recipientIp, payload)
                
                // 7. Save in local database
                val chatMessage = ChatMessage(
                    peerIp = recipientIp,
                    senderName = uiState.value.ownName,
                    senderIp = uiState.value.ownIp,
                    messageText = "[Attachment: $fileName]", // fallback text
                    encryptedData = aesResult.encryptedDataBase64,
                    encryptedAesKey = encAesKeyStr,
                    iv = aesResult.ivBase64,
                    isFromMe = true,
                    fileType = fileType,
                    fileName = fileName,
                    localFilePath = localFile.absolutePath,
                    fileSize = fileSize
                )
                repository.insertMessage(chatMessage)
                
                if (success) {
                    repository.updateOnlineStatus(recipientIp, true)
                } else {
                    repository.updateOnlineStatus(recipientIp, false)
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(errorMessage = "File saved locally but delivery failed. Recipient is offline.") }
                    }
                }
            } catch (e: Exception) {
                Log.e("MessengerViewModel", "Failed to send file", e)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(errorMessage = "Failed to send file: ${e.localizedMessage}") }
                }
            }
        }
    }

    // Sends a typing notification to current participant
    fun sendTypingState(isTyping: Boolean) {
        val recipientIp = uiState.value.activeChatPeerIp ?: return
        typingJob?.cancel()
        typingJob = viewModelScope.launch(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("event", "typing")
                put("senderIp", uiState.value.ownIp)
                put("senderPort", uiState.value.ownPort)
                put("isTyping", isTyping)
            }
            p2pEngine.transmitEvent(recipientIp, payload)
        }
    }

    // Subnet Fast Scanner Loop
    fun scanSubnet() {
        val myIp = uiState.value.ownIp
        if (myIp == "127.0.0.1") return
        val parts = myIp.split(".")
        if (parts.size != 4) return
        val base = "${parts[0]}.${parts[1]}.${parts[2]}."

        _uiState.update { it.copy(isScanning = true, scanProgress = 0f) }

        viewModelScope.launch(Dispatchers.IO) {
            val total = 254
            var scannedCount = 0
            val semaphore = Semaphore(40) // run at most 40 in parallel to prevent flooding
            val jobs = mutableListOf<Job>()

            for (i in 1..254) {
                val targetIp = base + i
                if (targetIp == myIp) {
                    scannedCount++
                    continue
                }

                val job = launch {
                    semaphore.withPermit {
                        try {
                            val socket = Socket()
                            // short timeout 180ms to keep scans incredibly zippy!
                            socket.connect(InetSocketAddress(targetIp, P2PMessagingEngine.SECURE_PORT), 180)
                            socket.close()

                            // If port is listening, ping them with a Handshake immediately!
                            sendHandshakeToPeer(targetIp)
                        } catch (e: Exception) {
                            // Offline or closed port
                        } finally {
                            synchronized(this@MessengerViewModel) {
                                scannedCount++
                                val progress = scannedCount.toFloat() / total
                                _uiState.update { it.copy(scanProgress = progress) }
                            }
                        }
                    }
                }
                jobs.add(job)
            }

            jobs.forEach { it.join() }
            _uiState.update { it.copy(isScanning = false) }
        }
    }

    private suspend fun sendHandshakeToPeer(peerIp: String): Boolean {
        val myPubKeyStr = sharedPrefs.getString("public_key", "") ?: ""
        val payload = JSONObject().apply {
            put("event", "handshake")
            put("senderIp", uiState.value.ownIp)
            put("senderPort", uiState.value.ownPort)
            put("senderName", uiState.value.ownName)
            put("senderAvatar", uiState.value.ownAvatar)
            put("publicKey", myPubKeyStr)
        }
        return p2pEngine.transmitEvent(peerIp, payload)
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (networkInterface in Collections.list(interfaces)) {
                val addresses = networkInterface.inetAddresses
                for (inetAddress in Collections.list(addresses)) {
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        return inetAddress.hostAddress
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e("IPResolver", "Error resolving local IP", ex)
        }
        return null
    }

    private suspend fun findMatchingPeer(peerIp: String): Peer? {
        val exact = repository.getPeerByIp(peerIp)
        if (exact != null) return exact

        if (peerIp.contains(":")) {
            val baseIp = peerIp.substringBefore(":")
            val baseMatch = repository.getPeerByIp(baseIp)
            if (baseMatch != null) return baseMatch
        } else {
            val matchWithPort = uiState.value.peers.find { 
                it.ip.substringBefore(":") == peerIp 
            }
            if (matchWithPort != null) return matchWithPort
        }
        return null
    }

    // --- P2PMessagingEngine.P2PListener implementations ---

    override fun onPeerConnected(ip: String, name: String, publicKey: String, avatar: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = findMatchingPeer(ip)
            val ipToUse = existing?.ip ?: ip
            val updatedPeer = if (existing != null) {
                existing.copy(
                    alias = name,
                    publicKey = publicKey.ifBlank { existing.publicKey },
                    isOnline = true,
                    lastSeen = System.currentTimeMillis(),
                    avatar = avatar
                )
            } else {
                Peer(
                    ip = ipToUse,
                    alias = name,
                    publicKey = publicKey,
                    isOnline = true,
                    lastSeen = System.currentTimeMillis(),
                    avatar = avatar
                )
            }
            repository.insertPeer(updatedPeer)

            // If we didn't have their public key or they are new, reply with our own Handshake so handshake is mutual!
            if (existing == null || existing.publicKey.isBlank()) {
                sendHandshakeToPeer(ipToUse)
            }
        }
    }

    override fun onMessageReceived(
        peerIp: String,
        senderName: String,
        encryptedData: String,
        encryptedAesKey: String,
        iv: String,
        fileType: String?,
        fileName: String?,
        fileSize: Long
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val existing = findMatchingPeer(peerIp)
                val ipToUse = existing?.ip ?: peerIp
                var peer = existing
                if (peer == null) {
                    peer = Peer(
                        ip = ipToUse,
                        alias = senderName,
                        publicKey = "", // Handshake flow will replenish this
                        isOnline = true,
                        lastSeen = System.currentTimeMillis()
                    )
                    repository.insertPeer(peer)
                    sendHandshakeToPeer(ipToUse) // request identity
                } else {
                    repository.updateOnlineStatus(ipToUse, true)
                }

                _uiState.update { state ->
                    if (state.activeChatPeerIp == ipToUse) {
                        state.copy(activeChatPeerTyping = false) // stop typing on new message
                    } else {
                        state
                    }
                }

                // 1. Decrypt AES session key with my RSA Private Key
                val myPrivKey = myPrivateKey
                if (myPrivKey != null) {
                    val sessionAesKey = EncryptionManager.decryptAesKeyWithRsa(encryptedAesKey, myPrivKey)

                    // 2. Decrypt text message with the AES key
                    val plainText = EncryptionManager.decryptWithAes(encryptedData, sessionAesKey, iv)

                    // 3. Save file if present
                    var localPath: String? = null
                    var finalMsgText = plainText
                    if (fileType != null && fileName != null) {
                        try {
                            val context = getApplication<Application>().applicationContext
                            val localDir = File(context.filesDir, "received_files")
                            if (!localDir.exists()) localDir.mkdirs()
                            val localFile = File(localDir, "${System.currentTimeMillis()}_$fileName")
                            
                            val fileBytes = Base64.decode(plainText, Base64.DEFAULT)
                            localFile.writeBytes(fileBytes)
                            localPath = localFile.absolutePath
                            finalMsgText = "[Attachment: $fileName]"
                        } catch (ex: Exception) {
                            Log.e("MessengerViewModel", "Failed to save received file bytes", ex)
                        }
                    }

                    // 4. Write decrypted message to Room
                    val incomingMsg = ChatMessage(
                        peerIp = ipToUse,
                        senderName = senderName,
                        senderIp = ipToUse,
                        messageText = finalMsgText,
                        encryptedData = encryptedData,
                        encryptedAesKey = encryptedAesKey,
                        iv = iv,
                        isFromMe = false,
                        fileType = fileType,
                        fileName = fileName,
                        localFilePath = localPath,
                        fileSize = fileSize
                    )
                    repository.insertMessage(incomingMsg)
                } else {
                    Log.e("MessengerViewModel", "Private key is null on message receive!")
                }
            } catch (e: Exception) {
                Log.e("MessengerViewModel", "Failed to decrypt incoming message", e)
                val existing = findMatchingPeer(peerIp)
                val ipToUse = existing?.ip ?: peerIp
                // Write placeholder "Decryption Failed" error message
                val incomingMsg = ChatMessage(
                    peerIp = ipToUse,
                    senderName = senderName,
                    senderIp = ipToUse,
                    messageText = "⚠️ Decryption Failed: Cipher is invalid or matching signature missing.",
                    encryptedData = encryptedData,
                    encryptedAesKey = encryptedAesKey,
                    iv = iv,
                    isFromMe = false,
                    isSecure = false,
                    fileType = fileType,
                    fileName = fileName,
                    fileSize = fileSize
                )
                repository.insertMessage(incomingMsg)
            }
        }
    }

    override fun onTypingStateChanged(peerIp: String, isTyping: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = findMatchingPeer(peerIp)
            val ipToUse = existing?.ip ?: peerIp
            _uiState.update { state ->
                if (state.activeChatPeerIp == ipToUse) {
                    state.copy(activeChatPeerTyping = isTyping)
                } else {
                    state
                }
            }
        }
    }

    override fun onServerStatusChanged(isListening: Boolean) {
        _uiState.update { it.copy(isServerRunning = isListening) }
    }

    override fun onCleared() {
        super.onCleared()
        p2pEngine.stopServer()
    }

    companion object {
        private const val PORT = P2PMessagingEngine.SECURE_PORT
    }
}

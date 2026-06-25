package com.example.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class P2PMessagingEngine(
    private val scope: CoroutineScope,
    private val listener: P2PListener
) {
    companion object {
        const val SECURE_PORT = 12120
        private const val TAG = "P2PMessagingEngine"
        private const val TIMEOUT_MS = 2500
    }

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var listenJob: Job? = null
    
    // Custom single-thread executor dispatcher to guarantee in-order delivery
    private val networkDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    fun startServer(port: Int = SECURE_PORT) {
        if (isRunning) return
        isRunning = true
        listenJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port)
                listener.onServerStatusChanged(true)
                Log.d(TAG, "Sync server started on port $port")

                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    scope.launch(Dispatchers.IO) {
                        handleIncomingConnection(client)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server socket error", e)
                listener.onServerStatusChanged(false)
            } finally {
                listener.onServerStatusChanged(false)
            }
        }
    }

    fun stopServer() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        }
        serverSocket = null
        listenJob?.cancel()
        listenJob = null
    }

    private suspend fun handleIncomingConnection(socket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                socket.soTimeout = 8000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), "UTF-8"))
                val line = reader.readLine()
                if (line != null) {
                    val json = JSONObject(line)
                    val event = json.optString("event")
                    val remoteIp = socket.inetAddress?.hostAddress ?: ""
                    val senderPort = json.optInt("senderPort", SECURE_PORT)
                    val peerIp = if (senderPort != SECURE_PORT) "$remoteIp:$senderPort" else remoteIp
                    
                    Log.d(TAG, "Received network event: $event from $peerIp")

                    withContext(Dispatchers.Main) {
                        when (event) {
                            "handshake" -> {
                                val senderName = json.getString("senderName")
                                val publicKey = json.getString("publicKey")
                                val senderAvatar = json.optString("senderAvatar", "👤")
                                listener.onPeerConnected(peerIp, senderName, publicKey, senderAvatar)
                            }
                            "message" -> {
                                val senderName = json.getString("senderName")
                                val encryptedData = json.getString("encryptedData")
                                val encryptedAesKey = json.getString("encryptedAesKey")
                                val iv = json.getString("iv")
                                val fileType = json.optString("fileType").takeIf { it.isNotBlank() }
                                val fileName = json.optString("fileName").takeIf { it.isNotBlank() }
                                val fileSize = json.optLong("fileSize", 0L)
                                listener.onMessageReceived(
                                    peerIp = peerIp,
                                    senderName = senderName,
                                    encryptedData = encryptedData,
                                    encryptedAesKey = encryptedAesKey,
                                    iv = iv,
                                    fileType = fileType,
                                    fileName = fileName,
                                    fileSize = fileSize
                                )
                            }
                            "typing" -> {
                                val isTyping = json.getBoolean("isTyping")
                                listener.onTypingStateChanged(peerIp, isTyping)
                            }
                            "ping" -> {
                                // Simple ping response could be handled or logged
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing incoming connection", e)
            } finally {
                try {
                    socket.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing input socket", e)
                }
            }
        }
    }

    suspend fun transmitEvent(peerIp: String, json: JSONObject): Boolean {
        return withContext(networkDispatcher) {
            var socket: Socket? = null
            try {
                socket = Socket()
                val colonIndex = peerIp.lastIndexOf(':')
                val (host, port) = if (colonIndex != -1) {
                    val portStr = peerIp.substring(colonIndex + 1)
                    val parsedPort = portStr.toIntOrNull()
                    if (parsedPort != null && (peerIp.count { it == ':' } == 1 || peerIp.contains(']'))) {
                        Pair(peerIp.substring(0, colonIndex).removePrefix("[").removeSuffix("]"), parsedPort)
                    } else if (parsedPort != null && peerIp.count { it == ':' } > 1 && parsedPort > 1024) {
                        Pair(peerIp.substring(0, colonIndex), parsedPort)
                    } else {
                        Pair(peerIp, SECURE_PORT)
                    }
                } else {
                    Pair(peerIp, SECURE_PORT)
                }
                socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), "UTF-8"))
                writer.write(json.toString())
                writer.newLine()
                writer.flush()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to transmit event to $peerIp", e)
                false
            } finally {
                try {
                    socket?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to close client socket", e)
                }
            }
        }
    }

    suspend fun pingPeer(peerIp: String): Boolean {
        val pingJson = JSONObject().apply {
            put("event", "ping")
        }
        return transmitEvent(peerIp, pingJson)
    }

    interface P2PListener {
        fun onPeerConnected(ip: String, name: String, publicKey: String, avatar: String)
        fun onMessageReceived(
            peerIp: String,
            senderName: String,
            encryptedData: String,
            encryptedAesKey: String,
            iv: String,
            fileType: String?,
            fileName: String?,
            fileSize: Long
        )
        fun onTypingStateChanged(peerIp: String, isTyping: Boolean)
        fun onServerStatusChanged(isListening: Boolean)
    }
}

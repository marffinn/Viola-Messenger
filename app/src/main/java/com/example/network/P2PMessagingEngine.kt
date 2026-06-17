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

    fun startServer() {
        if (isRunning) return
        isRunning = true
        listenJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(SECURE_PORT)
                listener.onServerStatusChanged(true)
                Log.d(TAG, "Sync server started on port $SECURE_PORT")

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
                    Log.d(TAG, "Received network event: $event from ${socket.inetAddress.hostAddress}")

                    withContext(Dispatchers.Main) {
                        when (event) {
                            "handshake" -> {
                                val senderName = json.getString("senderName")
                                val senderIp = json.getString("senderIp")
                                val publicKey = json.getString("publicKey")
                                listener.onPeerConnected(senderIp, senderName, publicKey)
                            }
                            "message" -> {
                                val senderIp = json.getString("senderIp")
                                val senderName = json.getString("senderName")
                                val encryptedData = json.getString("encryptedData")
                                val encryptedAesKey = json.getString("encryptedAesKey")
                                val iv = json.getString("iv")
                                listener.onMessageReceived(
                                    peerIp = senderIp,
                                    senderName = senderName,
                                    encryptedData = encryptedData,
                                    encryptedAesKey = encryptedAesKey,
                                    iv = iv
                                )
                            }
                            "typing" -> {
                                val senderIp = json.getString("senderIp")
                                val isTyping = json.getBoolean("isTyping")
                                listener.onTypingStateChanged(senderIp, isTyping)
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
                socket.connect(InetSocketAddress(peerIp, SECURE_PORT), TIMEOUT_MS)
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
        fun onPeerConnected(ip: String, name: String, publicKey: String)
        fun onMessageReceived(
            peerIp: String,
            senderName: String,
            encryptedData: String,
            encryptedAesKey: String,
            iv: String
        )
        fun onTypingStateChanged(peerIp: String, isTyping: Boolean)
        fun onServerStatusChanged(isListening: Boolean)
    }
}
